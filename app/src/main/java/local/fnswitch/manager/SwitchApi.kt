package local.fnswitch.manager

import android.annotation.SuppressLint

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class DeviceAddress internal constructor(val host: String, val port: Int = 443) {
    val label get() = host + if (port == 443) "" else ":$port"
    val base get() = "https://$label/".toHttpUrl()
    companion object {
        fun parse(input: String): DeviceAddress {
            val text = input.trim().removePrefix("https://").trimEnd('/')
            require(!text.contains('/') && !text.contains('@') && !text.contains('?') && !text.contains('#')) { "请填写内网 IPv4 地址，可附加 HTTPS 端口" }
            val pieces = text.split(':')
            require(pieces.size in 1..2) { "地址格式不正确" }
            val octets = pieces[0].split('.')
            require(octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }) { "请填写内网 IPv4 地址" }
            val n = octets.map(String::toInt)
            require(n[0] == 10 || n[0] == 172 && n[1] in 16..31 || n[0] == 192 && n[1] == 168) { "仅支持局域网地址" }
            val port = if (pieces.size == 2) pieces[1].toIntOrNull() else 443
            require(port != null && port in 1..65535) { "HTTPS 端口应为 1–65535" }
            return DeviceAddress(n.joinToString("."), port)
        }
    }
}

data class DeviceCertificate(val fingerprint: String, val subject: String, val issuer: String, val expires: Date)
fun certificateFingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("Client certificates unsupported")
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("服务器未提供证书")
        cert.checkValidity()
        if (!MessageDigest.isEqual(certificateFingerprint(cert).toByteArray(), pin.toByteArray())) throw CertificateException("交换机证书发生变化，请重新确认设备身份")
    }
}

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
object CertificateProbe {
    // This handshake inspects the certificate only. No HTTP request, cookie or credential is sent.
    fun inspect(address: DeviceAddress): DeviceCertificate {
        val observer = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), SecureRandom()) }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(address.host, address.port), 8000)
            (context.socketFactory.createSocket(raw, address.host, address.port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 12000
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                val aeadRsa = listOf("TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_256_GCM_SHA384")
                socket.enabledCipherSuites = (socket.enabledCipherSuites.toList() + aeadRsa).distinct().filter { it in socket.supportedCipherSuites }.toTypedArray()
                socket.startHandshake()
                val cert = socket.session.peerCertificates.first() as X509Certificate
                cert.checkValidity()
                return DeviceCertificate(certificateFingerprint(cert), cert.subjectX500Principal.name, cert.issuerX500Principal.name, cert.notAfter)
            }
        } finally { raw.close() }
    }
}

