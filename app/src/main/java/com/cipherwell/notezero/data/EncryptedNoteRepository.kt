package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.withTransaction
import com.cipherwell.notezero.BuildConfig
import com.cipherwell.notezero.backup.BackupEnvelope
import com.cipherwell.notezero.backup.BackupFormat
import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.crypto.BiometricKeyManager
import com.cipherwell.notezero.crypto.Bip39Recovery
import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ItemQueries
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey

/**
 * The one and only [NoteRepository] implementation: encrypted Room storage + in-memory
 * decrypted index + vault lifecycle + session auto-lock + encrypted backup envelopes.
 *
 * Security invariants implemented here:
 *  - The VMK exists only in memory as a [ByteArray]; it is never written anywhere (the DB
 *    holds only password/recovery-wrapped copies). [lock] zeroizes it and empties both
 *    index flows. Biometric wraps live in the Keystore/its private prefs, never here.
 *  - Items/folders are persisted ONLY as AES-256-GCM envelopes; the AAD binds the row id
 *    and kind (`CryptoAad.item`), so a ciphertext row copied to another id/kind fails
 *    authentication and is skipped.
 *  - Corrupt/tampered rows are skipped and counted ([corruptRowCount]); they never crash
 *    the vault and are never surfaced as plaintext.
 *  - No log line in this file contains titles, bodies, tags, passwords or keys.
 *  - Every suspend entry point dispatches to [ioDispatcher] internally (main-safe).
 *
 * Persistence layout: item/folder rows carry id + kind/timestamps + ciphertext only; the
 * singleton `vault_meta` row carries key envelopes + non-secret metadata. See
 * docs/DATA-MODEL.md.
 *
 * @param context application context; the constructor shape is frozen for app wiring:
 *   `EncryptedNoteRepository(context)`.
 * @param database optional injected Room database (tests pass an in-memory one).
 * @param preferences optional injected preferences (tests pass an isolated DataStore file).
 * @param biometricKeyManager optional injected Keystore manager.
 * @param ioDispatcher dispatcher for all blocking work (tests may inject a test dispatcher).
 * @param clock millis clock, injected so auto-lock/versioning are testable.
 * @param kdfIterations PBKDF2 iterations for NEW envelopes (existing envelopes keep their
 *   stored count; tests use the contract minimum to stay fast).
 * @param scope long-lived scope for the idle auto-lock timer and preference observers.
 * @param allowAutoLock false only for dev/test builds (`-PdevDisableAutoLock=true`).
 */
