package local.fnswitch.manager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

fun validFirmware(): ByteArray {
    val bytes = ByteArray(512) { 0x41 }; val h = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    h.putInt(0, 0x93000000.toInt()); h.putInt(4, 0); h.putInt(12, bytes.size - 64)
    h.putInt(24, CRC32().apply { update(bytes, 64, bytes.size - 64) }.value.toInt())
    h.putInt(4, CRC32().apply { update(bytes, 0, 64) }.value.toInt()); return bytes
}
class AdvancedModelsTest {
    @Test fun firmwareCorruptionWrongFamilyAndWrongLengthAreRejected() {
        assertEquals(64, FileValidation.inspect("firmware.fmw", validFirmware(), ImportKind.FIRMWARE).sha256.length)
        val corrupt = validFirmware().also { it[90] = 0 }
        assertThrows(SwitchFailure::class.java) { FileValidation.inspect("firmware.fmw", corrupt, ImportKind.FIRMWARE) }
        assertThrows(SwitchFailure::class.java) { FileValidation.inspect("firmware.fmw", validFirmware().copyOf(500), ImportKind.FIRMWARE) }
        assertThrows(SwitchFailure::class.java) { FileValidation.inspect("firmware.apk", validFirmware(), ImportKind.FIRMWARE) }
        assertThrows(SwitchFailure::class.java) { FileValidation.inspect("../firmware.fmw", validFirmware(), ImportKind.FIRMWARE) }
    }
    @Test fun percentUtilizationNeverBecomesCoreCountOrMemorySize() {
        assertEquals(HardwareInfo(), HardwareInfo.parse(JSONObject(), JSONObject("""{"cpu":9,"mem":30}""")))
        val values = HardwareInfo.parse(JSONObject("""{"cpuModel":"test CPU","cpuCores":4,"memTotalKb":262144}"""), JSONObject())
        assertEquals(4, values.cores); assertEquals(256L*1024*1024, values.memoryBytes)
    }
    @Test fun referenceProfileAppliesOnlyToSupportedModelAndLiveFieldsTakePrecedence() {
        val info = JSONObject("""{"model":"FN SWITCH 8 PRO","sysMac":"02:00:00:00:00:01"}""")
        val known = HardwareInfo.parse(info, JSONObject())
        assertTrue(known.boardProfile); assertEquals(256L*1024*1024, known.memoryBytes); assertEquals("Realtek RTL9303 · MIPS 34Kc", known.cpuModel)
        val live = HardwareInfo.parse(info.put("cpuModel", "reported CPU").put("cpuCores", 2).put("totalMemoryBytes", 200000000), JSONObject())
        assertEquals("reported CPU", live.cpuModel); assertEquals(2, live.cores); assertEquals(200000000L, live.memoryBytes); assertFalse(live.coresFromProfile)
        assertEquals(HardwareInfo(), HardwareInfo.parse(JSONObject("""{"model":"Other model","sysMac":"02:00:00:00:00:02"}"""), JSONObject()))
    }
    @Test fun consoleIsIsolatedFromOtherDevicesFilesAndWebsites() {
        assertEquals("/cgi/get.cgi?cmd=sys_sysinfo", ConsolePolicy.path("https://switch.invalid/cgi/get.cgi?cmd=sys_sysinfo"))
        listOf("http://switch.invalid/", "https://switch.invalid.evil/", "https://user@switch.invalid/", "file:///etc/passwd", "https://switch.invalid:8443/", "https://192.168.1.199/").forEach { assertThrows(SwitchFailure::class.java) { ConsolePolicy.path(it) } }
        val html = ConsolePolicy.inject("<html><HEAD><script src='one.js'></script></HEAD></html>", "html/vlan_vlan_create.html")
        assertTrue(html.indexOf("/__fn/bridge.js") < html.indexOf("one.js"))
    }
    @Test fun deviceMenuIsDynamicAndDuplicatePathsAreCollapsed() {
        val home = JSONObject("""{"menuTree":[{"name":"network","leaves":[{"name":"route","url":"html/routes.html"},{"name":"duplicate","url":"html/routes.html"}]}]}""")
        assertEquals(listOf(WebFeature("route", "html/routes.html", "network")), webFeatures(home))
    }
}
