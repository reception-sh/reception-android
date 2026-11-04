package sh.reception.receptionAndroidExample

import android.util.Base64
import android.util.Log
import sh.reception.Reception
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The example's buttons for push, automations, deletion and verified users. */
object ExampleActions {
    private const val TAG = "ReceptionExample"

    /** An automation in the local test app listens for it. */
    const val EVENT = "android_example_event"
    private var user = 0

    fun registerForPush() = Reception.registerForPushIfAuthorized()

    fun track() = Reception.track(EVENT, opensChat = true)

    fun deleteData() = Reception.deleteData { error -> Log.i(TAG, "Delete data ${error?.code ?: "succeeded"}") }

    /** Signs in as a new verified user. A real app gets the token from its server, never with the secret on the device. */
    fun switchUser() {
        val secret = BuildConfig.RECEPTION_IDENTITY_SECRET
        if (secret.isEmpty()) {
            Log.w(TAG, "No identity secret in .local/identity-secret.txt")
            return
        }
        user += 1
        val expires = System.currentTimeMillis() / 1000 + 3600
        Reception.identifyWithToken(token(secret, """{"user_id":"android-token-user-$user","exp":$expires}"""))
    }

    private fun token(secret: String, payload: String): String {
        val content = "${encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())}.${encode(payload.toByteArray())}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "$content.${encode(mac.doFinal(content.toByteArray()))}"
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
