package local.fnswitch.manager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val title: Int) { SYSTEM(R.string.follow_system), LIGHT(R.string.light), DARK(R.string.dark) }
enum class Palette(val title: Int, val color: Long) {
    DYNAMIC(R.string.dynamic, 0xFF0B57D0), BLUE(R.string.blue, 0xFF0B57D0), MIKU(R.string.miku, 0xFF39C5BB), GREEN(R.string.green, 0xFF146C2E), PURPLE(R.string.purple, 0xFF6750A4), ORANGE(R.string.orange, 0xFF9C4221)
}
class AppPreferences(application: Application) {
    private val store = application.getSharedPreferences("appearance", 0)
    var theme by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == store.getString("theme", "SYSTEM") } ?: ThemeMode.SYSTEM); private set
    var palette by mutableStateOf(Palette.entries.firstOrNull { it.name == store.getString("palette", "DYNAMIC") } ?: Palette.DYNAMIC); private set
    fun chooseTheme(mode: ThemeMode) { theme = mode; store.edit().putString("theme", mode.name).apply() }
    fun choosePalette(value: Palette) { palette = value; store.edit().putString("palette", value.name).apply() }
    fun language(tag: String) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
}
