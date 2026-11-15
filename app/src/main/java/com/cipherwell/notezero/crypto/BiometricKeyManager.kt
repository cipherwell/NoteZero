package com.cipherwell.notezero.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed biometric unlock.
 *
 * Security properties required by the spec (same bar as VaultZero):
 *  - The VMK copy is wrapped by an AES-256-GCM key that lives INSIDE Android Keystore
 *    (never exportable), not by the vault password.
 *  - The Keystore key requires per-use user authentication
 *    (`setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`),
 *    so no code path can decrypt the wrapped VMK without a successful BiometricPrompt.
 *  - The key is invalidated by biometric re-enrolment
 *    (`setInvalidatedByBiometricEnrollment(true)`); a stale wrap is deleted, not reused.
 *  - Enrolment is two-phase: [prepareEnroll] returns an ENCRYPT_MODE [Cipher] for
 *    `BiometricPrompt.CryptoObject`; [completeEnroll] only persists the wrapped VMK after
 *    that prompt authorised the cipher. A prompt-free `doFinal` fails by design.
 *  - Unlock is two-phase: [prepareDecrypt] returns the DECRYPT_MODE [BiometricDecrypt] for
 *    the prompt; [completeDecrypt] releases the VMK only after authentication.
 *  - Disabling biometric unlock deletes both the Keystore key and the wrapped VMK.
 *
 * Only ciphertext (the wrapped VMK) is persisted, in app-private SharedPreferences; the
 * plaintext VMK never touches disk. Instrumented test:
 * androidTest/crypto/BiometricKeyManagerTest.kt (Robolectric cannot emulate the Keystore TEE).
 */
class BiometricKeyManager(private val context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /** True when the device has usable strong biometric hardware and an enrolled biometric. */
    fun canUseBiometrics(): Boolean = try {
        BiometricManager.from(appContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    /** True when a Keystore key + wrapped VMK are present and the wrapping key is still usable. */
    fun isEnrolled(): Boolean {
        val blob = storedBlob() ?: return false
        if (blob.size < MIN_BLOB_BYTES) return false
        return try {
            val key = existingKey() ?: return false
            // init does not require authentication; it does throw when the key has been
            // permanently invalidated (biometric re-enrolment / lock-screen reset).
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, ivOf(blob)))
            }
            true
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            false
        } catch (invalidated: UnrecoverableKeyException) {
            false
        } catch (unauthenticated: UserNotAuthenticatedException) {
            // Legacy per-use-auth keys may demand authentication as early as init; the
            // key material still exists, decryption is simply gated behind the prompt.
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Phase 1 of enabling biometric unlock: creates the Keystore key (if needed) and
     * returns a cipher primed for ENCRYPT_MODE, to be wrapped in
     * `BiometricPrompt.CryptoObject`. A per-use-auth Keystore key cannot encrypt without a
     * successful prompt, so enrolment is necessarily two-phase.
     */
    fun prepareEnroll(): BiometricEnroll? {
        if (!canUseBiometrics()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            BiometricEnroll(cipher)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Phase 2: after a successful BiometricPrompt auth, wraps [vmk] under the Keystore
     * key and persists the wrapped copy. Returns false on any failure (no partial state).
     */
    fun completeEnroll(enroll: BiometricEnroll, vmk: ByteArray): Boolean {
        if (vmk.size != VMK_LENGTH_BYTES) return false
        return try {
            enroll.cipher.updateAAD(AAD)
            val iv = enroll.cipher.iv
            if (iv == null || iv.size != IV_LENGTH_BYTES) return false
            val ciphertext = enroll.cipher.doFinal(vmk)
            val blob = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
            persistBlob(blob)
        } catch (e: Exception) {
            // Unauthorised ciphers throw UserNotAuthenticatedException (or InvalidKeyException).
            false
        }
    }

    /**
     * Returns a cipher primed for decryption, or null when not enrolled/invalidated.
     * If the Keystore reports the key was invalidated by re-enrolment, the stale state
     * is wiped and null is returned.
     */
    fun prepareDecrypt(): BiometricDecrypt? {
        val blob = storedBlob() ?: return null
        if (blob.size < MIN_BLOB_BYTES) {
            disable()
            return null
        }
        val key = try {
            existingKey()
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            disable()
            return null
        } catch (invalidated: UnrecoverableKeyException) {
            disable()
            return null
        } catch (e: Exception) {
            return null
        } ?: run {
            // Key gone (cleared app data / keystore reset): never reuse the orphaned wrap.
            disable()
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, ivOf(blob)))
            BiometricDecrypt(cipher)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            disable()
            null
        } catch (invalidated: UnrecoverableKeyException) {
            disable()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Decrypts the wrapped VMK after a successful BiometricPrompt auth. Null on failure. */
    fun completeDecrypt(decrypt: BiometricDecrypt): ByteArray? {
        val blob = storedBlob() ?: return null
        if (blob.size < MIN_BLOB_BYTES) return null
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val vmk = try {
            decrypt.cipher.updateAAD(AAD)
            decrypt.cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            return null
        }
        if (vmk.size != VMK_LENGTH_BYTES) {
            NoteCrypto.wipe(vmk)
            return null
        }
        return vmk
    }

    /** Deletes the Keystore key and the wrapped VMK copy. Idempotent. */
    fun disable() {
        runCatching {
            prefs().edit().remove(PREF_WRAPPED_VMK).commit()
        }
        runCatching {
            val store = keyStore()
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    /**
     * Creates the per-use-auth AES-256-GCM wrapping key, preferring StrongBox and falling
     * back to the TEE/software-backed Keystore when StrongBox is unavailable.
     */
    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryCreateKey(strongBox = true)?.let { return it }
        }
        return tryCreateKey(strongBox = false)
            ?: throw IllegalStateException("Android Keystore could not create the biometric wrapping key")
    }

    private fun tryCreateKey(strongBox: Boolean): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(buildKeySpec(strongBox))
        generator.generateKey()
    } catch (e: Exception) {
        null
    }

    private fun buildKeySpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0-second validity: every cipher operation needs its own authenticated prompt.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun storedBlob(): ByteArray? {
        val encoded = prefs().getString(PREF_WRAPPED_VMK, null) ?: return null
        return Encoding.base64Decode(encoded)
    }

    private fun persistBlob(blob: ByteArray): Boolean =
        prefs().edit().putString(PREF_WRAPPED_VMK, Encoding.base64Encode(blob)).commit()

    private fun ivOf(blob: ByteArray): ByteArray = blob.copyOfRange(0, IV_LENGTH_BYTES)

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "notezero.biometric.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "notezero_biometric_v1"
        private const val PREF_WRAPPED_VMK = "wrapped_vmk_v1"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val VMK_LENGTH_BYTES = 32
        private const val MIN_BLOB_BYTES = IV_LENGTH_BYTES + 16
        private val AAD = "notezero.biometric.v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * A Keystore cipher primed for decryption after a successful BiometricPrompt.
 * UI wraps this in androidx.biometric.BiometricPrompt.CryptoObject(cipher).
 */
data class BiometricDecrypt(val cipher: Cipher)

/**
 * A Keystore cipher primed for ENCRYPT_MODE after a successful BiometricPrompt, used to
 * enable biometric unlock (see [BiometricKeyManager.prepareEnroll]/[BiometricKeyManager.completeEnroll]).
 */
data class BiometricEnroll(val cipher: Cipher)
