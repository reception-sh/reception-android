package sh.reception.receptionAndroidExample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import sh.reception.Reception

/**
 * Debug builds only: a `singleTask` launcher activity in a task of its own, as Expo's template declares its
 * `MainActivity`, with its own launcher icon. Returning through that icon makes Android finish every activity above
 * it, the chat's sheet included, which must come back; every other way into this activity (the host's notification,
 * a deep link, a shortcut, its own navigation) leaves the chat closed. Every intent it receives is logged ([IntentLog]).
 */
class SingleTaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IntentLog.log("created", intent)
        addOnNewIntentListener { IntentLog.log("new", it) }
        publishShortcut()
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.single_task_title))
                        Button(onClick = Reception::openChat) { Text(stringResource(R.string.support)) }
                        OutlinedButton(onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, "tel:5550100".toUri()))
                        }) { Text(stringResource(R.string.leave_through_dialer)) }
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@SingleTaskActivity, SingleTaskCoverActivity::class.java))
                        }) { Text(stringResource(R.string.open_covering_screen)) }
                        OutlinedButton(onClick = {
                            notify(1, R.string.notify_open_start, Intent(this@SingleTaskActivity, SingleTaskActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        }) { Text(stringResource(R.string.notify_open_start)) }
                        OutlinedButton(onClick = {
                            notify(2, R.string.notify_launch_intent, launchIntent(this@SingleTaskActivity))
                        }) { Text(stringResource(R.string.notify_launch_intent)) }
                    }
                }
            }
        }
    }

    /** A notification of the host's own, as a messaging library posts it, whose tap starts [target]. */
    private fun notify(id: Int, title: Int, target: Intent) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.host_notifications), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = PendingIntent.getActivity(this, id, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            id,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_example_notification)
                .setContentTitle(getString(title)).setContentIntent(tap).setAutoCancel(true).build(),
        )
    }

    /** A launcher shortcut into this activity (long-press its icon), as a host's static or dynamic shortcut. */
    private fun publishShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcut = ShortcutInfo.Builder(this, "single_task").setShortLabel(getString(R.string.single_task_shortcut))
            .setActivity(ComponentName(this, SingleTaskActivity::class.java))
            .setIntent(Intent(Intent.ACTION_VIEW, null, this, SingleTaskActivity::class.java))
            .build()
        getSystemService(ShortcutManager::class.java)?.dynamicShortcuts = listOf(shortcut)
    }

    companion object {
        private const val CHANNEL = "host"

        /** The intent `PackageManager.getLaunchIntentForPackage` builds, for this activity. */
        fun launchIntent(context: Context): Intent =
            Intent.makeMainActivity(ComponentName(context, SingleTaskActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
