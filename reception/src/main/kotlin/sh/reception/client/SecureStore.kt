package sh.reception.client

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import sh.reception.Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Small secrets (the session secret, queued logout revocations) encrypted with an AES-GCM key in the Android
 * Keystore and stored in SharedPreferences. Replaces the iOS Keychain items.
 */
internal class SecureStore(private val preferences: SharedPreferences, private val keys: KeySource = KeystoreKey) {
    /** `Unavailable` (the Keystore failed) must never be treated as "no item". */
    sealed interface ReadResult {
        data class Value(val value: String) : ReadResult
        data object Missing : ReadResult
        data object Unavailable : ReadResult
    }

    /** The encryption key: [existing] returns null when none was created yet; [create] makes one. */
    interface KeySource {
        fun existing(): SecretKey?
        fun create(): SecretKey
    }

    fun read(account: String): ReadResult {
        val stored = try {
            preferences.getString(account, null)
        } catch (_: ClassCastException) {
            null
        } ?: return ReadResult.Missing
        return try {
            // A value without its key, or one the key cannot open, came from a backup of another device.
            val key = keys.existing() ?: return ReadResult.Missing
            val sealed = decode(stored) ?: return ReadResult.Missing
            val plaintext = SecretBox(key).open(sealed) ?: return ReadResult.Missing
            ReadResult.Value(String(plaintext, Charsets.UTF_8))
        } catch (error: Exception) {
            if (!isKeystoreFailure(error)) throw error
            Log.error { "Secure storage read failed, ${error.javaClass.simpleName}" }
            ReadResult.Unavailable
        }
    }

    fun save(value: String, account: String): Boolean {
        val sealed = try {
            SecretBox(keys.existing() ?: keys.create()).seal(value.toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            if (!isKeystoreFailure(error)) throw error
            Log.error { "Secure storage update failed, ${error.javaClass.simpleName}" }
            return false
        }
        preferences.edit { putString(account, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
        return true
    }

    fun delete(account: String): Boolean {
        preferences.edit { remove(account) }
        return true
    }

    private fun decode(stored: String): ByteArray? = try {
        Base64.decode(stored, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isKeystoreFailure(error: Exception): Boolean =
        error is GeneralSecurityException || error is ProviderException || error is IOException

    /** One app-wide AES-256 key in the Android Keystore; no user authentication, never leaves the device. */
    object KeystoreKey : KeySource {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "sh.reception.secure-store"

        override fun existing(): SecretKey? {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            return keyStore.getKey(ALIAS, null) as? SecretKey
        }

        override fun create(): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    companion object {
        private var shared: SecureStore? = null

        /** The key of the shared store. Robolectric has no Android Keystore, so the session tests use a software key. */
        var keys: KeySource = KeystoreKey

        fun shared(context: Context): SecureStore = shared ?: SecureStore(
            context.applicationContext.getSharedPreferences("sh.reception.secure", Context.MODE_PRIVATE),
            keys,
        ).also { shared = it }
    }
}
