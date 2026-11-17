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
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.ReadingState
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
import kotlinx.coroutines.flow.update
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
 *  - Locked notes/folders are a **visibility gate**, not a second encryption layer: the
 *    `locked` flag is content inside the envelope (ItemCodec v3 / FolderCodec v2) and the
 *    session-scoped [verifiedLockedIds] set is memory-only, cleared on lock/background/
 *    close. Locking never touches the VMK or the AAD.
 *  - No log line in this file contains titles, bodies, tags, passwords or keys.
 *  - Every suspend entry point dispatches to [ioDispatcher] internally (main-safe).
 *
 * Persistence layout: item/folder rows carry id + kind/timestamps + ciphertext only; the
 * singleton `vault_meta` row carries key envelopes + non-secret metadata. Attachment
 * plaintext lives only in app-private blob files (`filesDir/attachments/<id>.nzb`: magic
 * `NZAT` + version + AES-GCM under the vault VMK, AAD-bound to note+attachment id — see
 * docs/ATTACHMENTS.md). See docs/DATA-MODEL.md.
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
 * @param attachmentsDir optional injected attachment blob directory (tests isolate it);
 *   defaults to `filesDir/attachments`.
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
    attachmentsDir: File? = null,
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

    /** App-private directory holding encrypted attachment blobs (never plaintext). */
    internal val attachmentsDirectory: File =
        attachmentsDir ?: File(appContext.filesDir, ATTACHMENTS_DIR_NAME)

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

    /** Number of attachment blob reads that failed AEAD/SHA-256/size verification (diagnostics). */
    @Volatile
    private var attachmentFailures = 0

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

    /**
     * Guards the lock-re-authentication rate limiter ([verificationFailures],
     * [verificationBlockedUntil]). Small and independent of [mutex]: the slow PBKDF2 step of
     * [verifyMasterPassword] must not hold the repository mutex, and the limiter is state
     * about attempts, not about vault content.
     */
    private val verificationLock = Any()

    /** Consecutive failed [verifyMasterPassword] attempts in this session (memory only). */
    private var verificationFailures: Int = 0

    /** Clock deadline before which [verifyMasterPassword] refuses to run the KDF. */
    private var verificationBlockedUntil: Long = 0L

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

    /**
     * Session-scoped lock verifications (notes + folders). Memory only: never written to
     * Room, DataStore or a file, so a restart always re-masks. Cleared by [lock], [close],
     * a background lock that actually applies, and a session (re)start.
     */
    private val _verifiedLockedIds = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedLockedIds: StateFlow<Set<String>> = _verifiedLockedIds.asStateFlow()

    /** Number of rows skipped as corrupt/tampered during the last index load (diagnostics only). */
    internal val corruptRowCount: Int get() = corruptRows

    /** Number of attachment reads that failed integrity verification (diagnostics only). */
    internal val attachmentFailureCount: Int get() = attachmentFailures

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
                    clearLockVerificationsInternal()
                    resetVerificationRateLimit()
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
                    clearLockVerificationsInternal()
                    resetVerificationRateLimit()
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
                // Clear before publishing UNLOCKED: markLockVerified() is a no-op while the
                // vault is locked, so no verification can survive into the new session.
                clearLockVerificationsInternal()
                resetVerificationRateLimit()
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
            // Wipe first, then clear: markLockVerified() requires a live VMK, so a mark that
            // interleaves with this lock is either removed by the clear or rejected outright.
            // A fresh unlock starts from a clean rate-limit state (the password was just proven).
            wipeVmkLocked()
            clearLockVerificationsInternal()
            resetVerificationRateLimit()
            itemsById.clear()
            foldersById.clear()
            corruptRows = 0
            attachmentFailures = 0
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
                    // Every session boundary starts unverified and with a clean re-auth
                    // limiter, exactly like unlock()/setupVault(): reset may be invoked while
                    // already unlocked, so a stale verification or rate-limit block must never
                    // cross into the fresh session. Clear before publishing UNLOCKED because
                    // markLockVerified() is a no-op while the vault is not unlocked.
                    clearLockVerificationsInternal()
                    resetVerificationRateLimit()
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

    override fun isBiometricUnlockEnrolled(): Boolean = try {
        biometrics.isEnrolled()
    } catch (e: Exception) {
        false
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
                    // Tearing down a live session re-masks every verified id and clears the
                    // re-auth limiter; a stale set must not outlive the VMK it belongs to.
                    clearLockVerificationsInternal()
                    resetVerificationRateLimit()
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
                    // A biometric unlock is a session boundary: start unverified with a clean
                    // limiter, exactly like unlock()/setupVault() (decision #15).
                    clearLockVerificationsInternal()
                    resetVerificationRateLimit()
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
        // The background lock applies now: re-mask locked content immediately (before the
        // async lock() completes) so the UI never shows a verified row for a vault that is
        // on its way to LOCKED. A deferred (picker) background deliberately does NOT clear.
        clearLockVerificationsInternal()
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
        if (lockNow && _status.value == VaultStatus.UNLOCKED) {
            clearLockVerificationsInternal()
            requestLockNow()
        }
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

    // ---- locked notes & folders (visibility gate; decision #15) ---------------

    override suspend fun setItemLocked(id: String, locked: Boolean): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk ?: return@withLock false
                val item = itemsById[id] ?: return@withLock false
                if (item.locked == locked) return@withLock true
                // Only the payload flag changes; same VMK, same AAD, no key/ciphertext rotation.
                saveItemLocked(item.copy(locked = locked), key, preserveLock = false)
                afterItemMutationLocked()
                // A lock-state change re-arms this id: newly locked content must be masked, and
                // an unlocked note no longer needs (nor keeps) a verification.
                _verifiedLockedIds.update { it - id }
                true
            }
        }

    override suspend fun saveReadingState(itemId: String, state: ReadingState): Boolean =
        withContext(ioDispatcher) {
            try {
                mutex.withLock {
                    val key = vmk ?: return@withLock false
                    val item = itemsById[itemId] ?: return@withLock false
                    if (item.readingState == state) return@withLock true
                    saveItemLocked(item.copy(readingState = state), key, preserveReadingState = false)
                    afterItemMutationLocked()
                    true
                }
            } catch (e: IllegalArgumentException) {
                // Out-of-bounds state (labels/counts/position) fails closed, never persists.
                false
            }
        }

    override suspend fun savePdfAnnotations(itemId: String, annotations: PdfAnnotations?): Boolean =
        withContext(ioDispatcher) {
            try {
                mutex.withLock {
                    val key = vmk ?: return@withLock false
                    val item = itemsById[itemId] ?: return@withLock false
                    val stored = annotations?.takeUnless { it.isEmpty }
                    if (item.pdfAnnotations == stored) return@withLock true
                    saveItemLocked(
                        item.copy(pdfAnnotations = stored),
                        key,
                        preservePdfAnnotations = false,
                    )
                    afterItemMutationLocked()
                    true
                }
            } catch (e: IllegalArgumentException) {
                // Out-of-bounds annotations fail closed, never persist.
                false
            }
        }

    override suspend fun setFolderLocked(id: String, locked: Boolean): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk ?: return@withLock false
                val folder = foldersById[id] ?: return@withLock false
                if (folder.locked == locked) return@withLock true
                val updated = folder.copy(locked = locked)
                persistFolderLocked(updated, key)
                foldersById[id] = updated
                publishIndexLocked()
                // Descendants are untouched (hiding them is a UI concern) but the folder id
                // itself loses any verification.
                _verifiedLockedIds.update { it - id }
                true
            }
        }

    /**
     * Verifies the master password against the stored password envelope without changing the
     * session. The expensive KDF runs outside [mutex]; only the constant-time comparison with
     * the live VMK takes the repository mutex, so a concurrent [lock] cannot halfway wipe the
     * key under the comparison (a locked vault then yields false without counting a failure).
     */
    override suspend fun verifyMasterPassword(password: CharArray): Boolean =
        withContext(ioDispatcher) {
            try {
                if (_status.value != VaultStatus.UNLOCKED) return@withContext false
                // Blank input is not an attempt: false, no state change, array still wiped.
                if (isBlankPassword(password)) return@withContext false
                if (!verificationAttemptAllowedNow()) return@withContext false
                val meta = try {
                    vaultMetaDao.get()
                } catch (e: Exception) {
                    null
                } ?: return@withContext recordFailedVerification()
                val envelope = KeyEnvelope.fromBytes(meta.passwordEnvelope)
                    ?: return@withContext recordFailedVerification()
                if (envelope.type != EnvelopeType.PASSWORD) return@withContext recordFailedVerification()
                if (!isIterationCountAcceptable(envelope.iterations)) {
                    return@withContext recordFailedVerification()
                }
                val candidate = try {
                    NoteCrypto.unwrapVmk(
                        envelope.wrappedVmk,
                        NoteCrypto.deriveKek(password, envelope.salt, envelope.iterations),
                        CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
                    )
                } catch (e: Exception) {
                    null
                }
                if (candidate == null || candidate.size != NoteCrypto.VMK_LENGTH_BYTES) {
                    if (candidate != null) NoteCrypto.wipe(candidate)
                    return@withContext recordFailedVerification()
                }
                val matches: Boolean? = try {
                    mutex.withLock {
                        val live = vmk ?: return@withLock null
                        NoteCrypto.constantTimeEquals(candidate, live)
                    }
                } finally {
                    NoteCrypto.wipe(candidate)
                }
                // null = the vault locked concurrently: not a credential verdict, no failure.
                if (matches == null) return@withContext false
                if (!matches) return@withContext recordFailedVerification()
                synchronized(verificationLock) {
                    verificationFailures = 0
                    verificationBlockedUntil = 0L
                }
                true
            } finally {
                wipePassword(password)
            }
        }

    override fun markLockVerified(id: String) {
        if (id.isBlank()) return
        // Never create a verification outside a live unlocked session: it would survive into
        // the next unlock and reveal locked content without re-authentication. The VMK check
        // closes the window in which a concurrent lock() has wiped the key (or close() has
        // shut the session down) but has not published LOCKED yet.
        synchronized(verificationLock) {
            if (_status.value != VaultStatus.UNLOCKED || vmk == null) return
            _verifiedLockedIds.update { it + id }
        }
    }

    override fun clearLockVerifications() {
        clearLockVerificationsInternal()
    }

    private fun clearLockVerificationsInternal() {
        synchronized(verificationLock) {
            _verifiedLockedIds.update { if (it.isEmpty()) it else emptySet() }
        }
    }

    private fun resetVerificationRateLimit() {
        synchronized(verificationLock) {
            verificationFailures = 0
            verificationBlockedUntil = 0L
        }
    }

    /** False while the growing delay / cooldown from a previous failure is still running. */
    private fun verificationAttemptAllowedNow(): Boolean =
        synchronized(verificationLock) { clock() >= verificationBlockedUntil }

    /** Arms the next delay and returns false, so callers can `return recordFailedVerification()`. */
    private fun recordFailedVerification(): Boolean {
        synchronized(verificationLock) {
            verificationFailures += 1
            verificationBlockedUntil =
                clock() + LockVerificationPolicy.delayAfterFailure(verificationFailures)
        }
        return false
    }

    /** Empty / whitespace-only / NUL-filled input: rejected without counting as an attempt. */
    private fun isBlankPassword(password: CharArray): Boolean =
        password.isEmpty() || password.all { it.isWhitespace() || it == '\u0000' }

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
            val removed = itemsById.remove(id) ?: return@withLock
            itemDao.deleteById(id)
            _verifiedLockedIds.update { it - id }
            afterItemMutationLocked()
            // The row is gone: its attachment refs can never be surfaced again, so the blobs
            // are deleted now (best effort; leftovers are removed by the next orphan sweep).
            removed.attachments.forEach { deleteBlobLocked(it.id) }
        }
    }

    override suspend fun emptyTrash() = withContext(ioDispatcher) {
        mutex.withLock {
            requireUnlocked()
            val trashed = itemsById.values.filter { it.trashed }
            if (trashed.isEmpty()) return@withLock
            itemDao.deleteByIds(trashed.map { it.id })
            trashed.forEach { itemsById.remove(it.id) }
            _verifiedLockedIds.update { it - trashed.map { item -> item.id } }
            afterItemMutationLocked()
            trashed.flatMap { it.attachments }.forEach { deleteBlobLocked(it.id) }
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
            _verifiedLockedIds.update { it - id }
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
                    val attachmentBlobs = collectAttachmentBlobsLocked()
                        ?: return@withLock false // over the format's OOM guard: fail, never drop blobs
                    val contents = BackupFormat.Contents(
                        exportedAt = clock(),
                        items = itemsById.values.toList(),
                        folders = foldersById.values.toList(),
                        attachments = attachmentBlobs,
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
                } catch (oom: OutOfMemoryError) {
                    // Export peak heap is several times the 64 MiB stream cap (payload +
                    // sealed copy + attachment blobs). Fail cleanly with `false` and no partial
                    // file instead of letting the Error reach the UI and kill the process
                    // (audit finding F9).
                    false
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
                    val opened = openBackupLocked(envelope, key, password) ?: return@withLock null
                    try {
                        applyBackupContentsLocked(opened.contents, opened.sourceVmk)
                    } finally {
                        if (opened.ownsSourceVmk) NoteCrypto.wipe(opened.sourceVmk)
                    }
                } catch (oom: OutOfMemoryError) {
                    // Structural limits bound allocations, but the decoded record graph can
                    // still exhaust a small heap. Return null (invalid/unreadable file) instead
                    // of crashing the app (audit finding F9).
                    null
                } catch (e: Exception) {
                    null
                } finally {
                    wipePassword(password)
                    lastInteractionAt = clock()
                }
            }
        }

    // ---- encrypted attachments ----------------------------------------------

    override suspend fun importAttachment(
        noteId: String,
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): AttachmentImport = withContext(ioDispatcher) {
        mutex.withLock {
            val key = vmk ?: return@withLock AttachmentImport.Failure(AttachmentError.LOCKED)
            val item = itemsById[noteId]
                ?: return@withLock AttachmentImport.Failure(AttachmentError.NOTE_NOT_FOUND)
            if (item.attachments.size >= AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE) {
                return@withLock AttachmentImport.Failure(AttachmentError.QUOTA_EXCEEDED)
            }
            // Enforce the per-file limit WHILE reading: a hostile ContentResolver stream can
            // never force an allocation past MAX_ATTACHMENT_BYTES (readAtMost returns null
            // before buffering the byte that would exceed the limit).
            val plaintext = try {
                AttachmentBlob.readAtMost(source, AttachmentLimits.MAX_ATTACHMENT_BYTES)
            } catch (e: Exception) {
                return@withLock AttachmentImport.Failure(AttachmentError.IO)
            } ?: return@withLock AttachmentImport.Failure(AttachmentError.TOO_LARGE)
            try {
                if (referencedAttachmentBytesLocked() + plaintext.size > AttachmentLimits.MAX_TOTAL_BYTES) {
                    return@withLock AttachmentImport.Failure(AttachmentError.QUOTA_EXCEEDED)
                }
                val id = UUID.randomUUID().toString()
                val ref = AttachmentRef(
                    id = id,
                    displayName = boundedLabel(displayName, ItemCodec.MAX_ATTACHMENT_NAME_BYTES, DEFAULT_ATTACHMENT_NAME),
                    mimeType = boundedLabel(mimeType, ItemCodec.MAX_ATTACHMENT_MIME_BYTES, DEFAULT_ATTACHMENT_MIME),
                    sizeBytes = plaintext.size.toLong(),
                    createdAt = clock(),
                    sha256 = AttachmentBlob.sha256Hex(plaintext),
                )
                // Blob first (atomic temp+rename), then the ref. A failure between the two is
                // rolled back, so a failed import leaves neither an orphan blob nor a ref.
                val blob = AttachmentBlob.encode(
                    plaintext,
                    NoteCrypto.secretKey(key),
                    CryptoAad.attachment(noteId, id),
                )
                if (!writeBlobAtomicallyLocked(id, blob)) {
                    return@withLock AttachmentImport.Failure(AttachmentError.IO)
                }
                try {
                    saveItemLocked(
                        item.copy(attachments = item.attachments + ref),
                        key,
                        preserveAttachments = false,
                    )
                } catch (e: Exception) {
                    deleteBlobLocked(id)
                    return@withLock AttachmentImport.Failure(AttachmentError.IO)
                }
                afterItemMutationLocked()
                AttachmentImport.Success(ref)
            } catch (e: Exception) {
                AttachmentImport.Failure(AttachmentError.IO)
            } finally {
                NoteCrypto.wipe(plaintext)
            }
        }
    }

    override suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray? =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk ?: return@withLock null
                attachmentBytesLocked(noteId, attachmentId, key)
            }
        }

    override suspend fun exportAttachment(
        noteId: String,
        attachmentId: String,
        out: OutputStream,
    ): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val key = vmk ?: return@withLock false
            val plaintext = attachmentBytesLocked(noteId, attachmentId, key) ?: return@withLock false
            try {
                out.write(plaintext)
                out.flush()
                true
            } catch (e: Exception) {
                false
            } finally {
                // Explicit user-requested export is the only plaintext write path; wipe the
                // repository's copy once the caller's stream has been served.
                NoteCrypto.wipe(plaintext)
            }
        }
    }

    override suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                val key = vmk ?: return@withLock false
                val item = itemsById[noteId] ?: return@withLock false
                if (item.attachments.none { it.id == attachmentId }) return@withLock false
                val remaining = item.attachments.filterNot { it.id == attachmentId }
                try {
                    saveItemLocked(
                        item.copy(attachments = remaining),
                        key,
                        preserveAttachments = false,
                    )
                } catch (e: Exception) {
                    // Ref still stored: keep the blob too, otherwise the ref would point at
                    // nothing. Report failure; the caller can retry.
                    return@withLock false
                }
                afterItemMutationLocked()
                deleteBlobLocked(attachmentId)
                true
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
            attachmentFailures = 0
        }
        clearLockVerificationsInternal()
        resetVerificationRateLimit()
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
     * counted and skipped; they are never surfaced and never crash the load. On a fully
     * clean load, orphan attachment blobs left by a crash are swept (see
     * [cleanupOrphanBlobsLocked]).
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
        attachmentFailures = 0
        cleanupOrphanBlobsLocked()
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
     *
     * Attachment refs are repository-owned metadata ([preserveAttachments] = true for the
     * public [saveItem]): the stored refs win over whatever a (possibly stale) editor copy
     * carries, so autosave can never add or drop an attachment. The attachment import /
     * delete paths pass false and are the only code allowed to mutate the list.
     *
     * The same rule protects [Item.locked] ([preserveLock] = true): an editor autosave with a
     * stale `locked=false` copy cannot silently unlock a note. Only [setItemLocked] passes
     * false, so the lock flag can only be changed through the explicit lock API.
     */
    private suspend fun saveItemLocked(
        item: Item,
        vmkBytes: ByteArray,
        preserveAttachments: Boolean = true,
        preserveLock: Boolean = true,
        preserveReadingState: Boolean = true,
        preservePdfAnnotations: Boolean = true,
    ): Item {
        val existing = itemsById[item.id]
        val now = clock()
        val folderId = item.folderId?.takeIf { foldersById.containsKey(it) }
        val createdAt = if (item.createdAt > 0L) item.createdAt else existing?.createdAt ?: now
        val updatedAt = maxOf(now, (existing?.updatedAt ?: item.updatedAt) + 1L)
        val version = maxOf(existing?.version ?: -1L, item.version) + 1L
        val locked = if (preserveLock) existing?.locked ?: item.locked else item.locked
        val readingState = if (preserveReadingState) {
            existing?.readingState ?: item.readingState
        } else {
            item.readingState
        }
        val pdfAnnotations = if (preservePdfAnnotations) {
            existing?.pdfAnnotations ?: item.pdfAnnotations
        } else {
            item.pdfAnnotations
        }
        val attachments = when {
            !preserveAttachments -> item.attachments
            existing != null -> existing.attachments
            else -> item.attachments
        }.let { refs ->
            if (refs.size <= AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE) refs
            else refs.take(AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE)
        }
        val saved = item.copy(
            tags = ItemQueries.normaliseTags(item.tags),
            folderId = folderId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            attachments = attachments,
            locked = locked,
            readingState = readingState,
            pdfAnnotations = pdfAnnotations,
        )
        persistItemLocked(saved, vmkBytes)
        itemsById[saved.id] = saved
        return saved
    }

    // ---- attachment internals ------------------------------------------------

    /**
     * Decrypts + verifies one attachment blob (AEAD, then exact size and SHA-256 against the
     * ref). Used by read/export paths under the repository mutex. Returns null on any
     * failure and counts it; never returns unauthenticated bytes.
     */
    private fun attachmentBytesLocked(
        noteId: String,
        attachmentId: String,
        vmkBytes: ByteArray,
    ): ByteArray? {
        if (!isSafeAttachmentId(attachmentId)) return null
        val item = itemsById[noteId] ?: return null
        val ref = item.attachments.firstOrNull { it.id == attachmentId } ?: return null
        val file = blobFile(attachmentId)
        val ciphertext = try {
            if (file.isFile) file.readBytes() else null
        } catch (e: Exception) {
            null
        }
        if (ciphertext == null) {
            attachmentFailures += 1
            return null
        }
        var plaintext: ByteArray? = null
        try {
            plaintext = AttachmentBlob.decode(
                ciphertext,
                NoteCrypto.secretKey(vmkBytes),
                CryptoAad.attachment(noteId, attachmentId),
            )
        } catch (e: Exception) {
            plaintext = null
        } finally {
            NoteCrypto.wipe(ciphertext)
        }
        val verified = plaintext != null &&
            plaintext.size.toLong() == ref.sizeBytes &&
            AttachmentBlob.sha256Hex(plaintext) == ref.sha256
        if (!verified) {
            attachmentFailures += 1
            if (plaintext != null) NoteCrypto.wipe(plaintext)
            return null
        }
        return plaintext
    }

    /** Sum of the plaintext sizes of every referenced attachment (quota accounting). */
    private fun referencedAttachmentBytesLocked(): Long {
        var total = 0L
        for (item in itemsById.values) {
            for (ref in item.attachments) {
                if (ref.sizeBytes > 0L) total += ref.sizeBytes
                if (total > AttachmentLimits.MAX_TOTAL_BYTES) return total
            }
        }
        return total
    }

    /**
     * Reads every referenced blob that is present and readable into memory for a backup.
     *
     * A blob that is missing, empty-sized or unreadable is left out (the ref still travels
     * in the item payload; import counts that attachment as skipped — logged nowhere). But
     * if the included attachment bytes alone would exceed the backup payload cap, this
     * returns null so the export fails cleanly: a backup must never silently drop
     * attachments to fit (docs/BACKUP-FORMAT.md §6).
     */
    private fun collectAttachmentBlobsLocked(): List<BackupFormat.AttachmentBlobRecord>? {
        val blobs = ArrayList<BackupFormat.AttachmentBlobRecord>()
        var totalBytes = 0L
        for (item in itemsById.values) {
            for (ref in item.attachments) {
                if (!isSafeAttachmentId(ref.id)) continue
                val file = blobFile(ref.id)
                if (!file.isFile) continue
                val length = file.length()
                if (length < AttachmentBlob.MIN_BLOB_BYTES || length > AttachmentBlob.MAX_BLOB_BYTES) continue
                totalBytes += length
                if (totalBytes > BackupFormat.MAX_PAYLOAD_BYTES) return null
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    continue // unreadable blob: ref travels alone, import counts it skipped
                }
                blobs.add(BackupFormat.AttachmentBlobRecord(item.id, ref.id, bytes))
            }
        }
        return blobs
    }

    private fun blobFile(attachmentId: String): File =
        File(attachmentsDirectory, "$attachmentId$BLOB_FILE_SUFFIX")

    /**
     * Attachment ids are repository-generated UUIDs. Refusing arbitrary strings before they
     * reach a file path stops a crafted/imported ref from escaping `attachments/`
     * (path traversal) — e.g. `../notezero-vault.db`.
     */
    private fun isSafeAttachmentId(id: String): Boolean =
        id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Writes a fully-encrypted blob via a temp file + rename, so a crash never leaves a partial `.nzb`. */
    private fun writeBlobAtomicallyLocked(attachmentId: String, blob: ByteArray): Boolean {
        val dir = attachmentsDirectory
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val temp = File(dir, "$TEMP_BLOB_PREFIX${UUID.randomUUID()}")
        return try {
            temp.writeBytes(blob)
            val target = blobFile(attachmentId)
            if (temp.renameTo(target)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    /** Writes a re-encrypted blob to a temp file (backup restore); renamed only after the DB commit. */
    private fun writeTempBlobLocked(blob: ByteArray): File? {
        val dir = attachmentsDirectory
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        val temp = File(dir, "$TEMP_BLOB_PREFIX${UUID.randomUUID()}")
        return try {
            temp.writeBytes(blob)
            temp
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    private fun deleteBlobLocked(attachmentId: String): Boolean {
        if (!isSafeAttachmentId(attachmentId)) return false
        return try {
            val file = blobFile(attachmentId)
            !file.exists() || file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Startup/after-unlock orphan sweep: deletes blob files whose attachment id is not
     * referenced by any decrypted item, plus temp files from an interrupted atomic import.
     *
     * Fail-safe rule: it never runs when [corruptRows] > 0, because a skipped (corrupt)
     * item row may reference a blob that would otherwise look orphaned — and silently
     * deleting a referenced blob would destroy user data. A blob left behind by a crash
     * is therefore only collected once the vault loads fully, which is exactly the
     * documented rule in docs/ATTACHMENTS.md.
     */
    private fun cleanupOrphanBlobsLocked() {
        if (corruptRows > 0) return
        val dir = attachmentsDirectory
        if (!dir.isDirectory) return
        val referenced = HashSet<String>()
        for (item in itemsById.values) {
            for (ref in item.attachments) {
                if (isSafeAttachmentId(ref.id)) referenced.add(ref.id)
            }
        }
        val files = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return
        for (file in files) {
            try {
                val name = file.name
                when {
                    name.startsWith(TEMP_BLOB_PREFIX) -> file.delete()
                    name.endsWith(BLOB_FILE_SUFFIX) -> {
                        val id = name.removeSuffix(BLOB_FILE_SUFFIX)
                        if (id !in referenced) file.delete()
                    }
                }
            } catch (e: Exception) {
                // Best effort only: a leftover blob is harmless ciphertext.
            }
        }
    }

    /** Trims/shortens a user-visible label to the UTF-8 byte budget of the item payload. */
    private fun boundedLabel(value: String, maxBytes: Int, fallback: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return fallback
        val capped = if (trimmed.length > LABEL_CHAR_CAP) trimmed.substring(0, LABEL_CHAR_CAP) else trimmed
        if (capped.toByteArray(Charsets.UTF_8).size <= maxBytes) return capped
        var end = capped.length
        while (end > 0 && capped.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return capped.substring(0, end).ifEmpty { fallback }
    }

    /**
     * Dedupe policy: an item is a duplicate only when the same id AND the same updatedAt
     * already exists; any other same-id row is a conflict and is never overwritten. Records
     * are all decoded/authenticated before the first write, and the write is one Room
     * transaction, so an import is all-or-nothing for the accepted rows.
     *
     * v2 attachments: every blob is decrypted (source VMK + attachment AAD), its SHA-256 and
     * size are verified against the local/imported ref, then it is re-encrypted under the
     * local VMK and written to a temp file. Temp files are renamed into place only after the
     * Room transaction commits; a failed import deletes them, so it cannot leave refs
     * pointing at uncorrupt garbage or stray blobs. Missing/invalid blobs are counted as
     * [BackupImportReport.attachmentsSkipped] and never fail the note (see BACKUP-FORMAT.md).
     */
    private suspend fun applyBackupContentsLocked(
        contents: BackupFormat.Contents,
        sourceVmk: ByteArray,
    ): BackupImportReport {
        val key = vmk ?: return BackupImportReport(0, 0, 0, 0)
        var imported = 0
        var duplicated = 0
        var skipped = 0
        var foldersImported = 0
        var attachmentsImported = 0
        var attachmentsSkipped = 0

        /** Item ids that exist locally after this import (imported or duplicate). */
        val restorableItemIds = HashSet<String>()

        val seenItemIds = HashSet<String>()
        val itemRows = ArrayList<ItemEntity>()
        val itemsToAdd = ArrayList<Item>()
        for (item in contents.items) {
            val existing = itemsById[item.id]
            if (existing != null) {
                if (existing.updatedAt == item.updatedAt) {
                    duplicated++
                    restorableItemIds.add(item.id)
                } else {
                    skipped++
                }
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
            restorableItemIds.add(item.id)
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

        val pendingBlobs = ArrayList<Pair<File, File>>()
        try {
            // Index the target items AFTER the classification above: imported items plus the
            // ones that were already local (duplicates). Conflict-skipped items stay local.
            val targetItems = HashMap<String, Item>()
            itemsById.forEach { (id, value) -> targetItems[id] = value }
            itemsToAdd.forEach { targetItems[it.id] = it }

            val recordsByKey = HashMap<String, BackupFormat.AttachmentBlobRecord>()
            for (record in contents.attachments) {
                val recordKey = attachmentRecordKey(record.noteId, record.attachmentId)
                if (recordsByKey.containsKey(recordKey)) {
                    duplicated++
                    continue
                }
                recordsByKey[recordKey] = record
            }

            for (record in recordsByKey.values) {
                if (!isSafeAttachmentId(record.attachmentId)) {
                    attachmentsSkipped++
                    continue
                }
                val target = targetItems[record.noteId]
                if (target == null) {
                    attachmentsSkipped++
                    continue
                }
                val ref = target.attachments.firstOrNull { it.id == record.attachmentId }
                if (ref == null) {
                    attachmentsSkipped++
                    continue
                }
                if (blobFile(record.attachmentId).isFile) {
                    // Dedupe by attachment id: the blob is already present locally.
                    duplicated++
                    continue
                }
                val plaintext = try {
                    AttachmentBlob.decode(
                        record.blob,
                        NoteCrypto.secretKey(sourceVmk),
                        CryptoAad.attachment(record.noteId, record.attachmentId),
                    )
                } catch (e: Exception) {
                    null
                }
                if (plaintext == null) {
                    attachmentsSkipped++
                    continue
                }
                val verified = plaintext.size.toLong() == ref.sizeBytes &&
                    AttachmentBlob.sha256Hex(plaintext) == ref.sha256
                if (!verified) {
                    NoteCrypto.wipe(plaintext)
                    attachmentsSkipped++
                    continue
                }
                val reencrypted = try {
                    AttachmentBlob.encode(
                        plaintext,
                        NoteCrypto.secretKey(key),
                        CryptoAad.attachment(record.noteId, record.attachmentId),
                    )
                } catch (e: Exception) {
                    null
                } finally {
                    NoteCrypto.wipe(plaintext)
                }
                if (reencrypted == null) {
                    attachmentsSkipped++
                    continue
                }
                val temp = writeTempBlobLocked(reencrypted)
                if (temp == null) {
                    attachmentsSkipped++
                    continue
                }
                pendingBlobs.add(temp to blobFile(record.attachmentId))
            }

            // Refs that travel in the item payload but have no blob record in the file
            // (e.g. the source vault could not read that blob): the note is restored, the
            // attachment is counted as skipped. Never fail the note.
            for (item in contents.items) {
                if (item.id !in restorableItemIds) continue
                for (ref in item.attachments) {
                    val recordKey = attachmentRecordKey(item.id, ref.id)
                    if (recordsByKey.containsKey(recordKey)) continue
                    if (isSafeAttachmentId(ref.id) && blobFile(ref.id).isFile) continue
                    attachmentsSkipped++
                }
            }

            if (itemRows.isNotEmpty() || folderRows.isNotEmpty()) {
                database.withTransaction {
                    if (itemRows.isNotEmpty()) itemDao.upsertAll(itemRows)
                    if (folderRows.isNotEmpty()) folderDao.upsertAll(folderRows)
                }
            }
        } catch (t: Throwable) {
            pendingBlobs.forEach { (temp, _) -> temp.delete() }
            throw t
        }

        // DB committed: publish the blobs. A failed rename leaves the ref without a blob
        // (reads return null, counted) but never exposes a partial `.nzb`.
        for ((temp, target) in pendingBlobs) {
            if (temp.renameTo(target)) {
                attachmentsImported++
            } else {
                temp.delete()
                attachmentsSkipped++
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
            attachmentsImported = attachmentsImported,
            attachmentsSkipped = attachmentsSkipped,
        )
    }

    private fun attachmentRecordKey(noteId: String, attachmentId: String): String =
        "$noteId|$attachmentId"

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
     *
     * The returned [OpenedBackup] carries the VMK that opened the payload: v2 attachment
     * blobs are individually sealed under that same source VMK, and [openedBackup] is wiped
     * by the caller when it is not the local VMK.
     */
    private fun openBackupLocked(
        envelope: BackupEnvelope,
        localVmk: ByteArray,
        password: CharArray?,
    ): OpenedBackup? {
        envelope.decrypt(localVmk)?.let { opened ->
            return OpenedBackup(contents = opened, sourceVmk = localVmk, ownsSourceVmk = false)
        }
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
        val contents = try {
            envelope.decrypt(candidateVmk)
        } catch (e: Exception) {
            null
        }
        if (contents == null) {
            NoteCrypto.wipe(candidateVmk)
            return null
        }
        return OpenedBackup(contents = contents, sourceVmk = candidateVmk, ownsSourceVmk = true)
    }

    /** A backup opened with [sourceVmk]; [sourceVmk] is not the local VMK when [ownsSourceVmk]. */
    private class OpenedBackup(
        val contents: BackupFormat.Contents,
        val sourceVmk: ByteArray,
        val ownsSourceVmk: Boolean,
    )

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
        const val ATTACHMENTS_DIR_NAME = "attachments"
        const val BLOB_FILE_SUFFIX = ".nzb"
        const val TEMP_BLOB_PREFIX = ".tmp-"
        const val DEFAULT_ATTACHMENT_NAME = "attachment"
        const val DEFAULT_ATTACHMENT_MIME = "application/octet-stream"
        const val LABEL_CHAR_CAP = 4096
        val WHITESPACE = Regex("\\s+")
    }
}
