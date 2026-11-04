package sh.reception.client

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** An AES key in memory, standing in for the Android Keystore that Robolectric lacks. */
class SoftwareKey : SecureStore.KeySource {
    private var key: SecretKey? = null

    override fun existing(): SecretKey? = key

    override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
}
