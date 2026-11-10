package local.fnswitch.manager

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

data class UiState(val snapshot: Snapshot? = null, val busy: Boolean = false, val message: Int? = null,
                   val certificate: DeviceCertificate? = null, val changedCertificate: Boolean = false, val lastRefresh: Long = 0L,
                   val export: ExportFile? = null, val importFile: ImportFile? = null, val vlan: VlanConfiguration? = null, val portVlan: PortVlanSettings? = null)
class SwitchViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("connection", 0)
    val appearance = AppPreferences(application)
    var address by mutableStateOf(preferences.getString("address", "192.168.1.199").orEmpty())
    var username by mutableStateOf(preferences.getString("username", "admin").orEmpty())
    var passwordInput by mutableStateOf("")
    var ui by mutableStateOf(UiState()); private set
    var api: SwitchApi? = null; private set
    private var pendingAddress: DeviceAddress? = null
    fun connect() {
        if (ui.busy) return
        val target = try { DeviceAddress.parse(address) } catch (_: IllegalArgumentException) { message(R.string.error_address); return }
        if (username.isBlank() || passwordInput.isEmpty()) { message(R.string.error_credentials); return }
        ui = ui.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val certificate = withContext(Dispatchers.IO) { CertificateProbe.inspect(target) }
                val pin = preferences.getString("pin.${target.label}", null)
                pendingAddress = target
                if (pin == certificate.fingerprint) finishConnection(target, certificate.fingerprint)
                else ui = ui.copy(busy = false, certificate = certificate, changedCertificate = pin != null)
            } catch (e: Exception) { ui = ui.copy(busy = false, message = friendlyError(e)) }
        }
    }
    fun trustCertificate() {
        val certificate = ui.certificate ?: return
        val target = pendingAddress ?: return
        ui = ui.copy(certificate = null, busy = true)
        viewModelScope.launch { finishConnection(target, certificate.fingerprint) }
    }
    fun dismissCertificate() { pendingAddress = null; ui = ui.copy(certificate = null, busy = false) }
    private suspend fun finishConnection(target: DeviceAddress, pin: String) {
        val connection = SwitchApi(target, pin)
        val password = passwordInput.toCharArray()
        try {
            val snapshot = withContext(Dispatchers.IO) { connection.login(username.trim(), password); connection.snapshot() }
            if (!snapshot.model.contains("FN SWITCH 8 PRO", true)) throw SwitchFailure(Failure.DEVICE)
            api?.close(); api = connection
            preferences.edit().putString("address", target.label).putString("username", username.trim()).putString("pin.${target.label}", pin).apply()
            address = target.label; passwordInput = ""
            ui = UiState(snapshot = snapshot, lastRefresh = System.currentTimeMillis())
        } catch (e: Exception) { connection.close(); ui = ui.copy(busy = false, message = friendlyError(e)) }
        finally { password.fill('\u0000'); pendingAddress = null }
    }
    fun refresh() = action(null) { }
    fun updatePort(port: Port, enabled: Boolean, description: String) = action(R.string.saved) { it.updatePort(port.number, enabled, description) }
    fun updateNtp(server: String) = action(R.string.saved) { it.updateNtp(server.trim()) }
    fun saveConfiguration() = action(R.string.save_success) { it.saveConfiguration() }
    fun createVlan(id: Int) = action(R.string.saved) { it.createVlan(id) }
    fun renameVlan(id: Int, name: String) = action(R.string.saved) { it.renameVlan(id, name) }
    fun deleteVlan(id: Int) = action(R.string.saved) { it.deleteVlan(id) }
    fun updateVlan(original: VlanConfiguration, ports: List<VlanPortState>) { ui = ui.copy(vlan = null); action(R.string.saved) { it.updateVlanConfiguration(original, ports) } }
    fun updatePortVlan(original: PortVlanSettings, mode: Int, pvid: Int) { ui = ui.copy(portVlan = null); action(R.string.saved) { it.updatePortVlan(original, mode, pvid) } }
    fun loadVlan(id: Int) = task({ it.vlanConfiguration(id) }) { ui = ui.copy(vlan = it) }
    fun loadPortVlan(port: Int) = task({ it.portVlanSettings(port) }) { ui = ui.copy(portVlan = it) }
    fun backup(kind: BackupKind) = task({ it.backup(kind) }) { ui = ui.copy(export = it) }
    fun offerExport(file: ExportFile) { ui = ui.copy(export = file) }
    fun prepareImport(name: String, bytes: ByteArray, kind: ImportKind) {
        if (ui.busy) return
        ui = ui.copy(busy = true)
        viewModelScope.launch {
            try { val file = withContext(Dispatchers.IO) { FileValidation.inspect(name, bytes, kind) }; ui = ui.copy(importFile = file, busy = false) }
            catch (e: Exception) { ui = ui.copy(busy = false, message = friendlyError(e)) }
        }
    }
    fun upload() {
        val file = ui.importFile ?: return
        ui = ui.copy(importFile = null)
        action(R.string.upload_success) { it.upload(file) }
    }
    // Results are posted on Main; IO never mutates Compose state.
    private fun <T> task(operation: (SwitchApi) -> T, deliver: (T) -> Unit) {
        if (ui.busy) return
        val connection = api ?: return
        ui = ui.copy(busy = true, message = null)
        viewModelScope.launch {
            try { val result = withContext(Dispatchers.IO) { operation(connection) }; deliver(result); ui = ui.copy(busy = false) }
            catch (e: Exception) { ui = ui.copy(busy = false, message = friendlyError(e)) }
        }
    }
    private fun action(success: Int?, block: (SwitchApi) -> Unit) {
        if (ui.busy) return
        val connection = api ?: return
        ui = ui.copy(busy = true, message = null)
        viewModelScope.launch {
            var changed = false
            try {
                val snapshot = withContext(Dispatchers.IO) { block(connection); changed = success != null; connection.snapshot() }
                ui = ui.copy(snapshot = snapshot, busy = false, message = success, lastRefresh = System.currentTimeMillis())
            } catch (e: Exception) { ui = ui.copy(busy = false, message = if (changed) R.string.operation_unconfirmed else friendlyError(e)) }
        }
    }
    fun consumeExport() { ui.export?.bytes?.fill(0); ui = ui.copy(export = null) }
    fun dismissEditors() { ui = ui.copy(importFile = null, vlan = null, portVlan = null) }
    fun logout() {
        if (ui.busy) return
        val previous = api; api = null; passwordInput = ""; ui = UiState()
        if (previous != null) viewModelScope.launch(Dispatchers.IO) { runCatching { previous.endSession() } }
    }
    fun message(message: Int) { ui = ui.copy(message = message) }
    fun clearMessage() { ui = ui.copy(message = null) }
    override fun onCleared() { api?.close(); passwordInput = ""; super.onCleared() }
}
fun friendlyError(e: Throwable): Int {
    if (e is SwitchFailure) return e.failure.resource
    if (e is SocketTimeoutException || generateSequence(e.cause) { it.cause }.any { it is SocketTimeoutException }) return R.string.error_timeout
    if (e is CertificateException || e is SSLException || generateSequence(e.cause) { it.cause }.any { it is CertificateException }) return R.string.error_certificate
    return when (e) {
        is java.net.ConnectException, is java.net.NoRouteToHostException -> R.string.error_connection
        is IllegalArgumentException -> R.string.error_parameter
        else -> R.string.error_generic
    }
}
