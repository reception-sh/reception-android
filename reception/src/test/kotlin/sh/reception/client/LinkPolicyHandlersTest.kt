package sh.reception.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The host's own handlers come from its intent filters: paths must match, schemes compare exactly, hosts ignore case. */
@RunWith(RobolectricTestRunner::class)
class LinkPolicyHandlersTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun addHostActivity() {
        val component = ComponentName(context.packageName, "${context.packageName}.LinkActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
            addDataAuthority("host.example", null)
            addDataPath("/app/", PatternMatcher.PATTERN_PREFIX)
        })
        packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("myapp")
        })
    }

    @Test
    fun onlyTheDeclaredPathIsTheHosts() {
        val policy = LinkPolicy.of(context)
        assertTrue(policy.isHostScheme("https://host.example/app/settings"))
        assertFalse(policy.isHostScheme("https://host.example/blog/post"))
        assertTrue(policy.isHostScheme("https://host.example/app/other"))
    }

    @Test
    fun theSchemeCaseMustMatchTheFilter() {
        val policy = LinkPolicy.of(context)
        assertTrue(policy.isHostScheme("myapp://settings"))
        assertFalse(policy.isHostScheme("MYAPP://settings"))
        assertTrue(policy.isHostScheme("https://HOST.example/app/settings"))
        assertFalse(policy.isHostScheme("https://HOST.example/blog/settings"))
    }
}