class SwitchApi(val address: DeviceAddress, private val pin: String) : Closeable {
    private val cookies = mutableListOf<Cookie>()
    private val trust = PinnedTrustManager(pin)
    private val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(*(ConnectionSpec.MODERN_TLS.cipherSuites.orEmpty() + listOf(CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384)).distinct().toTypedArray())
            .build()))
        .hostnameVerifier { host, session -> host == address.host && runCatching { certificateFingerprint(session.peerCertificates.first() as X509Certificate) == pin }.getOrDefault(false) }
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) { synchronized(cookies) { incoming.forEach { c -> cookies.removeAll { it.name == c.name && it.path == c.path }; cookies.add(c) } } }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) { cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
        })
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private var username = ""
    private var password = charArrayOf()

    @Synchronized fun login(user: String, pass: CharArray) {
        require(user.isNotBlank() && pass.isNotEmpty()) { "请输入用户名和密码" }
        username = user
        password.fill('\u0000'); password = pass.copyOf()
        authenticate()
    }
    private fun authenticate() {
        rawGet("home_login")
        rawPost("home_loginAuth", mapOf("username" to username, "password" to String(password)), null)
        val status = rawGet("home_loginStatus").optJSONObject("data")
        if (status?.optString("status") != "ok") throw SwitchFailure(if (status?.optString("failReason")?.contains("errLoginSessionNum") == true) Failure.SESSION_LIMIT else Failure.LOGIN)
    }
    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("设备返回 HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return try { JSONObject(body) } catch (_: Exception) { throw SwitchFailure(Failure.DEVICE) }
        }
    }
    private fun rawGet(command: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = address.base.newBuilder().addPathSegments("cgi/get.cgi").addQueryParameter("cmd", command)
        params.forEach { (k, v) -> url.addQueryParameter(k, v) }
        return execute(Request.Builder().url(url.build()).header("Referer", address.base.resolve("home.html").toString()).build())
    }
    private fun rawPost(command: String, values: Map<String, String>, csrf: String?): JSONObject {
        val body = FormBody.Builder().add("_ds", "1")
        values.forEach { (k, v) -> body.add(k, v) }
        csrf?.let { body.add("_csrf", it) }
        body.add("_de", "1")
        return execute(Request.Builder().url(address.base.resolve("cgi/set.cgi?cmd=$command")!!).header("Referer", address.base.resolve("home.html").toString()).post(body.build()).build())
    }
    @Synchronized fun get(command: String, params: Map<String, String> = emptyMap()): JSONObject {
        var response = rawGet(command, params)
        if (response.optBoolean("logout")) {
            if (password.isEmpty()) throw SwitchFailure(Failure.SESSION)
            authenticate(); response = rawGet(command, params)
        }
        if (response.optBoolean("logout")) throw SwitchFailure(Failure.SESSION)
        return response.optJSONObject("data") ?: throw SwitchFailure(Failure.DEVICE)
    }
    @Synchronized private fun post(command: String, values: Map<String, String>): JSONObject {
        val csrf = get("home_main").optString("csrfToken")
        if (csrf.isBlank()) throw SwitchFailure(Failure.SESSION)
        val result = rawPost(command, values, csrf)
        if (result.optBoolean("logout")) throw SwitchFailure(Failure.SESSION)
        if (result.optString("status") in listOf("fail", "error")) throw SwitchFailure(Failure.REJECTED)
        if (result.optString("status") != "ok" && !((command == "file_savecfg" && result.optString("statusFile").isNotBlank() || command == "file_backup" && result.optString("filename").isNotBlank())))
            throw SwitchFailure(Failure.UNCONFIRMED)
        return result
    }
    @Synchronized fun snapshot(): Snapshot {
        val info = get("sys_sysinfo")
        val ports = get("port_port")
        val vlans = get("vlan_create")
        val memberships = get("vlan_port")
        val usage = get("sys_cpumem")
        val time = get("time_time")
        return Snapshot.parse(info, ports, vlans, memberships, usage, time).copy(features = webFeatures(get("home_main")))
    }
    @Synchronized fun updatePort(number: Int, enabled: Boolean, description: String) {
        require(number in 1..8 && description.length <= 32) { "端口参数不正确" }
        require(description.none { it < ' ' || it == '<' || it == '>' }) { "备注不能包含尖括号或控制字符" }
        val current = get("port_portEdit", mapOf("_src" to (number - 1).toString()))
        if (listOf("adminSpeed", "adminDuplex", "adminFlowCtrl").any { current.optString(it).isBlank() }) throw SwitchFailure(Failure.DEVICE)
        val fields = linkedMapOf("portList" to number.toString(), "descp" to description,
            "adminSpeed" to current.optString("adminSpeed", "auto"), "adminDuplex" to current.optString("adminDuplex", "auto"),
            "adminFlowCtrl" to current.optString("adminFlowCtrl", "disable"))
        if (enabled) fields["adminStatus"] = "on"
        post("port_portEdit", fields)
    }
    @Synchronized fun updateNtp(server: String) {
        require(server.length in 1..253 && server.matches(Regex("[A-Za-z0-9.-]+"))) { "请填写有效的 NTP 服务器域名或 IPv4 地址" }
        val original = get("time_time")
        if (original.optInt("dlsType", 0) != 0) throw SwitchFailure(Failure.DST)
        val fields = linkedMapOf("sntp" to "1", "srvDef" to if (server.matches(Regex("[0-9.]+"))) "ipv4" else "host",
            "srvHost" to server, "port" to original.optInt("port", 123).toString(), "timezone" to original.optInt("timezone", 480).toString(),
            "dlsType" to original.optInt("dlsType", 0).toString())
        listOf("dlsOffset", "dlsRecDayS", "dlsRecWeekS", "dlsRecMonthS", "dlsRecTimeS", "dlsRecDayE", "dlsRecWeekE", "dlsRecMonthE", "dlsRecTimeE").forEach { k -> if (original.has(k)) fields[k] = original.get(k).toString() }
        post("time_time", fields)
    }
    @Synchronized fun saveConfiguration() {
        val result = post("file_savecfg", mapOf("srcFile" to "running", "dstFile" to "startup"))
        val statusFile = result.optString("statusFile")
        if (statusFile.isNotBlank()) {
            repeat(12) {
                Thread.sleep(800)
                val status = get("file_saveStatus", mapOf("statusFile" to statusFile)).optString("status")
                if (status in listOf("success", "ok")) return
                if (status in listOf("fail", "error")) throw SwitchFailure(Failure.REJECTED)
            }
            throw SwitchFailure(Failure.UNCONFIRMED)
        }
        throw SwitchFailure(Failure.UNCONFIRMED)
    }
    @Synchronized fun createVlan(id: Int) {
        require(id in 2..4094)
        val state = get("vlan_create")
        val current = state.getJSONArray("adminVlans")
        val ids = (0 until current.length()).map { current.getJSONObject(it).getInt("vlan") }
        if (id in ids) throw SwitchFailure(Failure.PARAMETER)
        post("vlan_create", mapOf("vlanList" to (ids + id).sorted().joinToString(",")))
    }
    @Synchronized fun renameVlan(id: Int, name: String) {
        require(id in 1..4094 && name.length <= 32 && name.none { it < ' ' || it == '<' || it == '>' })
        post("vlan_edit", mapOf("vlan" to id.toString(), "name" to name))
    }
    @Synchronized fun deleteVlan(id: Int) {
        val state = get("vlan_create").getJSONArray("adminVlans")
        val entry = (0 until state.length()).map { state.getJSONObject(it) }.firstOrNull { it.optInt("vlan") == id }
        if (entry?.optInt("type") != 2) throw SwitchFailure(Failure.PARAMETER)
        post("vlan_del", mapOf("vlanList" to id.toString()))
    }
    @Synchronized fun vlanConfiguration(id: Int): VlanConfiguration {
        require(id in 1..4094)
        val state = get("vlan_conf", mapOf("vlan" to id.toString()))
        if (state.optInt("vlan") != id) throw SwitchFailure(Failure.DEVICE)
        val ports = state.getJSONArray("ports")
        return VlanConfiguration(id, (0 until ports.length()).map { ports.getJSONObject(it).let { p -> VlanPortState(p.getInt("membership"), p.getBoolean("forbidden"), p.getBoolean("pvid"), p.getInt("mode")) } })
    }
    @Synchronized fun updateVlanConfiguration(original: VlanConfiguration, visible: List<VlanPortState>) {
        require(visible.size == 8 && original.ports.size >= 8)
        if (vlanConfiguration(original.id) != original) throw SwitchFailure(Failure.REJECTED)
        val fields = linkedMapOf("vlan" to original.id.toString())
        original.ports.forEachIndexed { i, before ->
            val p = visible.getOrNull(i) ?: before // Keep every SDK LAG row not represented by a physical port.
            require(p.membership in listOf(0, 2, 3) && !(p.forbidden && (p.membership != 0 || before.pvid)))
            if ((before.mode == 1 || before.mode == 3) && p.membership != before.membership) throw SwitchFailure(Failure.PARAMETER)
            if (before.pvid && p.membership != 3) throw SwitchFailure(Failure.PARAMETER)
            if (original.id == 1 && before.mode == 2 && p.membership != 3) throw SwitchFailure(Failure.PARAMETER)
            fields["membership_$i"] = p.membership.toString()
            if (p.forbidden) fields["forbidden_$i"] = "on"
        }
        post("vlan_conf", fields)
    }
    @Synchronized fun portVlanSettings(number: Int): PortVlanSettings {
        require(number in 1..8)
        val state = get("vlan_portEdit", mapOf("_src" to (number - 1).toString()))
        return PortVlanSettings(number, state.getInt("mode"), state.getInt("pvid"))
    }
    @Synchronized fun updatePortVlan(original: PortVlanSettings, mode: Int, pvid: Int) {
        require(mode in 0..2 && pvid in 1..4094)
        if (portVlanSettings(original.port) != original) throw SwitchFailure(Failure.REJECTED)
        val vlans = get("vlan_create").getJSONArray("adminVlans")
        if ((0 until vlans.length()).none { vlans.getJSONObject(it).optInt("vlan") == pvid }) throw SwitchFailure(Failure.PARAMETER)
        val state = get("vlan_portEdit", mapOf("_src" to (original.port - 1).toString()))
        val fields = linkedMapOf("portList" to original.port.toString(), "mode" to mode.toString(), "pvid" to pvid.toString())
        // Match the vendor form: disabled controls are omitted for Access/Trunk modes.
        if (mode == 0) {
            fields["accFrameType"] = state.getInt("accFrameType").toString()
            if (state.getBoolean("ingressFilter")) fields["ingressFilter"] = "on"
        }
        if (mode == 2 && state.getBoolean("uplink")) {
            fields["uplink"] = "on"; fields["tpid"] = state.getString("tpid")
        }
        post("vlan_portEdit", fields)
    }
    @Synchronized fun backup(kind: BackupKind): ExportFile {
        val fields = linkedMapOf("method" to "http", "fileType" to kind.wire, (if (kind == BackupKind.FIRMWARE) "img_action" else "cfg_action") to "backup")
        if (kind == BackupKind.FIRMWARE) fields["image"] = "0"
        val result = post("file_backup", fields)
        val filename = result.optString("filename")
        if (filename.isBlank()) throw SwitchFailure(Failure.UNCONFIRMED)
        val response = proxy("GET", filename)
        if (response.status != 200 || response.bytes.isEmpty() || response.contentType.contains("text/html", true) || response.bytes.take(20).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("<")) throw SwitchFailure(Failure.FILE)
        return ExportFile("FN-Switch-${kind.wire}-${System.currentTimeMillis()}.${kind.extension}", response.bytes)
    }
    @Synchronized fun upload(file: ImportFile) {
        FileValidation.inspect(file.name, file.bytes, file.kind)
        val csrf = get("home_main").getString("csrfToken")
        val path = if (file.kind == ImportKind.FIRMWARE) "cgi-bin/httpupload.cgi" else "cgi/httprestorecfg.cgi"
        val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("fileType", file.kind.wire).addFormDataPart("method", "http")
            .addFormDataPart(if (file.kind == ImportKind.FIRMWARE) "img_action" else "cfg_action", "upgrade")
        if (file.kind == ImportKind.FIRMWARE) multipart.addFormDataPart("image", "0")
        multipart.addFormDataPart("fileName", file.name, file.bytes.toRequestBody("application/octet-stream".toMediaType()))
        val url = address.base.resolve(path)!!.newBuilder().addQueryParameter("_csrf", csrf).build()
        val request = Request.Builder().url(url).header("Referer", address.base.resolve("home.html").toString()).post(multipart.build()).build()
        client.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val expectedMarker = if (file.kind == ImportKind.FIRMWARE) "httpuploadimg" else "httprestorecfg"
            if (!response.isSuccessful || !body.contains(expectedMarker)) throw SwitchFailure(Failure.UNCONFIRMED)
        }
        repeat(60) {
            Thread.sleep(2000)
            val status = get("file_upStatus", mapOf("fileType" to file.kind.wire, "type" to "http")).optString("status")
            when (status) { "success" -> return; "fail", "error" -> throw SwitchFailure(Failure.REJECTED); "uploading" -> Unit; else -> throw SwitchFailure(Failure.UNCONFIRMED) }
        }
        throw SwitchFailure(Failure.UNCONFIRMED)
    }
    // Network gateway for the isolated in-app console. Never forwards requests to a second host.
    fun proxy(method: String, path: String, body: ByteArray? = null, contentType: String? = null): ProxyResponse {
        if (method !in listOf("GET", "POST") || (body?.size ?: 0) > FileValidation.MAX_BYTES + 1024 * 1024) throw SwitchFailure(Failure.PARAMETER)
        val url = address.base.resolve(path) ?: throw SwitchFailure(Failure.PARAMETER)
        if (url.scheme != "https" || url.host != address.host || url.port != address.port || url.username.isNotEmpty() || url.password.isNotEmpty()) throw SwitchFailure(Failure.PARAMETER)
        val request = Request.Builder().url(url).header("Referer", address.base.resolve("home.html").toString())
            .method(method, if (method == "POST") (body ?: byteArrayOf()).toRequestBody(contentType?.toMediaTypeOrNull()) else null).build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { it.readBytesLimited(FileValidation.MAX_BYTES + 1024 * 1024) } ?: byteArrayOf()
            return ProxyResponse(response.code, response.message.ifBlank { "Response" }, response.headers.names().associateWith { response.header(it).orEmpty() }, bytes)
        }
    }

    @Synchronized fun endSession() {
        try {
            val result = rawGet("home_main")
            val token = result.optJSONObject("data")?.optString("csrfToken").orEmpty()
            if (!result.optBoolean("logout") && token.isNotBlank()) rawPost("home_logout", mapOf("empty" to "1"), token)
        } finally { close() }
    }
    @Synchronized override fun close() { password.fill('\u0000'); password = charArrayOf(); username = ""; synchronized(cookies) { cookies.clear() }; client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}

