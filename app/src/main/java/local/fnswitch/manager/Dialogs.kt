package local.fnswitch.manager

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DialogShell(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BasicAlertDialog(dismiss) { Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) { Column(Modifier.widthIn(min = 280.dp, max = 520.dp).heightIn(max = 740.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) } }
}
@Composable fun SettingsDialog(preferences: AppPreferences, dismiss: () -> Unit) { DialogShell(dismiss) {
    Text(t(R.string.settings), style = MaterialTheme.typography.headlineSmall)
    SettingsEditor(preferences)
    TextButton(dismiss, Modifier.align(Alignment.End)) { Text(t(R.string.close)) }
} }
@Composable fun SettingsEditor(preferences: AppPreferences) {
    SectionTitle(t(R.string.theme))
    Row { ThemeMode.entries.forEach { mode -> FilterChip(preferences.theme == mode, { preferences.chooseTheme(mode) }, label = { Text(t(mode.title)) }) } }
    SectionTitle(t(R.string.palette))
    Palette.entries.forEach { palette -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(preferences.palette == palette, { preferences.choosePalette(palette) }); Text(t(palette.title)) } }
    SectionTitle(t(R.string.language))
    val selected = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val languages = listOf("" to t(R.string.follow_system), "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語")
    languages.forEach { (tag, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == tag, { preferences.language(tag) }); Text(label) } }
}
@Composable fun CertificateDialog(address: String, cert: DeviceCertificate, changed: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(if (changed) Icons.Outlined.GppMaybe else Icons.Outlined.VerifiedUser, null) }, title = { Text(t(if (changed) R.string.certificate_changed else R.string.certificate_title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t(if (changed) R.string.certificate_change_note else R.string.certificate_note)); Text(address, style = MaterialTheme.typography.titleMedium)
        Text("SHA-256", style = MaterialTheme.typography.labelMedium)
        Text(cert.fingerprint.chunked(16).joinToString("\n") { it.chunked(2).joinToString(":") }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(t(R.string.expires, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cert.expires)))
        TextButton({ context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SHA-256", cert.fingerprint)) }) { Text(t(R.string.copy_fingerprint)) }
    } }, confirmButton = { TextButton(onConfirm) { Text(t(R.string.trust_connect)) } }, dismissButton = { TextButton(onDismiss) { Text(t(R.string.cancel)) } })
}
@Composable fun PortDialog(port: Port, onDismiss: () -> Unit, onApply: (Boolean, String) -> Unit) { DialogShell(onDismiss) { PortEditor(port, onDismiss, onApply) } }
@Composable fun PortEditor(port: Port, onDismiss: () -> Unit, onApply: (Boolean, String) -> Unit) {
    var enabled by remember(port.number) { mutableStateOf(port.enabled) }; var description by remember(port.number) { mutableStateOf(port.description) }; var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t(if (confirm) R.string.disable_port_title else R.string.port_number, port.number), style = MaterialTheme.typography.headlineSmall)
        if (confirm) { Text(t(R.string.disable_port_note)); Row { TextButton({ confirm = false }) { Text(t(R.string.back)) }; TextButton({ onApply(enabled, description) }) { Text(t(R.string.confirm)) } } }
        else {
            OutlinedTextField(description, { if (it.length <= 32) description = it }, label = { Text(t(R.string.port_description)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(t(R.string.enable_port), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            Text(t(R.string.port_note), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onDismiss) { Text(t(R.string.cancel)) }; TextButton({ if (!enabled && port.enabled) confirm = true else onApply(enabled, description) }, enabled = enabled != port.enabled || description != port.description) { Text(t(R.string.apply)) } }
        }
    }
}
@Composable fun ConfirmDialog(title: String, text: String, label: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(confirm) { Text(label) } }, dismissButton = { TextButton(dismiss) { Text(t(R.string.cancel)) } }) }
@Composable fun TextEditDialog(title: String, label: String, initial: String, note: String, dismiss: () -> Unit, numeric: Boolean = false, apply: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(note, style = MaterialTheme.typography.bodySmall); OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text)) } }, confirmButton = { TextButton({ apply(value.trim()) }, enabled = value.isNotBlank()) { Text(t(R.string.apply)) } }, dismissButton = { TextButton(dismiss) { Text(t(R.string.cancel)) } })
}
@Composable fun VlanMembersDialog(original: VlanConfiguration, dismiss: () -> Unit, apply: (List<VlanPortState>) -> Unit) {
    var values by remember(original) { mutableStateOf(original.ports.take(8)) }; var confirm by remember { mutableStateOf(false) }
    DialogShell(dismiss) {
        Text("VLAN ${original.id}", style = MaterialTheme.typography.headlineSmall); Text(t(R.string.vlan_warning), style = MaterialTheme.typography.bodySmall)
        if (!confirm) values.forEachIndexed { i, state ->
            Text(t(R.string.port_number, i + 1) + if (state.pvid) " · PVID" else "", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(0 to R.string.exclude, 2 to R.string.tagged, 3 to R.string.untagged).forEach { (membership, label) ->
                    val before = original.ports[i]
                    val allowed = if (before.mode == 1 || before.mode == 3 || before.pvid || original.id == 1 && before.mode == 2) membership == before.membership else true
                    FilterChip(state.membership == membership, { values = values.toMutableList().also { it[i] = state.copy(membership = membership, forbidden = false) } }, enabled = allowed, label = { Text(t(label), style = MaterialTheme.typography.labelSmall) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(t(R.string.forbidden), Modifier.weight(1f)); Switch(state.forbidden, { flag -> values = values.toMutableList().also { it[i] = state.copy(forbidden = flag) } }, enabled = state.membership == 0 && !state.pvid) }
            HorizontalDivider()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(dismiss) { Text(t(R.string.cancel)) }; TextButton({ if (confirm) apply(values) else confirm = true }, enabled = values != original.ports.take(8)) { Text(t(if (confirm) R.string.confirm else R.string.apply)) } }
    }
}
@Composable fun PortVlanDialog(original: PortVlanSettings, dismiss: () -> Unit, apply: (Int, Int) -> Unit) {
    var mode by remember { mutableIntStateOf(original.mode) }; var pvid by remember { mutableStateOf(original.pvid.toString()) }; var confirm by remember { mutableStateOf(false) }
    DialogShell(dismiss) {
        Text(t(R.string.port_number, original.port), style = MaterialTheme.typography.headlineSmall); Text(t(R.string.vlan_warning), style = MaterialTheme.typography.bodySmall)
        if (!confirm) {
            listOf("Hybrid", "Access", "Trunk").forEachIndexed { i, label -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == i, { mode = i }); Text(label) } }
            OutlinedTextField(pvid, { pvid = it.filter(Char::isDigit).take(4) }, label = { Text("PVID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } else Text("${listOf("Hybrid", "Access", "Trunk").getOrElse(mode) { "—" }} · PVID $pvid")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(dismiss) { Text(t(R.string.cancel)) }; TextButton({ if (confirm) apply(mode, pvid.toInt()) else confirm = true }, enabled = mode in 0..2 && pvid.toIntOrNull() in 1..4094 && (mode != original.mode || pvid != original.pvid.toString())) { Text(t(if (confirm) R.string.confirm else R.string.apply)) } }
    }
}
@Composable fun ImportDialog(file: ImportFile, dismiss: () -> Unit, upload: () -> Unit) {
    var typed by remember(file) { mutableStateOf("") }; val keyword = if (file.kind == ImportKind.FIRMWARE) "ST3208F" else "RESTORE"
    DialogShell(dismiss) {
        Text(t(file.kind.title), style = MaterialTheme.typography.headlineSmall)
        InfoRow(t(R.string.file_name), file.name); InfoRow(t(R.string.file_size), formatBytes(file.bytes.size.toLong()))
        Text("SHA-256\n" + file.sha256.chunked(16).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (file.kind == ImportKind.FIRMWARE) Text(t(R.string.firmware_check_note), style = MaterialTheme.typography.bodySmall)
        Text(t(R.string.upload_note)); Text(t(R.string.backup_note), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(typed, { typed = it }, label = { Text(t(R.string.type_confirmation, keyword)) }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(dismiss) { Text(t(R.string.cancel)) }; Button(upload, enabled = typed == keyword) { Text(t(R.string.confirm)) } }
    }
}
