package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Regression tests for the external-activity lock deferral (SAF picker): launching a
 * system file picker stops the Activity, which must not be treated as "the user left the
 * vault". Real repository, real crypto and in-memory Room; no sleeps — the lock is driven
 * by the injected clock and awaited through the status flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalActivityLockTest {

    private lateinit var context: Context

    private val databases = mutableListOf<NoteZeroDatabase>()
    private val repositories = mutableListOf<EncryptedNoteRepository>()
    private val preferenceScopes = mutableListOf<CoroutineScope>()

    private var now: Long = 1_700_000_000_000L

    private lateinit var db: NoteZeroDatabase
    private lateinit var prefs: VaultPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoteZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases.add(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferenceScopes.add(scope)
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        prefs = VaultPreferences.createForFile(file, scope)
    }

    @After
    fun tearDown() {
        repositories.forEach { repository -> runCatching { repository.close() } }
        preferenceScopes.forEach { scope -> scope.cancel() }
        databases.forEach { database -> runCatching { database.close() } }
    }

    private fun newRepository(allowAutoLock: Boolean = true): EncryptedNoteRepository =
        EncryptedNoteRepository(
            context = context,
            database = db,
            preferences = prefs,
            clock = { now },
            kdfIterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            allowAutoLock = allowAutoLock,
        ).also { repositories.add(it) }

    private suspend fun EncryptedNoteRepository.awaitLocked() {
        withTimeout(5_000) { status.first { it == VaultStatus.LOCKED } }
    }

    private suspend fun openingRepository(): EncryptedNoteRepository {
        val repository = newRepository()
        repository.setupVault(PASSWORD.toCharArray())
        repository.saveItem(repository.createItem(ItemKind.NOTE).copy(title = "Private note"))
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(1, repository.items.value.size)
        return repository
    }

    // ---- contract: with no external activity, background locks as before ---------

    @Test
    fun backgroundWithoutExternalActivityStillLocksImmediately() = runBlocking {
        val repository = openingRepository()

        repository.onAppBackgrounded()

        repository.awaitLocked()
        assertTrue(repository.items.value.isEmpty())
        assertFalse(repository.hasActiveExternalActivity())
    }

    // ---- contract: background caused by the picker keeps the vault open ----------

    @Test
    fun backgroundDuringExternalActivityKeepsVaultUnlockedAndItemsAvailable() = runBlocking {
        val repository = openingRepository()
        repository.beginExternalActivity()

        repository.onAppBackgrounded()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(1, repository.items.value.size)
        assertTrue(repository.hasActiveExternalActivity())
        assertTrue(repository.isBackgroundLockDeferred())
    }

    @Test
    fun endWhileForegroundKeepsVaultUnlocked() = runBlocking {
        val repository = openingRepository()
        repository.beginExternalActivity()
        repository.onAppBackgrounded()

        // The picker returns: the Activity is back in the foreground before the result is
        // delivered to the launcher callback.
        repository.onAppForegrounded()
        repository.endExternalActivity()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(1, repository.items.value.size)
        assertFalse(repository.hasActiveExternalActivity())
        assertFalse(repository.isBackgroundLockDeferred())
    }

    @Test
    fun endWhileStillBackgroundedLocksImmediately() = runBlocking {
        val repository = openingRepository()
        repository.beginExternalActivity()
        repository.onAppBackgrounded()
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)

        // Result delivered before the app came back (timeout / process oddity): lock now
        // rather than waiting for the idle timer.
        repository.endExternalActivity()

        repository.awaitLocked()
        assertTrue(repository.items.value.isEmpty())
        assertFalse(repository.hasActiveExternalActivity())
    }

    @Test
    fun idleTimeoutStillLocksWhileExternalActivityIsActive() = runBlocking {
        val repository = openingRepository()
        repository.setAutoLockMinutes(1)
        repository.beginExternalActivity()
        repository.onAppBackgrounded()
        assertTrue(repository.isBackgroundLockDeferred())

        // The picker is left open past the idle timeout: no permanent suppression.
        now += 61_000
        assertTrue(repository.evaluateAutoLock())
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertTrue(repository.items.value.isEmpty())

        // The eventual SAF result must not resurrect anything or double-lock.
        repository.endExternalActivity()
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertFalse(repository.hasActiveExternalActivity())
    }

    @Test
    fun nestedExternalActivitiesOnlySettleOnTheLastEnd() = runBlocking {
        val repository = openingRepository()
        repository.beginExternalActivity()
        repository.beginExternalActivity()
        repository.onAppBackgrounded()
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)

        repository.endExternalActivity()
        // One picker is still in flight: stay open.
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertTrue(repository.hasActiveExternalActivity())

        repository.endExternalActivity()
        repository.awaitLocked()
    }

    @Test
    fun foregroundReturnWithoutResultDoesNotSuppressTheNextBackgroundLock() = runBlocking {
        val repository = openingRepository()
        repository.beginExternalActivity()
        repository.onAppBackgrounded()
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)

        // The picker result callback is never delivered (the picker process died). The
        // foreground return still ends the excursion, so a subsequent background must lock
        // normally instead of deferring forever.
        repository.onAppForegrounded()
        repository.onAppBackgrounded()

        repository.awaitLocked()
        assertFalse(repository.hasActiveExternalActivity())
        assertFalse(repository.isBackgroundLockDeferred())
    }

    @Test
    fun externalActivityDoesNotAffectDisabledAutoLockBuilds() = runBlocking {
        val repository = newRepository(allowAutoLock = false)
        repository.setupVault(PASSWORD.toCharArray())
        repository.beginExternalActivity()

        repository.onAppBackgrounded()

        // DEV_DISABLE_AUTO_LOCK escape hatch still wins; the deferral state is irrelevant.
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        repository.endExternalActivity()
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
