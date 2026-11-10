package local.fnswitch.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun freezeAnimations() { compose.mainClock.autoAdvance = false }
    private fun screenshot(name: String) {
        val file = File("build/screenshots/$name.png"); file.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun connectionScreenShowsMaterialControls() {
        compose.setContent { FnSwitchTheme { Surface(Modifier.fillMaxSize()) { ConnectionScreen("192.168.1.199", {}, "admin", {}, "", {}, false, {}) } } }
        compose.onNodeWithText("连接交换机").assertIsDisplayed()
        compose.onNodeWithText("安全连接").assertIsDisplayed()
        compose.onNodeWithContentDescription("显示密码").assertIsDisplayed()
        screenshot("connection")
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun overviewHasAllEightPortsAndResourceUsage() {
        val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.SettingsEthernet, Icons.Outlined.AccountTree, Icons.Outlined.Settings)
        compose.setContent { FnSwitchTheme {
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("概览") }) }, bottomBar = { NavigationBar { listOf("概览", "端口", "VLAN", "系统").forEachIndexed { index, label -> NavigationBarItem(selected = index == 0, onClick = {}, icon = { Icon(icons[index], label) }, label = { Text(label) }) } } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { OverviewScreen(fixtureSnapshot(), 0, {}) }
            }
        } }
        compose.onNodeWithText("FN SWITCH 8 PRO").assertIsDisplayed()
        compose.onNodeWithText("8", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("端口总览").assertIsDisplayed()
        screenshot("overview")
    }
    @Test fun disablingPortRequiresExplicitSecondConfirmation() {
        var applied = false
        compose.setContent { FnSwitchTheme { Surface { PortEditor(fixtureSnapshot().ports.first(), {}, { _, _ -> applied = true }) } } }
        compose.onNode(isToggleable()).performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("应用").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("停用端口 1？").assertIsDisplayed()
        assertFalse(applied)
        compose.onNodeWithText("确认", useUnmergedTree = true).performClick()
        assertTrue(applied)
    }
    @Test @Config(qualifiers = "en-w412dp-h915dp-xxhdpi") fun englishConnectionAndDarkThemeRender() {
        compose.setContent { FnSwitchTheme(ThemeMode.DARK, Palette.PURPLE) { Surface(Modifier.fillMaxSize()) { ConnectionScreen("192.168.1.199", {}, "admin", {}, "", {}, false, {}) } } }
        compose.onNodeWithText("Connect your switch").assertIsDisplayed()
        compose.onNodeWithText("Secure connect").assertIsDisplayed()
        screenshot("connection-en-dark")
    }
    @Test fun themeAndPaletteAreRetainedAfterPreferencesReload() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val preference = AppPreferences(app); preference.chooseTheme(ThemeMode.DARK); preference.choosePalette(Palette.GREEN)
        val reread = AppPreferences(app)
        assertEquals(ThemeMode.DARK, reread.theme); assertEquals(Palette.GREEN, reread.palette)
    }
    @Test fun hardwareCardShowsTeardownInformationInsteadOfFirmwareMemoryLimit() {
        compose.setContent { FnSwitchTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { OverviewScreen(fixtureSnapshot(), 0, {}) } } }
        compose.onNodeWithText("Realtek RTL9303 · MIPS 34Kc").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("256.0 MiB").performScrollTo().assertIsDisplayed()
        screenshot("hardware")
    }

    @Test @Config(qualifiers = "ja-w412dp-h915dp-xxhdpi") fun japaneseConnectionIsLocalized() {
        compose.setContent { FnSwitchTheme { Surface(Modifier.fillMaxSize()) { ConnectionScreen("192.168.1.199", {}, "admin", {}, "", {}, false, {}) } } }
        compose.onNodeWithText("スイッチに接続").assertIsDisplayed()
        compose.onNodeWithText("安全に接続").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "zh-rTW-w412dp-h915dp-xxhdpi") fun traditionalChineseConnectionIsLocalized() {
        compose.setContent { FnSwitchTheme { Surface(Modifier.fillMaxSize()) { ConnectionScreen("192.168.1.199", {}, "admin", {}, "", {}, false, {}) } } }
        compose.onNodeWithText("連接交換機").assertIsDisplayed()
        compose.onNodeWithText("安全連接").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun mikuPaletteUsesRequestedTealAndLegibleButtonText() {
        var scheme: androidx.compose.material3.ColorScheme? = null
        val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.SettingsEthernet, Icons.Outlined.AccountTree, Icons.Outlined.Build, Icons.Outlined.Settings)
        compose.setContent { FnSwitchTheme(ThemeMode.LIGHT, Palette.MIKU) {
            scheme = MaterialTheme.colorScheme
            Scaffold(bottomBar = { NavigationBar { listOf("概览", "端口", "VLAN", "工具", "系统").forEachIndexed { i, label -> NavigationBarItem(selected = i == 0, onClick = {}, icon = { Icon(icons[i], null) }, label = { Text(label) }) } } }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { OverviewScreen(fixtureSnapshot(), 0, {}) }
            }
        } }
        compose.runOnIdle {
            assertEquals(androidx.compose.ui.graphics.Color(0xFF39C5BB), scheme!!.primary)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(scheme!!.onPrimary.toArgb(), scheme!!.primary.toArgb()) >= 4.5)
        }
        screenshot("miku-light")
    }
    @Test fun mikuDarkPaletteRendersWithPinkAccent() {
        compose.setContent { FnSwitchTheme(ThemeMode.DARK, Palette.MIKU) { Surface(Modifier.fillMaxSize()) { ConnectionScreen("192.168.1.199", {}, "admin", {}, "", {}, false, {}) } } }
        compose.onNodeWithText("安全连接").assertIsDisplayed()
        screenshot("miku-dark")
    }

}
