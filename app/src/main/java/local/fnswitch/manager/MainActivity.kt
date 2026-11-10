package local.fnswitch.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@Composable fun t(id: Int, vararg args: Any): String = stringResource(id, *args)
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { val vm: SwitchViewModel = viewModel(); FnSwitchTheme(vm.appearance.theme, vm.appearance.palette) { SwitchApp(vm) } }
    }
}
@Composable fun FnSwitchTheme(mode: ThemeMode = ThemeMode.SYSTEM, palette: Palette = Palette.DYNAMIC, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    val primary = Color(palette.color)
    val scheme = if (palette == Palette.MIKU) {
        if (dark) darkColorScheme(primary = Color(0xFF39C5BB), onPrimary = Color(0xFF003833), primaryContainer = Color(0xFF104F49), onPrimaryContainer = Color(0xFFA3F2E9), secondary = Color(0xFFFFB1CD), onSecondary = Color(0xFF591F38), secondaryContainer = Color(0xFF71334F), onSecondaryContainer = Color(0xFFFFD8E7), background = Color(0xFF0E1615), surface = Color(0xFF0E1615))
        else lightColorScheme(primary = Color(0xFF39C5BB), onPrimary = Color(0xFF003833), primaryContainer = Color(0xFFB8F1E9), onPrimaryContainer = Color(0xFF003D38), secondary = Color(0xFFAD3F71), onSecondary = Color.White, secondaryContainer = Color(0xFFFFD8E7), onSecondaryContainer = Color(0xFF4D1731), background = Color(0xFFF5FBF9), surface = Color(0xFFF5FBF9))
    } else if (palette == Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.lerp(primary, Color.White, .65f), primaryContainer = androidx.compose.ui.graphics.lerp(primary, Color.Black, .55f), onPrimaryContainer = Color(0xFFE2E8FF), background = Color(0xFF111318))
    else lightColorScheme(primary = primary, onPrimary = Color.White, primaryContainer = androidx.compose.ui.graphics.lerp(primary, Color.White, .83f), onPrimaryContainer = androidx.compose.ui.graphics.lerp(primary, Color.Black, .6f), secondaryContainer = Color(0xFFC4EED0), surface = Color(0xFFF8F9FF), background = Color(0xFFF8F9FF))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SwitchApp(vm: SwitchViewModel) {
    val state = vm.ui; val context = LocalContext.current; val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var port by remember { mutableStateOf<Port?>(null) }
    var settings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var ntp by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var createVlan by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Vlan?>(null) }
    var remove by remember { mutableStateOf<Vlan?>(null) }
    var webPath by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf(false) }
    var pendingImport by rememberSaveable { mutableStateOf(ImportKind.FIRMWARE) }
    var exportRequested by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { t(it) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearMessage() } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = vm.ui.export
        if (uri != null && file != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } ?: error("Output unavailable") }; vm.message(R.string.export_success) }
            catch (_: Exception) { vm.message(R.string.error_file) }
            finally { vm.consumeExport() }
        } else vm.consumeExport()
    }
    LaunchedEffect(state.export?.name) { state.export?.let { if (exportRequested != it.name) { exportRequested = it.name; exportLauncher.launch(it.name) } } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "import"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(FileValidation.MAX_BYTES) } ?: error("Input unavailable")
                    name to bytes
                }
                vm.prepareImport(file.first, file.second, pendingImport)
            } catch (_: Exception) { vm.message(R.string.error_file) }
        }
    }
    val currentApi = vm.api
    if (webPath != null && currentApi != null) {
        WebConsoleScreen(currentApi, webPath!!, { webPath = null; vm.refresh() }, { file -> vm.offerExport(file) }, { webPath = null; vm.logout(); tab = 0 })
        return
    }
    val labels = listOf(R.string.overview, R.string.ports, R.string.vlans, R.string.tools, R.string.system)
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.SettingsEthernet, Icons.Outlined.AccountTree, Icons.Outlined.Build, Icons.Outlined.Settings)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        CenterAlignedTopAppBar(title = { Text(if (state.snapshot == null) "FN Switch" else t(labels[tab])) },
            navigationIcon = { IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Palette, t(R.string.settings)) } },
            actions = { if (state.snapshot != null) IconButton(onClick = vm::refresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, t(R.string.refresh)) } })
    }, bottomBar = {
        if (state.snapshot != null) NavigationBar { labels.forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], null) }, label = { Text(t(title)) }) } }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val s = state.snapshot
            if (s == null) ConnectionScreen(vm.address, { vm.address = it }, vm.username, { vm.username = it }, vm.passwordInput, { vm.passwordInput = it }, state.busy, vm::connect)
            else Column {
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(t(R.string.progress), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall) }
                Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (tab) {
                        0 -> OverviewScreen(s, state.lastRefresh) { if (!state.busy) port = it }
                        1 -> PortsScreen(s, !state.busy, { port = it }, vm::loadPortVlan)
                        2 -> VlansScreen(s, !state.busy, { createVlan = true }, vm::loadVlan, { rename = it }, { remove = it })
                        3 -> ToolsScreen(s, !state.busy, { backup = true }, { pendingImport = it; importLauncher.launch(arrayOf("*/*")) }, { webPath = it })
                        4 -> SystemScreen(s, vm.address, !state.busy, { ntp = true }, { save = true }, { logout = true }, { about = true }, { settings = true }, { webPath = "" })
                    }
                }
            }
        }
    }
    state.certificate?.let { CertificateDialog(vm.address, it, state.changedCertificate, vm::trustCertificate, vm::dismissCertificate) }
    port?.let { selected -> PortDialog(selected, { port = null }) { enabled, desc -> port = null; vm.updatePort(selected, enabled, desc) } }
    if (settings) SettingsDialog(vm.appearance) { settings = false }
    if (ntp && state.snapshot != null) TextEditDialog(t(R.string.time_sync), t(R.string.ntp_server), state.snapshot.ntp, t(R.string.ntp_note), { ntp = false }) { ntp = false; vm.updateNtp(it) }
    if (save) ConfirmDialog(t(R.string.save_config), t(R.string.save_note), t(R.string.confirm), { save = false }) { save = false; vm.saveConfiguration() }
    if (logout) ConfirmDialog(t(R.string.logout), t(R.string.logout_note), t(R.string.logout), { logout = false }) { logout = false; vm.logout(); tab = 0 }
    if (about) AlertDialog(onDismissRequest = { about = false }, icon = { Icon(Icons.Outlined.Hub, null) }, title = { Text("FN Switch ${BuildConfig.VERSION_NAME}") }, text = { Text(t(R.string.about_note)) }, confirmButton = { TextButton(onClick = { about = false }) { Text(t(R.string.close)) } }, dismissButton = { TextButton(onClick = { about = false; licenses = true }) { Text(t(R.string.licenses)) } })
    if (licenses) {
        val notice = remember { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(t(R.string.licenses)) }, text = { Text(notice, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton(onClick = { licenses = false }) { Text(t(R.string.close)) } })
    }
    if (createVlan) TextEditDialog(t(R.string.new_vlan), t(R.string.vlan_id), "", t(R.string.vlan_warning), { createVlan = false }, numeric = true) { value -> value.toIntOrNull()?.let { createVlan = false; vm.createVlan(it) } ?: vm.message(R.string.error_parameter) }
    rename?.let { selected -> TextEditDialog("VLAN ${selected.id}", t(R.string.vlan_name), selected.name, t(R.string.vlan_warning), { rename = null }) { rename = null; vm.renameVlan(selected.id, it) } }
    remove?.let { selected -> ConfirmDialog(t(R.string.delete_vlan) + " ${selected.id}", t(R.string.vlan_warning), t(R.string.confirm), { remove = null }) { remove = null; vm.deleteVlan(selected.id) } }
    state.vlan?.let { VlanMembersDialog(it, vm::dismissEditors) { values -> vm.updateVlan(it, values) } }
    state.portVlan?.let { PortVlanDialog(it, vm::dismissEditors) { mode, pvid -> vm.updatePortVlan(it, mode, pvid) } }
    if (backup) AlertDialog(onDismissRequest = { backup = false }, title = { Text(t(R.string.backup)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(t(R.string.backup_note)); BackupKind.entries.forEach { kind -> OutlinedButton(onClick = { backup = false; vm.backup(kind) }, Modifier.fillMaxWidth()) { Text(t(kind.title)) } } } }, confirmButton = { TextButton(onClick = { backup = false }) { Text(t(R.string.cancel)) } })
    state.importFile?.let { ImportDialog(it, vm::dismissEditors, vm::upload) }
}
@Composable fun ConnectionScreen(address: String, onAddress: (String) -> Unit, username: String, onUsername: (String) -> Unit, password: String, onPassword: (String) -> Unit, busy: Boolean, connect: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(22.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(26.dp), modifier = Modifier.size(80.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Hub, null, Modifier.size(40.dp)) } }
        Text(t(R.string.connect_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
        Text("FN SWITCH 8 PRO", style = MaterialTheme.typography.titleMedium)
        Text(t(R.string.connect_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(address, onAddress, label = { Text(t(R.string.address)) }, supportingText = { Text(t(R.string.address_hint)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), leadingIcon = { Icon(Icons.Outlined.Router, null) })
        OutlinedTextField(username, onUsername, label = { Text(t(R.string.username)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) })
        OutlinedTextField(password, onPassword, label = { Text(t(R.string.password)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Lock, null) }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, t(if (visible) R.string.hide_password else R.string.show_password)) } })
        Button(connect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.EnhancedEncryption, null); Spacer(Modifier.width(10.dp)); Text(t(if (busy) R.string.connecting else R.string.secure_connect)) }
        Text(t(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}
@Composable fun OverviewScreen(s: Snapshot, refreshed: Long, onPort: (Port) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.VerifiedUser, null); Text(t(R.string.connected), style = MaterialTheme.typography.labelLarge) }
            Text(s.model, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Text(s.ip, fontFamily = FontFamily.Monospace)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Metric(t(R.string.online_ports), "${s.ports.count { it.connected }} / ${s.ports.size}", Modifier.weight(1f)); val up = s.uptimeSeconds; Metric(t(R.string.uptime), if (up == null) "—" else t(R.string.uptime_value, up / 86400, up / 3600 % 24, up / 60 % 60), Modifier.weight(2f)) }
        }
    }
    SectionTitle(t(R.string.port_overview), t(R.string.physical_ports)); PortGrid(s.ports, onPort)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { UsageCard(t(R.string.cpu), s.cpu, Icons.Outlined.Memory, Modifier.weight(1f)); UsageCard(t(R.string.memory), s.memory, Icons.Outlined.Storage, Modifier.weight(1f)) }
    SectionTitle(t(R.string.hardware))
    InfoCard { InfoRow(t(if (s.hardware.cpuFromProfile) R.string.reference_cpu else R.string.cpu_model), s.hardware.cpuModel ?: t(R.string.not_reported)); InfoRow(t(R.string.cpu_cores), if (s.hardware.coresFromProfile) t(R.string.core_spec) else s.hardware.cores?.toString() ?: t(R.string.not_reported)); InfoRow(t(if (s.hardware.memoryFromProfile) R.string.reference_memory else R.string.memory_total), s.hardware.memoryBytes?.let { formatBytes(it) } ?: t(R.string.not_reported)); if (s.hardware.boardProfile) { InfoRow(t(R.string.cpu_spec), t(R.string.cpu_max)); InfoRow(t(R.string.memory_chip), "ESMT M15T2G16128A × 1 · DDR3(L)"); Text(t(R.string.board_source), style = MaterialTheme.typography.bodySmall) } else Text(t(R.string.hardware_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.firmware == "V1.1.8-20261027-021") InfoRow(t(R.string.firmware_platform), "Linux 3.18.24 · MIPS32 Release 2") }
    InfoCard { InfoRow(t(R.string.firmware), s.firmware); InfoRow(t(R.string.device_time), s.time.trim()) }
    if (refreshed > 0) Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(refreshed), style = MaterialTheme.typography.labelSmall)
}
@Composable fun Metric(label: String, value: String, modifier: Modifier = Modifier) { Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleMedium) } }
@Composable fun PortGrid(ports: List<Port>, onPort: (Port) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ports.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        row.forEach { p -> Surface(onClick = { onPort(p) }, color = if (p.connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { Icon(if (p.fiber) Icons.Outlined.Cable else Icons.Outlined.SettingsEthernet, null); Text("${p.number}", style = MaterialTheme.typography.titleMedium); Text(if (!p.enabled) t(R.string.disabled) else if (p.connected) formatSpeed(p.speed) else t(R.string.not_connected), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        } }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
    } } }
}
@Composable fun UsageCard(label: String, usage: Int, icon: ImageVector, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null); Text(label) }; Text("$usage%", style = MaterialTheme.typography.headlineMedium); LinearProgressIndicator(progress = { usage / 100f }, modifier = Modifier.fillMaxWidth()) } } }
@Composable fun PortsScreen(s: Snapshot, enabled: Boolean, onPort: (Port) -> Unit, onVlan: (Int) -> Unit = {}) {
    s.ports.forEach { p -> InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(t(R.string.port_number, p.number), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text(if (p.connected) formatSpeed(p.speed) else if (p.enabled) t(R.string.not_connected) else t(R.string.disabled)) }
        if (p.description.isNotBlank()) Text(p.description)
        Text("PVID ${p.pvid}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton({ onPort(p) }, enabled = enabled) { Text(t(R.string.apply)) }; TextButton({ onVlan(p.number) }, enabled = enabled) { Text(t(R.string.port_vlan)) } }
    } }
}
@Composable fun VlansScreen(s: Snapshot, enabled: Boolean, create: () -> Unit, members: (Int) -> Unit, rename: (Vlan) -> Unit, remove: (Vlan) -> Unit) {
    FilledTonalButton(create, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(t(R.string.new_vlan)) }
    Text(t(R.string.vlan_warning), style = MaterialTheme.typography.bodySmall)
    s.vlans.forEach { vlan -> InfoCard {
        Text("VLAN ${vlan.id} · ${vlan.name}", style = MaterialTheme.typography.titleMedium)
        Text("PVID: " + s.ports.filter { it.pvid == vlan.id }.joinToString { it.number.toString() }, style = MaterialTheme.typography.bodySmall)
        TextButton({ members(vlan.id) }, enabled = enabled) { Text(t(R.string.vlan_members)) }
        Row { TextButton({ rename(vlan) }, enabled = enabled) { Text(t(R.string.vlan_name)) }; if (vlan.id != 1) TextButton({ remove(vlan) }, enabled = enabled) { Text(t(R.string.delete_vlan)) } }
    } }
}
@Composable fun ToolsScreen(s: Snapshot, enabled: Boolean, backup: () -> Unit, importer: (ImportKind) -> Unit, web: (String) -> Unit) {
    SectionTitle(t(R.string.native_features))
    ActionRow(Icons.Outlined.FileDownload, t(R.string.backup), t(R.string.backup_note), enabled, backup)
    ActionRow(Icons.Outlined.Restore, t(R.string.restore), t(R.string.backup_startup), enabled) { importer(ImportKind.STARTUP) }
    ActionRow(Icons.Outlined.SystemUpdate, t(R.string.upgrade), "ST3208F · .fmw", enabled) { importer(ImportKind.FIRMWARE) }
    ActionRow(Icons.Outlined.Language, t(R.string.full_web), t(R.string.full_web_note), enabled) { web("") }
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(t(R.string.search_features)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
    s.features.filter { featureTitle(it.key).contains(query, true) || it.path.contains(query, true) }.forEach { feature -> ActionRow(Icons.Outlined.ChevronRight, featureTitle(feature.key), featureTitle(feature.group), enabled) { web(feature.path) } }
}
@Composable fun SystemScreen(s: Snapshot, address: String, enabled: Boolean, ntp: () -> Unit, save: () -> Unit, logout: () -> Unit, about: () -> Unit, settings: () -> Unit, web: () -> Unit) {
    SectionTitle(t(R.string.device_info)); InfoCard { InfoRow(t(R.string.model), s.model); InfoRow(t(R.string.hostname), s.hostname); InfoRow(t(R.string.address), address); InfoRow(t(R.string.mac_address), s.mac); InfoRow(t(R.string.firmware), s.firmware) }
    SectionTitle(t(R.string.time_config)); ActionRow(Icons.Outlined.Schedule, t(R.string.time_sync), s.ntp, enabled, ntp); ActionRow(Icons.Outlined.Save, t(R.string.save_config), t(R.string.save_note), enabled, save)
    ActionRow(Icons.Outlined.Language, t(R.string.full_web), t(R.string.full_web_note), enabled, web)
    SectionTitle(t(R.string.application)); ActionRow(Icons.Outlined.Palette, t(R.string.settings), "Material 3", true, settings); ActionRow(Icons.Outlined.Info, t(R.string.about), BuildConfig.VERSION_NAME, true, about); ActionRow(Icons.AutoMirrored.Outlined.Logout, t(R.string.logout), t(R.string.logout_note), enabled, logout)
}
@Composable fun ActionRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) { Card(onClick, enabled = enabled, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable fun SectionTitle(title: String, subtitle: String? = null) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } }
@Composable fun InfoCard(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
@Composable fun InfoRow(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value.ifBlank { "—" }, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium) } }
fun formatSpeed(speed: String): String = when (speed.uppercase()) { "10000M", "10G" -> "10 Gb/s"; "2500M" -> "2.5 Gb/s"; "5000M" -> "5 Gb/s"; "1000M", "1G" -> "1 Gb/s"; else -> if (speed.endsWith("M")) speed.dropLast(1) + " Mb/s" else speed }
fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024 * 1024L) "%.2f GiB".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024)) else "%.1f MiB".format(Locale.ROOT, bytes / (1024.0 * 1024))
private object FeatureNames {
    private var cached: org.json.JSONObject? = null
    @Synchronized fun title(context: android.content.Context, key: String, language: String): String? {
        if (cached == null) cached = context.assets.open("feature-names.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        return cached?.optJSONObject(key)?.optString(if (language == "zh") "zh" else "en")?.takeIf { it.isNotBlank() }
    }
}
@Composable fun featureTitle(key: String): String {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    return FeatureNames.title(context, key, language) ?: key.replace(Regex("^(doc|fld)[12]"), "").replace(Regex("([a-z])([A-Z])"), "$1 $2")
}
