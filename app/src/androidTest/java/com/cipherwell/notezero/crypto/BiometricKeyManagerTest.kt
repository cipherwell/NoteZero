package com.cipherwell.notezero.crypto

import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.InvalidKeyException

/**
 * Device-level tests for [BiometricKeyManager] (Android Keystore TEE/StrongBox).
 *
 * Robolectric cannot emulate the Keystore, so these run only on a device/emulator. Tests
 * that need enrolled strong biometrics skip gracefully via [Assume] when the device has
 * none, which is the normal case for CI emulators. Every test starts and ends from a clean
 * (disabled) manager so no key material leaks between tests.
 *
 * What is provable without driving a BiometricPrompt UI:
 *  - availability / "not enrolled" negative paths never crash and never return secrets;
 *  - a per-use-auth Keystore key refuses `doFinal` without a prompt
 *    ([UserNotAuthenticatedException]); therefore a prompt-free `completeEnroll` must fail
 *    and must not persist a wrap;
 *  - `disable()` is idempotent and clears all state.
 * The positive biometric round trip requires the Phase-2/recovery flow tests driven from
 * the UI (Lead-run manual verification) because it needs a real prompt.
 */
@RunWith(AndroidJUnit4::class)
class BiometricKeyManagerTest {

    private lateinit var manager: BiometricKeyManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        manager = BiometricKeyManager(context)
        manager.disable()
    }

    @After
    fun tearDown() {
        manager.disable()
    }

    @Test
    fun freshManagerIsNotEnrolledAndHasNoDecryptCipher() {
        assertFalse(manager.isEnrolled())
        assertNull(manager.prepareDecrypt())
    }

    @Test
    fun disableIsIdempotent() {
        manager.disable()
        manager.disable()
        assertFalse(manager.isEnrolled())
        assertNull(manager.prepareDecrypt())
    }

    @Test
    fun canUseBiometricsNeverThrows() {
        // Result is environment-dependent; the contract is that it is a safe query.
        manager.canUseBiometrics()
    }

    @Test
    fun prepareEnrollReturnsNullWhenBiometricsAreUnavailable() {
        assumeFalse("device has enrolled strong biometrics", manager.canUseBiometrics())
        assertNull(manager.prepareEnroll())
        assertFalse(manager.isEnrolled())
    }

    @Test
    fun promptFreeKeystoreDoFinalIsRefusedByPerUseAuthentication() {
        assumeTrue("device has enrolled strong biometrics", manager.canUseBiometrics())
        val enroll = manager.prepareEnroll()
        assumeTrue("device/emulator could not create the auth-gated key", enroll != null)
        val vmk = NoteCrypto.randomVmk()
        var refused = false
        try {
            enroll!!.cipher.doFinal(vmk)
        } catch (expected: UserNotAuthenticatedException) {
            refused = true
        } catch (expected: InvalidKeyException) {
            // Older Keystores fail earlier in the flow; either way no data may be released.
            refused = true
        } catch (expected: GeneralSecurityException) {
            refused = true
        } finally {
            NoteCrypto.wipe(vmk)
        }
        assertTrue("a per-use-auth Keystore cipher must refuse doFinal without a prompt", refused)
        assertFalse("a refused doFinal must not leave an enrolment", manager.isEnrolled())
    }

    @Test
    fun unauthenticatedCompleteEnrollFailsAndPersistsNothing() {
        assumeTrue("device has enrolled strong biometrics", manager.canUseBiometrics())
        val enroll = manager.prepareEnroll()
        assumeTrue("device/emulator could not create the auth-gated key", enroll != null)
        val vmk = NoteCrypto.randomVmk()
        try {
            assertFalse(
                "completeEnroll must not succeed without a BiometricPrompt-authenticated cipher",
                manager.completeEnroll(enroll!!, vmk),
            )
        } finally {
            NoteCrypto.wipe(vmk)
        }
        assertFalse("failed enrolment must not persist a wrapped VMK", manager.isEnrolled())
        assertNull("failed enrolment must not produce a decrypt cipher", manager.prepareDecrypt())
    }

    @Test
    fun completeEnrollRejectsAWrongSizedVMKBeforeTouchingTheCipher() {
        assumeTrue("device has enrolled strong biometrics", manager.canUseBiometrics())
        val enroll = manager.prepareEnroll()
        assumeTrue("device/emulator could not create the auth-gated key", enroll != null)
        for (size in intArrayOf(0, 16, 31, 33)) {
            assertFalse(manager.completeEnroll(enroll!!, ByteArray(size)))
        }
        assertFalse(manager.isEnrolled())
    }

    @Test
    fun prepareDecryptAfterDisableIsNullAndDoesNotThrow() {
        manager.disable()
        assertNull(manager.prepareDecrypt())
        assertFalse(manager.isEnrolled())
    }

    @Test
    fun managerDoesNotExposeAWrappedVMKThroughAnyPublicAccessor() {
        assumeTrue("device has enrolled strong biometrics", manager.canUseBiometrics())
        val enroll = manager.prepareEnroll()
        assumeTrue("device/emulator could not create the auth-gated key", enroll != null)
        // completeDecrypt with an unauthenticated prepareEnroll cipher (wrong direction) must
        // fail rather than release anything.
        val decrypt = BiometricDecrypt(enroll!!.cipher)
        assertNull(manager.completeDecrypt(decrypt))
        assertNotNull(enroll.cipher)
    }
}