data class Port(val number: Int, val description: String, val enabled: Boolean, val connected: Boolean, val speed: String, val fiber: Boolean, val pvid: Int)
data class Vlan(val id: Int, val name: String)
data class Snapshot(val model: String, val hostname: String, val firmware: String, val ip: String, val mac: String, val uptime: String,
                    val time: String, val cpu: Int, val memory: Int, val ports: List<Port>, val vlans: List<Vlan>, val ntp: String, val ntpEnabled: Boolean, val hardware: HardwareInfo = HardwareInfo(), val features: List<WebFeature> = emptyList(), val uptimeSeconds: Long? = null) {
    companion object {
        fun parse(info: JSONObject, ports: JSONObject, vlans: JSONObject, membership: JSONObject, usage: JSONObject, time: JSONObject): Snapshot {
            val list = ports.optJSONArray("ports") ?: throw SwitchFailure(Failure.DEVICE)
            val pvids = membership.optJSONArray("ports")
            val portList = (0 until list.length().coerceAtMost(8)).map { i ->
                val p = list.getJSONObject(i)
                Port(i + 1, p.optString("descp"), p.optBoolean("adminStatus"), p.optBoolean("operStatus"), p.optString("operSpeed", "—"), p.optString("type").contains("Fiber"), pvids?.optJSONObject(i)?.optInt("pvid", 1) ?: 1)
            }
            val v = vlans.optJSONArray("adminVlans")
            val vlanList = (0 until (v?.length() ?: 0)).map { val x = v!!.getJSONObject(it); Vlan(x.optInt("vlan"), x.optString("name")) }
            val up = info.optString("sysUpTime")
            val numbers = Regex("\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]").find(up)?.groupValues
            val uptimeSeconds = numbers?.let { it[1].toLong()*86400 + it[2].toLong()*3600 + it[3].toLong()*60 + it[4].toLong() }
            val uptime = if (numbers == null) "—" else "${numbers[1]} 天 ${numbers[2]} 小时 ${numbers[3]} 分钟"
            return Snapshot(info.optString("model"), info.optString("hostname"), info.optString("fwVer"), info.optString("currIpv4"), info.optString("sysMac"), uptime,
                time.optString("timeStr", info.optString("sysCurrTime")), usage.optInt("cpu").coerceIn(0, 100), usage.optInt("mem").coerceIn(0, 100), portList, vlanList, time.optString("srvHost"), time.optBoolean("sntp"), HardwareInfo.parse(info, usage), uptimeSeconds = uptimeSeconds)
        }
    }
}