class EncryptedNoteRepository(
    context: Context,
    database: NoteZeroDatabase? = null,
    preferences: VaultPreferences? = null,
    biometricKeyManager: BiometricKeyManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdfIterations: Int = NoteCrypto.DEFAULT_ITERATIONS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val allowAutoLock: Boolean = !BuildConfig.DEV_DISABLE_AUTO_LOCK,
) : NoteRepository {

    private val appContext: Context = context.applicationContext ?: context

    /** True when this repository owns the DB file and may close it. */
    private val ownsDatabase: Boolean = database == null
    private val database: NoteZeroDatabase = database ?: NoteZeroDatabase.create(appContext)
    private val itemDao: ItemDao = this.database.itemDao()
    private val folderDao: FolderDao = this.database.folderDao()
    private val vaultMetaDao: VaultMetaDao = this.database.vaultMetaDao()
    private val prefs: VaultPreferences = preferences ?: VaultPreferences.create(appContext)
    private val biometrics: BiometricKeyManager = biometricKeyManager ?: BiometricKeyManager(appContext)

    /**
     * Non-secret marker (boolean existence of the vault's DB) so the synchronous
     * [status] initial value is correct before the first DB read finishes. Contains no
     * metadata; the async presence check corrects it if DB and marker ever disagree.
     */
    private val presenceFile = File(appContext.filesDir, PRESENCE_FILE_NAME)

    private val mutex = Mutex()

    /** The VMK. Memory only; zeroized by [lock]/[close]. Never persisted. */
    @Volatile
    private var vmk: ByteArray? = null

    /** Last-known meta row (wrapped envelopes + non-secret metadata), kept for [VaultInfo]. */
    @Volatile
    private var metaCache: VaultMetaEntity? = null

    private val itemsById = LinkedHashMap<String, Item>()
    private val foldersById = LinkedHashMap<String, Folder>()

    @Volatile
    private var corruptRows = 0

    private var autoLockJob: Job? = null

    /**
     * Guards [externalActivityCount]/[backgroundLockDeferred]. The Activity lifecycle
     * callbacks and the backup flow controller can arrive from different threads, so the
     * deferral decision is always taken under this lock (the lock itself is only requested
     * outside it — [lock] is suspend).
     */
    private val sessionLock = Any()

    /** Number of system-owned activities (SAF pickers) currently launched over the vault. */
    private var externalActivityCount: Int = 0

    /** True when [onAppBackgrounded] happened while an external activity was active. */
    private var backgroundLockDeferred: Boolean = false

    @Volatile
    private var lastInteractionAt: Long = 0L

    private val _status = MutableStateFlow(
        if (presenceFile.exists()) VaultStatus.LOCKED else VaultStatus.SETUP_REQUIRED,
    )
    override val status: StateFlow<VaultStatus> = _status.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    override val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _vaultInfo = MutableStateFlow<VaultInfo?>(null)
    override val vaultInfo: StateFlow<VaultInfo?> = _vaultInfo.asStateFlow()

    private val _autoLockMinutes = MutableStateFlow(VaultPreferences.DEFAULT_AUTO_LOCK_MINUTES)
    override val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Number of rows skipped as corrupt/tampered during the last index load (diagnostics only). */
    internal val corruptRowCount: Int get() = corruptRows

    init {
        scope.launch { reconcileVaultPresence() }
        scope.launch {
            prefs.autoLockMinutes.collect { minutes -> _autoLockMinutes.value = minutes }
        }
        scope.launch {
            prefs.themeMode.collect { mode -> _themeMode.value = mode }
        }
    }

    // ---- vault lifecycle -----------------------------------------------------

    override suspend fun setupVault(password: CharArray): List<String> = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                if (vaultMetaDao.get() != null) {
                    throw IllegalStateException("A vault already exists on this device")
                }
                val iterations = effectiveIterations()
                val newVmk = NoteCrypto.randomVmk() // 32 random bytes
                var handedOff = false
                try {
                    val passwordSalt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                    val passwordEnvelope = KeyEnvelope(
                        type = EnvelopeType.PASSWORD,
                        iterations = iterations,
                        salt = passwordSalt,
                        wrappedVmk = NoteCrypto.wrapVmk(
                            newVmk,
                            NoteCrypto.deriveKek(password, passwordSalt, iterations),
                            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                        ),
                    )
                    val phrase = Bip39Recovery.generatePhrase()
                    val recoverySalt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                    val recoveryEnvelope = KeyEnvelope(
                        type = EnvelopeType.RECOVERY,
                        iterations = iterations,
                        salt = recoverySalt,
                        wrappedVmk = NoteCrypto.wrapVmk(
                            newVmk,
                            NoteCrypto.deriveKekFromPhrase(phrase, recoverySalt, iterations),
                            CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
                        ),
                    )
                    val meta = VaultMetaEntity(
                        vaultId = UUID.randomUUID().toString(),
                        passwordEnvelope = passwordEnvelope.toBytes(),
                        recoveryEnvelope = recoveryEnvelope.toBytes(),
                        schemaVersion = NoteZeroDatabase.SCHEMA_VERSION,
                        createdAt = clock(),
                        biometricEnabled = false,
                    )
                    vaultMetaDao.upsert(meta)
                    metaCache = meta
                    adoptVmk(newVmk)
                    handedOff = true
                    try {
                        loadIndexLocked(newVmk)
                    } catch (e: Exception) {
                        // The freshly created vault cannot fail to load; if it does, fail
                        // closed instead of leaving a half-open session.
                        wipeVmkLocked()
                        itemsById.clear()
                        foldersById.clear()
                        throw e
                    }
                    markVaultPresent()
                    _status.value = VaultStatus.UNLOCKED
                    armAutoLock()
                    phrase
                } finally {
                    if (!handedOff) NoteCrypto.wipe(newVmk)
                }
            } finally {
                NoteCrypto.wipe(password)
            }
        }
    }

    override suspend fun unlock(password: CharArray): UnlockResult = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                if (vmk != null) {
                    // A second unlock request while already open: replace the session only
                    // if the credential proves out, otherwise drop to LOCKED.
                    wipeVmkLocked()
                    itemsById.clear()
                    foldersById.clear()
                    publishIndexLocked()
                    _vaultInfo.value = null
                    _status.value = if (presenceFile.exists()) VaultStatus.LOCKED else VaultStatus.SETUP_REQUIRED
                }
                val meta = vaultMetaDao.get()
                if (meta == null) {
                    forgetVaultPresence()
                    _status.value = VaultStatus.SETUP_REQUIRED
                    return@withLock UnlockResult.CORRUPT_VAULT
                }
                metaCache = meta
                val envelope = KeyEnvelope.fromBytes(meta.passwordEnvelope)
                    ?: return@withLock UnlockResult.CORRUPT_VAULT
                if (envelope.type != EnvelopeType.PASSWORD) return@withLock UnlockResult.CORRUPT_VAULT
                if (!isIterationCountAcceptable(envelope.iterations)) return@withLock UnlockResult.CORRUPT_VAULT

                val candidate = try {
                    NoteCrypto.unwrapVmk(
                        envelope.wrappedVmk,
                        NoteCrypto.deriveKek(password, envelope.salt, envelope.iterations),
                        CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                    )
                } finally {
                    NoteCrypto.wipe(password)
                }
                if (candidate == null || candidate.size != NoteCrypto.VMK_LENGTH_BYTES) {
                    if (candidate != null) NoteCrypto.wipe(candidate)
                    return@withLock UnlockResult.WRONG_PASSWORD
                }
                val loaded = try {
                    adoptVmk(candidate)
                    loadIndexLocked(candidate)
                    true
                } catch (e: Exception) {
                    false
                }
                if (!loaded) {
                    wipeVmkLocked()
                    itemsById.clear()
                    foldersById.clear()
                    publishIndexLocked()
                    _vaultInfo.value = null
                    _status.value = VaultStatus.LOCKED
                    markVaultPresent()
                    return@withLock UnlockResult.CORRUPT_VAULT
                }
                _status.value = VaultStatus.UNLOCKED
                armAutoLock()
                UnlockResult.SUCCESS
            } finally {
                NoteCrypto.wipe(password)
            }
        }
    }

    override suspend fun lock() = withContext(ioDispatcher) {
        mutex.withLock {
            autoLockJob?.cancel()
            autoLockJob = null
            wipeVmkLocked()
            itemsById.clear()
            foldersById.clear()
            corruptRows = 0
            publishIndexLocked()
            _vaultInfo.value = null
            metaCache = null
            val vaultExists = presenceFile.exists() || try {
                vaultMetaDao.get() != null
            } catch (e: Exception) {
                false
            }
            _status.value = if (vaultExists) VaultStatus.LOCKED else VaultStatus.SETUP_REQUIRED
        }
    }

    override suspend fun changePassword(current: CharArray, next: CharArray): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val meta = vaultMetaDao.get() ?: return@withLock false
                    val envelope = KeyEnvelope.fromBytes(meta.passwordEnvelope) ?: return@withLock false
                    if (envelope.type != EnvelopeType.PASSWORD) return@withLock false
                    if (!isIterationCountAcceptable(envelope.iterations)) return@withLock false
                    val candidate = NoteCrypto.unwrapVmk(
                        envelope.wrappedVmk,
                        NoteCrypto.deriveKek(current, envelope.salt, envelope.iterations),
                        CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                    ) ?: return@withLock false
                    try {
                        if (candidate.size != NoteCrypto.VMK_LENGTH_BYTES) return@withLock false
                        if (vmk != null && !NoteCrypto.constantTimeEquals(candidate, vmk!!)) {
                            // Envelope/memory disagreement means the vault state is inconsistent.
                            return@withLock false
                        }
                        val iterations = effectiveIterations()
                        val salt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                        val newWrapped = NoteCrypto.wrapVmk(
                            candidate,
                            NoteCrypto.deriveKek(next, salt, iterations),
                            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                        )
                        val newEnvelope = KeyEnvelope(
                            type = EnvelopeType.PASSWORD,
                            iterations = iterations,
                            salt = salt,
                            wrappedVmk = newWrapped,
                        )
                        // Only the password wrapper changes: item/folder ciphertext stays
                        // byte-identical, which a test pins.
                        vaultMetaDao.updatePasswordEnvelope(newEnvelope.toBytes())
                        metaCache = vaultMetaDao.get()
                        refreshVaultInfoLocked()
                        true
                    } finally {
                        NoteCrypto.wipe(candidate)
                    }
                } finally {
                    NoteCrypto.wipe(current)
                    NoteCrypto.wipe(next)
                }
            }
        }

    override suspend fun resetWithRecoveryPhrase(
        phrase: List<String>,
        newPassword: CharArray,
    ): UnlockResult = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val meta = vaultMetaDao.get() ?: return@withLock UnlockResult.CORRUPT_VAULT
                val recoveryEnvelope = KeyEnvelope.fromBytes(meta.recoveryEnvelope)
                    ?: return@withLock UnlockResult.CORRUPT_VAULT
                if (recoveryEnvelope.type != EnvelopeType.RECOVERY) return@withLock UnlockResult.CORRUPT_VAULT
                if (!isIterationCountAcceptable(recoveryEnvelope.iterations)) {
                    return@withLock UnlockResult.CORRUPT_VAULT
                }
                if (!Bip39Recovery.validatePhrase(phrase)) return@withLock UnlockResult.WRONG_RECOVERY_PHRASE
                val candidate = try {
                    NoteCrypto.unwrapVmk(
                        recoveryEnvelope.wrappedVmk,
                        NoteCrypto.deriveKekFromPhrase(phrase, recoveryEnvelope.salt, recoveryEnvelope.iterations),
                        CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
                    )
                } catch (e: Exception) {
                    null
                }
                if (candidate == null || candidate.size != NoteCrypto.VMK_LENGTH_BYTES) {
                    if (candidate != null) NoteCrypto.wipe(candidate)
                    return@withLock UnlockResult.WRONG_RECOVERY_PHRASE
                }
                var handedOff = false
                try {
                    val iterations = effectiveIterations()
                    // Re-wrap the recovery envelope under a FRESH salt with the SAME phrase.
                    // Rationale (documented in docs/BACKUP-FORMAT.md + DATA-MODEL.md): the frozen
                    // interface returns no new phrase here, and rotating to a phrase the user can
                    // never see would silently kill their only kit. Real phrase rotation is
                    // rotateRecoveryPhrase(currentPassword), which returns the new phrase.
                    val recoverySalt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                    val newRecoveryEnvelope = KeyEnvelope(
                        type = EnvelopeType.RECOVERY,
                        iterations = iterations,
                        salt = recoverySalt,
                        wrappedVmk = NoteCrypto.wrapVmk(
                            candidate,
                            NoteCrypto.deriveKekFromPhrase(phrase, recoverySalt, iterations),
                            CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
                        ),
                    )
                    val passwordSalt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                    val newPasswordEnvelope = KeyEnvelope(
                        type = EnvelopeType.PASSWORD,
                        iterations = iterations,
                        salt = passwordSalt,
                        wrappedVmk = NoteCrypto.wrapVmk(
                            candidate,
                            NoteCrypto.deriveKek(newPassword, passwordSalt, iterations),
                            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                        ),
                    )
                    vaultMetaDao.updateEnvelopes(
                        passwordEnvelope = newPasswordEnvelope.toBytes(),
                        recoveryEnvelope = newRecoveryEnvelope.toBytes(),
                    )
                    metaCache = vaultMetaDao.get()
                    val loaded = try {
                        adoptVmk(candidate)
                        handedOff = true
                        loadIndexLocked(candidate)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (!loaded) {
                        wipeVmkLocked()
                        itemsById.clear()
                        foldersById.clear()
                        publishIndexLocked()
                        _vaultInfo.value = null
                        _status.value = VaultStatus.LOCKED
                        markVaultPresent()
                        return@withLock UnlockResult.CORRUPT_VAULT
                    }
                    markVaultPresent()
                    _status.value = VaultStatus.UNLOCKED
                    armAutoLock()
                    UnlockResult.SUCCESS
                } finally {
                    if (!handedOff) NoteCrypto.wipe(candidate)
                }
            } finally {
                NoteCrypto.wipe(newPassword)
            }
        }
    }

    override suspend fun rotateRecoveryPhrase(currentPassword: CharArray): List<String>? =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val meta = vaultMetaDao.get() ?: return@withLock null
                    val envelope = KeyEnvelope.fromBytes(meta.passwordEnvelope) ?: return@withLock null
                    if (envelope.type != EnvelopeType.PASSWORD) return@withLock null
                    if (!isIterationCountAcceptable(envelope.iterations)) return@withLock null
                    val candidate = NoteCrypto.unwrapVmk(
                        envelope.wrappedVmk,
                        NoteCrypto.deriveKek(currentPassword, envelope.salt, envelope.iterations),
                        CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                    ) ?: return@withLock null
                    try {
                        if (candidate.size != NoteCrypto.VMK_LENGTH_BYTES) return@withLock null
                        val iterations = effectiveIterations()
                        val phrase = Bip39Recovery.generatePhrase()
                        val salt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
                        val newRecoveryEnvelope = KeyEnvelope(
                            type = EnvelopeType.RECOVERY,
                            iterations = iterations,
                            salt = salt,
                            wrappedVmk = NoteCrypto.wrapVmk(
                                candidate,
                                NoteCrypto.deriveKekFromPhrase(phrase, salt, iterations),
                                CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
                            ),
                        )
                        vaultMetaDao.updateRecoveryEnvelope(newRecoveryEnvelope.toBytes())
                        metaCache = vaultMetaDao.get()
                        refreshVaultInfoLocked()
                        phrase
                    } finally {
                        NoteCrypto.wipe(candidate)
                    }
                } finally {
                    NoteCrypto.wipe(currentPassword)
                }
            }
        }

    override suspend fun validateRecoveryPhrase(phrase: List<String>): Boolean =
        withContext(ioDispatcher) {
            // Format + BIP-39 checksum validation only (no KDF), so the recovery screen can
            // give immediate feedback; the credential match happens in resetWithRecoveryPhrase.
            Bip39Recovery.validatePhrase(phrase)
        }

    // ---- biometric -----------------------------------------------------------

    override fun prepareBiometricEnroll(): BiometricEnroll? {
        if (_status.value != VaultStatus.UNLOCKED || vmk == null) return null
        return try {
            biometrics.prepareEnroll()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun completeBiometricEnroll(enroll: BiometricEnroll): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk ?: return@withLock false
                val ok = try {
                    biometrics.completeEnroll(enroll, key)
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    setBiometricEnabledLocked(true)
                }
                ok
            }
        }

    override suspend fun disableBiometricUnlock() = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                biometrics.disable()
            } catch (e: Exception) {
                // Keystore removal is best-effort; never leave the flag claiming enrolment.
            }
            setBiometricEnabledLocked(false)
        }
    }

    override fun prepareBiometricDecrypt(): BiometricDecrypt? {
        val wasEnrolled = metaCache?.biometricEnabled == true
        val decrypt = try {
            biometrics.prepareDecrypt()
        } catch (e: Exception) {
            null
        }
        if (decrypt == null && wasEnrolled) {
            // Keystore key invalidated by re-enrolment / lock-screen reset: crypto-core wiped
            // the stale wrap; reflect it so the UI falls back to password unlock.
            scope.launch { clearInvalidatedBiometricState() }
        }
        return decrypt
    }

    override suspend fun completeBiometricUnlock(decrypt: BiometricDecrypt): UnlockResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                if (vmk != null) {
                    wipeVmkLocked()
                    itemsById.clear()
                    foldersById.clear()
                    publishIndexLocked()
                    _vaultInfo.value = null
                    _status.value = VaultStatus.LOCKED
                }
                val candidate = try {
                    biometrics.completeDecrypt(decrypt)
                } catch (e: Exception) {
                    null
                }
                if (candidate == null || candidate.size != NoteCrypto.VMK_LENGTH_BYTES) {
                    if (candidate != null) NoteCrypto.wipe(candidate)
                    return@withLock UnlockResult.BIOMETRIC_FAILED
                }
                var handedOff = false
                try {
                    val meta = vaultMetaDao.get() ?: return@withLock UnlockResult.CORRUPT_VAULT
                    metaCache = meta
                    adoptVmk(candidate)
                    handedOff = true
                    loadIndexLocked(candidate)
                    markVaultPresent()
                    _status.value = VaultStatus.UNLOCKED
                    armAutoLock()
                    UnlockResult.SUCCESS
                } catch (e: Exception) {
                    wipeVmkLocked()
                    itemsById.clear()
                    foldersById.clear()
                    publishIndexLocked()
                    _vaultInfo.value = null
                    _status.value = VaultStatus.LOCKED
                    UnlockResult.CORRUPT_VAULT
                } finally {
                    if (!handedOff) NoteCrypto.wipe(candidate)
                }
            }
        }

    // ---- session / preferences ----------------------------------------------

    override fun registerInteraction() {
        if (_status.value != VaultStatus.UNLOCKED) return
        armAutoLock()
    }

    override fun onAppBackgrounded() {
        // Security-first default: leaving the app (including screen-off) locks immediately.
        // The one exception is the system file picker (SAF backup export/import), which
        // stops the Activity on purpose: the vault must stay open so the chosen file can be
        // written/read when the result arrives. The idle timer is NOT cancelled here, so a
        // picker left open past the timeout still locks the vault (no permanent suppression).
        if (!allowAutoLock) return
        if (_status.value != VaultStatus.UNLOCKED) return
        val deferred = synchronized(sessionLock) {
            if (externalActivityCount > 0) {
                backgroundLockDeferred = true
                true
            } else {
                false
            }
        }
        if (deferred) return
        requestLockNow()
    }

    override fun onAppForegrounded() {
        // Returning to the foreground ends the external-activity excursion. Reset the
        // counter as well: if a picker result callback never arrives (e.g. the picker
        // process died), leaving it set would weaken all later background locks.
        synchronized(sessionLock) {
            if (backgroundLockDeferred) externalActivityCount = 0
            backgroundLockDeferred = false
        }
        if (_status.value == VaultStatus.UNLOCKED) armAutoLock()
    }

    override fun beginExternalActivity() {
        synchronized(sessionLock) {
            externalActivityCount += 1
        }
    }

    override fun endExternalActivity() {
        val lockNow = synchronized(sessionLock) {
            if (externalActivityCount > 0) externalActivityCount -= 1
            val deferredBackground = externalActivityCount == 0 && backgroundLockDeferred
            if (deferredBackground) backgroundLockDeferred = false
            deferredBackground
        }
        // Result arrived before the app came back to the foreground (timeout/process
        // weirdness): the background lock that was deferred must happen now.
        if (lockNow && _status.value == VaultStatus.UNLOCKED) requestLockNow()
    }

    /** Test/diagnostic hooks: no secret material, exposed as internal for the regression tests. */
    internal fun hasActiveExternalActivity(): Boolean =
        synchronized(sessionLock) { externalActivityCount > 0 }

    internal fun isBackgroundLockDeferred(): Boolean =
        synchronized(sessionLock) { backgroundLockDeferred }

    /** Cancels the idle timer and locks asynchronously, exactly like the background lock. */
    private fun requestLockNow() {
        autoLockJob?.cancel()
        autoLockJob = null
        scope.launch { lock() }
    }

    override suspend fun setAutoLockMinutes(minutes: Int) = withContext(ioDispatcher) {
        val clamped = minutes.coerceIn(
            VaultPreferences.MIN_AUTO_LOCK_MINUTES,
            VaultPreferences.MAX_AUTO_LOCK_MINUTES,
        )
        prefs.setAutoLockMinutes(clamped)
        _autoLockMinutes.value = clamped
        mutex.withLock {
            if (_status.value == VaultStatus.UNLOCKED) armAutoLock()
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) = withContext(ioDispatcher) {
        prefs.setThemeMode(mode)
        _themeMode.value = mode
    }

    /**
     * Deterministic check used by the timer and by unit tests: locks when the idle deadline
     * has passed. Returns true when it locked.
     */
    internal suspend fun evaluateAutoLock(): Boolean {
        if (!allowAutoLock) return false
        if (_status.value != VaultStatus.UNLOCKED) return false
        val minutes = _autoLockMinutes.value
        if (minutes <= 0) return false
        val deadline = lastInteractionAt + minutes * 60_000L
        if (clock() < deadline) return false
        lock()
        return true
    }

    // ---- items ---------------------------------------------------------------

    override suspend fun createItem(kind: ItemKind, folderId: String?): Item =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = requireUnlocked()
                if (folderId != null && foldersById[folderId] == null) {
                    throw IllegalArgumentException("Unknown folder id")
                }
                val now = clock()
                val item = Item(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    title = "",
                    body = "",
                    folderId = folderId,
                    createdAt = now,
                    updatedAt = now,
                    version = 0,
                )
                persistItemLocked(item, key)
                itemsById[item.id] = item
                afterItemMutationLocked()
                item
            }
        }

    override suspend fun saveItem(item: Item): Item = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val saved = saveItemLocked(item, key)
            afterItemMutationLocked()
            saved
        }
    }

    override suspend fun trashItem(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val item = itemsById[id] ?: return@withLock
            saveItemLocked(item.copy(trashed = true), key)
            afterItemMutationLocked()
        }
    }

    override suspend fun restoreItem(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val item = itemsById[id] ?: return@withLock
            saveItemLocked(item.copy(trashed = false), key)
            afterItemMutationLocked()
        }
    }

    override suspend fun deleteItemForever(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            requireUnlocked()
            if (itemsById.remove(id) == null) return@withLock
            itemDao.deleteById(id)
            afterItemMutationLocked()
        }
    }

    override suspend fun emptyTrash() = withContext(ioDispatcher) {
        mutex.withLock {
            requireUnlocked()
            val ids = itemsById.values.filter { it.trashed }.map { it.id }
            if (ids.isEmpty()) return@withLock
            itemDao.deleteByIds(ids)
            ids.forEach(itemsById::remove)
            afterItemMutationLocked()
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val item = itemsById[id] ?: return@withLock
            if (item.pinned == pinned) return@withLock
            saveItemLocked(item.copy(pinned = pinned), key)
            afterItemMutationLocked()
        }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val item = itemsById[id] ?: return@withLock
            if (item.favorite == favorite) return@withLock
            saveItemLocked(item.copy(favorite = favorite), key)
            afterItemMutationLocked()
        }
    }

    override suspend fun moveItem(id: String, folderId: String?) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            if (folderId != null && foldersById[folderId] == null) {
                throw IllegalArgumentException("Unknown folder id")
            }
            val item = itemsById[id] ?: return@withLock
            saveItemLocked(item.copy(folderId = folderId), key)
            afterItemMutationLocked()
        }
    }

    // ---- folders -------------------------------------------------------------

    override suspend fun createFolder(name: String, parentId: String?): Folder =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = requireUnlocked()
                val cleanName = name.trim()
                require(cleanName.isNotEmpty()) { "Folder name must not be blank" }
                if (parentId != null && foldersById[parentId] == null) {
                    throw IllegalArgumentException("Unknown parent folder id")
                }
                val sortOrder = foldersById.values
                    .filter { it.parentId == parentId }
                    .maxOfOrNull { it.sortOrder + 1 }
                    ?: 0
                val folder = Folder(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    parentId = parentId,
                    sortOrder = sortOrder,
                    createdAt = clock(),
                )
                persistFolderLocked(folder, key)
                foldersById[folder.id] = folder
                publishIndexLocked()
                refreshVaultInfoLocked()
                folder
            }
        }

    override suspend fun renameFolder(id: String, name: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val folder = foldersById[id] ?: return@withLock
            val cleanName = name.trim()
            require(cleanName.isNotEmpty()) { "Folder name must not be blank" }
            if (folder.name == cleanName) return@withLock
            val renamed = folder.copy(name = cleanName)
            persistFolderLocked(renamed, key)
            foldersById[id] = renamed
            publishIndexLocked()
        }
    }

    override suspend fun deleteFolder(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val key = requireUnlocked()
            val folder = foldersById[id] ?: return@withLock
            val newParentId = folder.parentId?.takeIf { foldersById.containsKey(it) }
            folderDao.deleteById(id)
            foldersById.remove(id)
            // Children are never deleted implicitly: child folders and items move up to the
            // deleted folder's parent (root when it had none).
            for (child in foldersById.values.toList().filter { it.parentId == id }) {
                val moved = child.copy(parentId = newParentId)
                persistFolderLocked(moved, key)
                foldersById[moved.id] = moved
            }
            for (item in itemsById.values.toList().filter { it.folderId == id }) {
                saveItemLocked(item.copy(folderId = newParentId), key)
            }
            publishIndexLocked()
            refreshVaultInfoLocked()
        }
    }

    // ---- encrypted backup ----------------------------------------------------

    override suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk
                try {
                    if (key == null) return@withLock false
                    val meta = vaultMetaDao.get() ?: return@withLock false
                    val contents = BackupFormat.Contents(
                        exportedAt = clock(),
                        items = itemsById.values.toList(),
                        folders = foldersById.values.toList(),
                    )
                    if (password != null) {
                        val envelope = buildBackupPasswordEnvelope(password, key)
                        BackupFormat.write(
                            out,
                            contents,
                            key,
                            BackupFormat.MODE_EXTRA_PASSWORD,
                            listOf(envelope),
                        )
                    } else {
                        val passwordEnvelope = KeyEnvelope.fromBytes(meta.passwordEnvelope)
                            ?: return@withLock false
                        val recoveryEnvelope = KeyEnvelope.fromBytes(meta.recoveryEnvelope)
                            ?: return@withLock false
                        BackupFormat.write(
                            out,
                            contents,
                            key,
                            BackupFormat.MODE_VAULT_KEY,
                            listOf(passwordEnvelope, recoveryEnvelope),
                        )
                    }
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    wipePassword(password)
                    lastInteractionAt = clock()
                }
            }
        }

    override suspend fun importBackup(input: InputStream, password: CharArray?): BackupImportReport? =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk
                try {
                    if (key == null) return@withLock null
                    val envelope = BackupFormat.read(input) ?: return@withLock null
                    val contents = openBackupLocked(envelope, key, password) ?: return@withLock null
                    applyBackupContentsLocked(contents)
                } catch (e: Exception) {
                    null
                } finally {
                    wipePassword(password)
                    lastInteractionAt = clock()
                }
            }
        }

    // ---- lifecycle helpers ---------------------------------------------------

    /** Releases this repository's resources; tests call it, the app keeps one instance. */
    fun close() {
        synchronized(sessionLock) {
            externalActivityCount = 0
            backgroundLockDeferred = false
        }
        autoLockJob?.cancel()
        autoLockJob = null
        scope.cancel()
        synchronized(this) {
            vmk?.let { bytes ->
                runCatching { NoteCrypto.wipe(bytes) }.onFailure { bytes.fill(0) }
            }
            vmk = null
            itemsById.clear()
            foldersById.clear()
            corruptRows = 0
        }
        if (ownsDatabase) runCatching { database.close() }
    }

    private suspend fun reconcileVaultPresence() {
        try {
            mutex.withLock {
                if (vmk != null) return@withLock
                val meta = vaultMetaDao.get()
                metaCache = meta
                if (meta != null) {
                    markVaultPresent()
                    if (_status.value == VaultStatus.SETUP_REQUIRED) {
                        _status.value = VaultStatus.LOCKED
                    }
                } else {
                    forgetVaultPresence()
                    if (_status.value == VaultStatus.LOCKED) {
                        _status.value = VaultStatus.SETUP_REQUIRED
                    }
                }
            }
        } catch (e: Exception) {
            // Keep the marker-derived initial status; unlock() reports CORRUPT_VAULT if the DB
            // really is unreadable.
        }
    }

    private suspend fun clearInvalidatedBiometricState() {
        try {
            mutex.withLock { setBiometricEnabledLocked(false) }
        } catch (e: Exception) {
            // Best effort: the UI already fell back to the password path.
        }
    }

    private suspend fun setBiometricEnabledLocked(enabled: Boolean) {
        vaultMetaDao.updateBiometricEnabled(enabled)
        metaCache = metaCache?.copy(biometricEnabled = enabled)
        refreshVaultInfoLocked()
    }

    // ---- crypto / index internals -------------------------------------------

    /**
     * Decrypts every row into the in-memory index. Rows that fail authentication/decode are
     * counted and skipped; they are never surfaced and never crash the load.
     */
    private suspend fun loadIndexLocked(vmkBytes: ByteArray) {
        val dataKey = NoteCrypto.secretKey(vmkBytes)
        itemsById.clear()
        foldersById.clear()
        var corrupt = 0
        for (row in itemDao.getAll()) {
            val item = decodeItemRow(row, dataKey)
            if (item == null) corrupt++ else itemsById[row.id] = item
        }
        for (row in folderDao.getAll()) {
            val folder = decodeFolderRow(row, dataKey)
            if (folder == null) corrupt++ else foldersById[row.id] = folder
        }
        corruptRows = corrupt
        publishIndexLocked()
        refreshVaultInfoLocked()
    }

    private fun decodeItemRow(row: ItemEntity, dataKey: SecretKey): Item? {
        return try {
            val kind = ItemKind.entries.firstOrNull { it.name == row.kind } ?: return null
            val plaintext = NoteCrypto.decrypt(
                dataKey,
                row.ciphertext,
                CryptoAad.item(row.id, kind.name),
            ) ?: return null
            val item = try {
                ItemCodec.decode(plaintext)
            } finally {
                NoteCrypto.wipe(plaintext)
            } ?: return null
            // Structural columns are plaintext: cross-check them against the authenticated
            // payload so a tampered row is treated as corrupt, not trusted.
            if (item.id != row.id || item.kind != kind) return null
            if (item.createdAt != row.createdAt || item.updatedAt != row.updatedAt) return null
            item
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeFolderRow(row: FolderEntity, dataKey: SecretKey): Folder? {
        return try {
            val plaintext = NoteCrypto.decrypt(
                dataKey,
                row.ciphertext,
                folderAad(row.id),
            ) ?: return null
            val folder = try {
                FolderCodec.decode(plaintext)
            } finally {
                NoteCrypto.wipe(plaintext)
            } ?: return null
            if (folder.id != row.id) return null
            if (folder.createdAt != row.createdAt) return null
            folder
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun persistItemLocked(item: Item, vmkBytes: ByteArray) {
        val plaintext = ItemCodec.encode(item)
        val ciphertext = try {
            NoteCrypto.encrypt(
                NoteCrypto.secretKey(vmkBytes),
                plaintext,
                CryptoAad.item(item.id, item.kind.name),
            )
        } finally {
            NoteCrypto.wipe(plaintext)
        }
        itemDao.upsert(
            ItemEntity(
                id = item.id,
                kind = item.kind.name,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                ciphertext = ciphertext,
            ),
        )
    }

    private suspend fun persistFolderLocked(folder: Folder, vmkBytes: ByteArray) {
        val plaintext = FolderCodec.encode(folder)
        val ciphertext = try {
            NoteCrypto.encrypt(NoteCrypto.secretKey(vmkBytes), plaintext, folderAad(folder.id))
        } finally {
            NoteCrypto.wipe(plaintext)
        }
        folderDao.upsert(
            FolderEntity(
                id = folder.id,
                createdAt = folder.createdAt,
                ciphertext = ciphertext,
            ),
        )
    }

    /**
     * Writes the item with bumped `updatedAt`/`version`. Timestamps are strictly increasing
     * so equal-timestamp duplicate detection in backups stays meaningful. A stale folder
     * reference (folder deleted while the editor was open) falls back to the root instead of
     * failing the autosave.
     */
    private suspend fun saveItemLocked(item: Item, vmkBytes: ByteArray): Item {
        val existing = itemsById[item.id]
        val now = clock()
        val folderId = item.folderId?.takeIf { foldersById.containsKey(it) }
        val createdAt = if (item.createdAt > 0L) item.createdAt else existing?.createdAt ?: now
        val updatedAt = maxOf(now, (existing?.updatedAt ?: item.updatedAt) + 1L)
        val version = maxOf(existing?.version ?: -1L, item.version) + 1L
        val saved = item.copy(
            tags = ItemQueries.normaliseTags(item.tags),
            folderId = folderId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
        persistItemLocked(saved, vmkBytes)
        itemsById[saved.id] = saved
        return saved
    }

    /**
     * Dedupe policy: an item is a duplicate only when the same id AND the same updatedAt
     * already exists; any other same-id row is a conflict and is never overwritten. Records
     * are all decoded/authenticated before the first write, and the write is one Room
     * transaction, so an import is all-or-nothing.
     */
    private suspend fun applyBackupContentsLocked(contents: BackupFormat.Contents): BackupImportReport {
        val key = vmk ?: return BackupImportReport(0, 0, 0, 0)
        var imported = 0
        var duplicated = 0
        var skipped = 0
        var foldersImported = 0

        val seenItemIds = HashSet<String>()
        val itemRows = ArrayList<ItemEntity>()
        val itemsToAdd = ArrayList<Item>()
        for (item in contents.items) {
            val existing = itemsById[item.id]
            if (existing != null) {
                if (existing.updatedAt == item.updatedAt) duplicated++ else skipped++
                continue
            }
            if (!seenItemIds.add(item.id)) {
                duplicated++
                continue
            }
            val row = try {
                encryptItemRow(item, key)
            } catch (e: Exception) {
                null
            }
            if (row == null) {
                skipped++
                continue
            }
            itemRows.add(row)
            itemsToAdd.add(item)
            imported++
        }

        val seenFolderIds = HashSet<String>()
        val folderRows = ArrayList<FolderEntity>()
        val foldersToAdd = ArrayList<Folder>()
        for (folder in contents.folders) {
            if (foldersById.containsKey(folder.id) || !seenFolderIds.add(folder.id)) {
                duplicated++
                continue
            }
            val row = try {
                encryptFolderRow(folder, key)
            } catch (e: Exception) {
                null
            }
            if (row == null) {
                skipped++
                continue
            }
            folderRows.add(row)
            foldersToAdd.add(folder)
            foldersImported++
        }

        if (itemRows.isNotEmpty() || folderRows.isNotEmpty()) {
            database.withTransaction {
                if (itemRows.isNotEmpty()) itemDao.upsertAll(itemRows)
                if (folderRows.isNotEmpty()) folderDao.upsertAll(folderRows)
            }
        }
        itemsToAdd.forEach { itemsById[it.id] = it }
        foldersToAdd.forEach { foldersById[it.id] = it }
        afterItemMutationLocked()
        return BackupImportReport(
            imported = imported,
            duplicated = duplicated,
            skipped = skipped,
            foldersImported = foldersImported,
        )
    }

    private suspend fun encryptItemRow(item: Item, vmkBytes: ByteArray): ItemEntity {
        val plaintext = ItemCodec.encode(item)
        val ciphertext = try {
            NoteCrypto.encrypt(
                NoteCrypto.secretKey(vmkBytes),
                plaintext,
                CryptoAad.item(item.id, item.kind.name),
            )
        } finally {
            NoteCrypto.wipe(plaintext)
        }
        return ItemEntity(
            id = item.id,
            kind = item.kind.name,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            ciphertext = ciphertext,
        )
    }

    private suspend fun encryptFolderRow(folder: Folder, vmkBytes: ByteArray): FolderEntity {
        val plaintext = FolderCodec.encode(folder)
        val ciphertext = try {
            NoteCrypto.encrypt(NoteCrypto.secretKey(vmkBytes), plaintext, folderAad(folder.id))
        } finally {
            NoteCrypto.wipe(plaintext)
        }
        return FolderEntity(id = folder.id, createdAt = folder.createdAt, ciphertext = ciphertext)
    }

    /**
     * Resolves a backup to plaintext with, in order: the current in-memory VMK (same-vault
     * backup, no credential needed), the backup's extra password envelope, then the vault
     * password or recovery phrase embedded in a vault-key backup.
     */
    private fun openBackupLocked(
        envelope: BackupEnvelope,
        localVmk: ByteArray,
        password: CharArray?,
    ): BackupFormat.Contents? {
        envelope.decrypt(localVmk)?.let { return it }
        val credential = password ?: return null
        val candidateVmk = when (envelope.mode) {
            BackupFormat.MODE_EXTRA_PASSWORD -> {
                val keyEnvelope = envelope.envelopeOf(EnvelopeType.PASSWORD) ?: return null
                unwrapWithPassword(keyEnvelope, credential, backupAad())
            }

            BackupFormat.MODE_VAULT_KEY -> {
                val words = credential.concatToString().trim().split(WHITESPACE)
                if (words.size == Bip39Recovery.WORD_COUNT && Bip39Recovery.validatePhrase(words)) {
                    val keyEnvelope = envelope.envelopeOf(EnvelopeType.RECOVERY) ?: return null
                    unwrapWithPhrase(keyEnvelope, words)
                } else {
                    val keyEnvelope = envelope.envelopeOf(EnvelopeType.PASSWORD) ?: return null
                    unwrapWithPassword(keyEnvelope, credential, vmkWrapAad())
                }
            }

            else -> null
        } ?: return null
        return try {
            envelope.decrypt(candidateVmk)
        } finally {
            NoteCrypto.wipe(candidateVmk)
        }
    }

    private fun buildBackupPasswordEnvelope(password: CharArray, vmkBytes: ByteArray): KeyEnvelope {
        val iterations = effectiveIterations()
        val salt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
        return KeyEnvelope(
            type = EnvelopeType.PASSWORD,
            iterations = iterations,
            salt = salt,
            wrappedVmk = NoteCrypto.wrapVmk(
                vmkBytes,
                NoteCrypto.deriveKek(password, salt, iterations),
                backupAad(),
            ),
        )
    }

    private fun unwrapWithPassword(
        envelope: KeyEnvelope,
        password: CharArray,
        aad: ByteArray,
    ): ByteArray? {
        if (!isIterationCountAcceptable(envelope.iterations)) return null
        return try {
            NoteCrypto.unwrapVmk(
                envelope.wrappedVmk,
                NoteCrypto.deriveKek(password, envelope.salt, envelope.iterations),
                aad,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun unwrapWithPhrase(envelope: KeyEnvelope, phrase: List<String>): ByteArray? {
        if (!isIterationCountAcceptable(envelope.iterations)) return null
        return try {
            NoteCrypto.unwrapVmk(
                envelope.wrappedVmk,
                NoteCrypto.deriveKekFromPhrase(phrase, envelope.salt, envelope.iterations),
                CryptoAad.RECOVERY_WRAP.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun backupAad(): ByteArray = CryptoAad.BACKUP.toByteArray(Charsets.UTF_8)

    private fun vmkWrapAad(): ByteArray = CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8)

    /** Folder envelopes use the same AAD domain as items with the pseudo-kind `FOLDER`. */
    private fun folderAad(folderId: String): ByteArray = CryptoAad.item(folderId, FOLDER_AAD_KIND)

    // ---- index / state publication ------------------------------------------

    private fun publishIndexLocked() {
        _items.value = itemsById.values.sortedWith(
            compareByDescending<Item> { it.updatedAt }.thenBy { it.id },
        )
        _folders.value = foldersById.values.sortedWith(
            compareBy<Folder> { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id },
        )
    }

    private fun afterItemMutationLocked() {
        publishIndexLocked()
        refreshVaultInfoLocked()
    }

    private fun refreshVaultInfoLocked() {
        val meta = metaCache
        val key = vmk
        _vaultInfo.value = if (meta == null || key == null) {
            null
        } else {
            VaultInfo(
                createdAt = meta.createdAt,
                itemCount = itemsById.size,
                folderCount = foldersById.size,
                kdfIterations = envelopeIterations(meta.passwordEnvelope) ?: effectiveIterations(),
                biometricUnlockEnabled = meta.biometricEnabled,
                schemaVersion = meta.schemaVersion,
            )
        }
    }

    private fun envelopeIterations(envelopeBytes: ByteArray): Int? = try {
        KeyEnvelope.fromBytes(envelopeBytes)?.iterations
    } catch (e: Exception) {
        null
    }

    private fun effectiveIterations(): Int =
        kdfIterations.coerceIn(NoteCrypto.MIN_ACCEPTED_ITERATIONS, KeyEnvelope.MAX_ITERATIONS)

    private fun isIterationCountAcceptable(iterations: Int): Boolean =
        iterations in NoteCrypto.MIN_ACCEPTED_ITERATIONS..KeyEnvelope.MAX_ITERATIONS

    private fun requireUnlocked(): ByteArray =
        vmk ?: throw IllegalStateException("Vault is locked")

    // ---- VMK / marker handling ----------------------------------------------

    /** Takes ownership of [bytes] as the live VMK, zeroizing any previous one. */
    private fun adoptVmk(bytes: ByteArray) {
        wipeVmkLocked()
        vmk = bytes
    }

    private fun wipeVmkLocked() {
        val previous = vmk ?: return
        vmk = null
        try {
            NoteCrypto.wipe(previous)
        } catch (t: Throwable) {
            previous.fill(0)
        }
    }

    private fun wipePassword(password: CharArray?) {
        if (password == null) return
        try {
            NoteCrypto.wipe(password)
        } catch (t: Throwable) {
            password.fill('\u0000')
        }
    }

    private fun markVaultPresent() {
        if (presenceFile.exists()) return
        try {
            presenceFile.parentFile?.mkdirs()
            presenceFile.writeText(PRESENCE_MARKER)
        } catch (e: Exception) {
            // Non-secret convenience marker: absence only costs a status refresh on next start.
        }
    }

    private fun forgetVaultPresence() {
        try {
            presenceFile.delete()
        } catch (e: Exception) {
            // Non-secret marker; deletion failure is corrected by the next presence check.
        }
    }

    // ---- auto-lock -----------------------------------------------------------

    private fun armAutoLock() {
        lastInteractionAt = clock()
        autoLockJob?.cancel()
        autoLockJob = null
        if (!allowAutoLock) return
        if (_status.value != VaultStatus.UNLOCKED) return
        if (_autoLockMinutes.value <= 0) return
        autoLockJob = scope.launch {
            while (isActive) {
                delay(AUTO_LOCK_POLL_INTERVAL_MS)
                if (evaluateAutoLock()) return@launch
            }
        }
    }

    private companion object {
        const val PRESENCE_FILE_NAME = "notezero.vault.presence"
        const val PRESENCE_MARKER = "1"
        const val FOLDER_AAD_KIND = "FOLDER"
        const val AUTO_LOCK_POLL_INTERVAL_MS = 10_000L
        val WHITESPACE = Regex("\\s+")
    }
}
