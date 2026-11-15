package com.cipherwell.notezero.crypto

import android.content.Context
import javax.crypto.Cipher

/**
 * Hardware-backed biometric unlock — CONTRACT ONLY at bootstrap.
 *
 * Security properties required by the spec (same bar as VaultZero):
 *  - The VMK copy is wrapped by an AES-256-GCM key that lives INSIDE Android Keystore
 *    (never exportable), not by the vault password.
 *  - The Keystore key requires per-use user authentication
 *    (`setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`).
 *  - The key is invalidated by biometric re-enrolment
 *    (`setInvalidatedByBiometricEnrollment(true)`); a stale wrap is deleted, not reused.
 *  - The UI must pass the returned [BiometricDecrypt] into a BiometricPrompt CryptoObject;
 *    the VMK is only released after a successful auth result.
 *  - Disabling biometric unlock deletes both the Keystore key and the wrapped VMK.
 *
 * Instrumented test: androidTest/crypto/BiometricKeyManagerTest.kt (requires a device/emulator
 * with enrolled biometrics; Robolectric cannot emulate the Keystore TEE).
 */
class BiometricKeyManager(private val context: Context) {

    /** True when the device has usable strong biometric hardware and an enrolled biometric. */
    fun canUseBiometrics(): Boolean = TODO("crypto-core: implement")

    /** True when a Keystore key + wrapped VMK are present and still valid. */
    fun isEnrolled(): Boolean = TODO("crypto-core: implement")

    /**
     * Creates the Keystore key (if needed) and wraps [vmk] under it. Must only be called
     * while the vault is unlocked. Returns false when biometrics are unavailable.
     */
    fun enroll(vmk: ByteArray): Boolean = TODO("crypto-core: implement")

    /**
     * Returns a cipher primed for decryption, or null when not enrolled/invalidated.
     * If the Keystore reports the key was invalidated by re-enrolment, the stale state
     * is wiped and null is returned.
     */
    fun prepareDecrypt(): BiometricDecrypt? = TODO("crypto-core: implement")

    /** Decrypts the wrapped VMK after a successful BiometricPrompt auth. Null on failure. */
    fun completeDecrypt(decrypt: BiometricDecrypt): ByteArray? = TODO("crypto-core: implement")

    /** Deletes the Keystore key and the wrapped VMK copy. */
    fun disable(): Unit = TODO("crypto-core: implement")
}

/**
 * A Keystore cipher primed for decryption after a successful BiometricPrompt.
 * UI wraps this in androidx.biometric.BiometricPrompt.CryptoObject(cipher).
 */
data class BiometricDecrypt(val cipher: Cipher)
