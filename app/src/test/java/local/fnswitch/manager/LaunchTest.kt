package local.fnswitch.manager

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test fun activityLaunchesWithCompatibleTheme() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertNotNull(controller.get().findViewById<android.view.View>(android.R.id.content)) }
        finally { controller.pause().stop().destroy() }
    }
}
