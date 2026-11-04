package sh.reception.client

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a fresh 12-byte IV per value; the IV precedes the ciphertext and its 128-bit tag. */
internal class SecretBox(private val key: SecretKey) {
    /** Throws [GeneralSecurityException] when the key cannot encrypt. */
    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider chooses the random IV; the Android Keystore refuses caller-chosen ones.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected_iv")
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(iv.size + ciphertext.size).put(iv).put(ciphertext).array()
    }

    /**
     * The plaintext, or null when [sealed] was not sealed with this key or was altered. Throws
     * [GeneralSecurityException] when the key itself cannot be used.
     */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES + TAG_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_BYTES))
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: BadPaddingException) {
            null
        } catch (_: IllegalBlockSizeException) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
