package sh.reception.client

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.reception.Log
import kotlin.coroutines.resume

/**
 * The only place the SDK touches Firebase Messaging, which the host may not ship (`compileOnly`). Every call checks
 * that the classes exist first; [FirebaseCalls] is loaded only after that check.
 */
internal object FirebaseBridge {
    private var reportedMissing = false

    private val isPresent: Boolean by lazy {
        try {
            Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            Class.forName("com.google.firebase.FirebaseApp")
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** The FCM registration token, or null without Firebase Messaging, before Firebase starts, or on a failure. */
    suspend fun token(context: Context): String? {
        if (!isPresent) {
            if (!reportedMissing) {
                reportedMissing = true
                Log.debug { "Push registration skipped, Firebase Messaging not in the app" }
            }
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                FirebaseCalls.token(context)
            } catch (error: IllegalStateException) {
                Log.debug { "Push registration skipped, Firebase unavailable: ${error.javaClass.simpleName}" }
                null
            } catch (_: LinkageError) {
                Log.debug { "Push registration skipped, Firebase Messaging incompatible" }
                null
            }
        }
    }
}

private object FirebaseCalls {
    suspend fun token(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.debug { "Push registration skipped, Firebase not initialized" }
            return null
        }
        val task = FirebaseMessaging.getInstance().token
        return suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener(Runnable::run) { completed ->
                val token = if (completed.isSuccessful) completed.result else null
                if (token == null) Log.debug { "Push token unavailable from Firebase" }
                continuation.resume(token)
            }
        }
    }
}
