package local.fnswitch.manager

import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

enum class Failure(val resource: Int) {
    LOGIN(R.string.error_login), SESSION_LIMIT(R.string.error_session_limit), SESSION(R.string.error_session), DEVICE(R.string.error_device),
    REJECTED(R.string.error_rejected), PARAMETER(R.string.error_parameter), FILE(R.string.error_file),
    UNCONFIRMED(R.string.operation_unconfirmed), DST(R.string.error_dst)
}
class SwitchFailure(val failure: Failure) : IOException(failure.name)

data class HardwareInfo(val cpuModel: String? = null, val cores: Int? = null, val memoryBytes: Long? = null, val boardProfile: Boolean = false, val coresFromProfile: Boolean = false, val cpuFromProfile: Boolean = false, val memoryFromProfile: Boolean = false) {
    companion object {
        fun parse(info: JSONObject, usage: JSONObject): HardwareInfo {
            val objects = listOfNotNull(info, usage, info.optJSONObject("hardware"), usage.optJSONObject("hardware"))
            fun text(vararg keys: String) = objects.firstNotNullOfOrNull { o -> keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it is String && it.isNotBlank() && it != "null" }?.toString() } }
            fun positive(vararg keys: String): Long? = objects.firstNotNullOfOrNull { o -> keys.firstNotNullOfOrNull { k -> if (o.has(k)) o.optLong(k).takeIf { it > 0 } else null } }
            val bytes = positive("totalMemoryBytes", "memoryTotalBytes", "memTotalBytes")
                ?: positive("memTotalKb", "memTotalKB", "memoryTotalKiB")?.takeIf { it <= Long.MAX_VALUE / 1024 }?.times(1024)
            val matchedBoard = info.optString("model") == "FN SWITCH 8 PRO"
            return HardwareInfo(text("cpuModel", "processorModel", "cpuName") ?: if (matchedBoard) "Realtek RTL9303 · MIPS 34Kc" else null,
                positive("cpuCores", "cpuCoreCount", "processorCount")?.takeIf { it <= 256 }?.toInt() ?: if (matchedBoard) 1 else null,
                bytes ?: if (matchedBoard) 256L * 1024 * 1024 else null, matchedBoard, matchedBoard && positive("cpuCores", "cpuCoreCount", "processorCount") == null, matchedBoard && text("cpuModel", "processorModel", "cpuName") == null, matchedBoard && bytes == null)
        }
    }
}
data class WebFeature(val key: String, val path: String, val group: String)
fun webFeatures(home: JSONObject): List<WebFeature> {
    val results = mutableListOf<WebFeature>()
    fun walk(array: org.json.JSONArray?, group: String) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val n = array.getJSONObject(i)
            val key = n.optString("name")
            if (n.has("url")) results.add(WebFeature(key, n.getString("url"), group))
            walk(n.optJSONArray("leaves"), if (group.isBlank()) key else group)
        }
    }
    walk(home.optJSONArray("menuTree"), "")
    return results.distinctBy { it.path }
}
data class VlanPortState(val membership: Int, val forbidden: Boolean, val pvid: Boolean, val mode: Int)
data class VlanConfiguration(val id: Int, val ports: List<VlanPortState>)
data class PortVlanSettings(val port: Int, val mode: Int, val pvid: Int)
data class ExportFile(val name: String, val bytes: ByteArray)
enum class BackupKind(val wire: String, val title: Int, val extension: String) {
    RUNNING("running", R.string.backup_running, "cfg"), STARTUP("startup", R.string.backup_startup, "cfg"), FIRMWARE("img", R.string.backup_firmware, "fmw")
}
enum class ImportKind(val wire: String, val title: Int) { FIRMWARE("img", R.string.upgrade), STARTUP("startup", R.string.restore) }
data class ImportFile(val name: String, val bytes: ByteArray, val kind: ImportKind, val sha256: String)
object FileValidation {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun inspect(name: String, bytes: ByteArray, kind: ImportKind): ImportFile {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || name.any { it < ' ' || it == '/' || it == '\\' || it == '"' }) throw SwitchFailure(Failure.FILE)
        if (kind == ImportKind.FIRMWARE) {
            if (!name.endsWith(".fmw", true) || bytes.size < 65) throw SwitchFailure(Failure.FILE)
            val header = bytes.copyOfRange(0, 64)
            fun uint(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val storedHeaderCrc = uint(4)
            for (i in 4..7) header[i] = 0
            if (uint(0) != 0x93000000L || uint(12) != (bytes.size - 64).toLong() || CRC32().apply { update(header) }.value != storedHeaderCrc || CRC32().apply { update(bytes, 64, bytes.size - 64) }.value != uint(24)) throw SwitchFailure(Failure.FILE)
        } else {
            if (bytes.size > 2 * 1024 * 1024 || bytes.any { it == 0.toByte() } || !(name.endsWith(".cfg", true) || name.endsWith(".txt", true))) throw SwitchFailure(Failure.FILE)
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.startsWith("SYSTEM CONFIG FILE")) throw SwitchFailure(Failure.FILE)
        }
        return ImportFile(name, bytes, kind, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) })
    }
}
data class ProxyResponse(val status: Int, val reason: String, val headers: Map<String, String>, val bytes: ByteArray) {
    val contentType get() = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/octet-stream"
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; if (output.size() + count > limit) throw SwitchFailure(Failure.FILE); output.write(buffer, 0, count) }
    return output.toByteArray()
}
