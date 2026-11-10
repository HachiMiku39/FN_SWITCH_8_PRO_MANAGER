package local.fnswitch.manager

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.security.Security

class SwitchApiTest {
    companion object {
        @JvmStatic @BeforeClass fun modelAndroidCipherSupport() {
            // Android supports RSA/AES-GCM. Recent desktop JDKs disable RSA key exchange by default.
            // This changes only the disposable test JVM, never the installed JDK or Android app.
            val disabled = Security.getProperty("jdk.tls.disabledAlgorithms")
            Security.setProperty("jdk.tls.disabledAlgorithms", disabled.split(',').filterNot { it.trim().startsWith("TLS_RSA_") }.joinToString(","))
        }
    }
    private lateinit var server: MockWebServer
    private lateinit var cert: HeldCertificate
    private lateinit var api: SwitchApi
    @Before fun setup() {
        cert = HeldCertificate.Builder().commonName("test switch").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        api = SwitchApi(DeviceAddress("127.0.0.1", server.port), certificateFingerprint(cert.certificate))
    }
    @After fun cleanup() { api.close(); server.shutdown() }
    private fun json(body: String, cookie: String? = null) {
        val response = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
        cookie?.let { response.setHeader("Set-Cookie", it) }; server.enqueue(response)
    }
    private fun loginResponses() {
        json("""{"data":{}}""")
        json("""{"status":"ok"}""", "SID=fixture-session; Path=/; Secure; HttpOnly")
        json("""{"data":{"status":"ok"}}""")
    }
    @Test fun onlyPrivateIpv4TargetsAccepted() {
        assertEquals(DeviceAddress("192.168.1.199"), DeviceAddress.parse("https://192.168.1.199/"))
        assertEquals(8443, DeviceAddress.parse("10.2.3.4:8443").port)
        listOf("8.8.8.8", "127.0.0.1", "172.32.1.1", "192.168.1.999", "192.168.1.1@evil.com", "192.168.1.1/path", "http://192.168.1.199", "192.168.1.1:0").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { DeviceAddress.parse(value) }
        }
    }
    @Test fun pinnedSelfSignedCertificateWorksWithoutGlobalTrust() {
        json("""{"data":{"model":"FN SWITCH 8 PRO"}}""")
        assertEquals("FN SWITCH 8 PRO", api.get("sys_sysinfo").getString("model"))
    }
    @Test fun changedCertificateRejectedBeforeHttpRequest() {
        val wrong = SwitchApi(DeviceAddress("127.0.0.1", server.port), "00".repeat(32))
        try { assertThrows(IOException::class.java) { wrong.get("sys_sysinfo") }; assertEquals(0, server.requestCount) } finally { wrong.close() }
    }
    @Test fun portChangeRetainsNegotiationSettingsAndUsesCsrf() {
        loginResponses()
        json("""{"data":{"adminSpeed":"2500","adminDuplex":"full","adminFlowCtrl":"auto"}}""")
        json("""{"data":{"csrfToken":"fixture-csrf"}}""")
        json("""{"status":"ok"}""")
        api.login("fixture-user", "fixture-password".toCharArray())
        api.updatePort(2, false, "桌面机 & NAS")
        val requests = (1..6).map { server.takeRequest() }
        assertTrue(requests[3].path!!.contains("_src=1"))
        assertEquals("SID=fixture-session", requests[5].getHeader("Cookie"))
        val body = URLDecoder.decode(requests[5].body.readUtf8(), "UTF-8")
        assertTrue(body.contains("portList=2")); assertTrue(body.contains("descp=桌面机 & NAS"))
        assertTrue(body.contains("adminSpeed=2500")); assertTrue(body.contains("adminDuplex=full")); assertTrue(body.contains("adminFlowCtrl=auto"))
        assertTrue(body.contains("_csrf=fixture-csrf")); assertFalse(body.contains("adminStatus"))
    }
    @Test fun rejectedMutationIsNotReplayed() {
        loginResponses()
        json("""{"data":{"csrfToken":"fixture-csrf"}}""")
        json("""{"logout":true}""")
        api.login("fixture-user", "fixture-password".toCharArray())
        assertThrows(IOException::class.java) { api.saveConfiguration() }
        assertEquals(5, server.requestCount)
    }
    @Test fun unconfirmedPortResponseDoesNotReportSuccessOrReplay() {
        loginResponses()
        json("""{"data":{"adminSpeed":"auto","adminDuplex":"auto","adminFlowCtrl":"disable"}}""")
        json("""{"data":{"csrfToken":"fixture-csrf"}}""")
        json("""{"status":"warning"}""")
        api.login("fixture-user", "fixture-password".toCharArray())
        assertThrows(IOException::class.java) { api.updatePort(2, true, "test") }
        assertEquals(6, server.requestCount)
    }
    @Test fun loginFailureDoesNotProceedToConfiguration() {
        json("""{"data":{}}"""); json("""{"status":"ok"}"""); json("""{"data":{"status":"fail"}}""")
        assertThrows(IOException::class.java) { api.login("wrong", "wrong".toCharArray()) }
        assertEquals(3, server.requestCount)
    }
    @Test fun fixtureParserUsesEightPhysicalPortsNotSixteenMembershipRows() {
        val snapshot = fixtureSnapshot()
        assertEquals(8, snapshot.ports.size); assertEquals(1, snapshot.vlans.single().id)
        assertEquals("10G", snapshot.ports[2].speed); assertTrue(snapshot.ports[6].fiber)
        assertEquals("4 天 23 小时 12 分钟", snapshot.uptime)
    }
    @Test fun creatingVlanPreservesEveryExistingId() {
        json("""{"data":{"adminVlans":[{"vlan":1},{"vlan":20},{"vlan":30}]}}""")
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"status":"ok"}""")
        api.createVlan(200)
        server.takeRequest(); server.takeRequest()
        assertTrue(URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8").contains("vlanList=1,20,30,200"))
    }
    @Test fun defaultVlanCannotBeDeleted() {
        json("""{"data":{"adminVlans":[{"vlan":1,"type":1}]}}""")
        assertThrows(SwitchFailure::class.java) { api.deleteVlan(1) }; assertEquals(1, server.requestCount)
    }
    private fun configurationJson(original: VlanConfiguration): String {
        val ports = org.json.JSONArray()
        original.ports.forEach { ports.put(JSONObject().put("mode", it.mode).put("membership", it.membership).put("forbidden", it.forbidden).put("pvid", it.pvid)) }
        return JSONObject().put("data", JSONObject().put("vlan", original.id).put("ports", ports)).toString()
    }
    @Test fun vlanMemberUpdatePreservesHiddenLagRowsAndChecksForConcurrentEdits() {
        val original = VlanConfiguration(20, (0 until 16).map { VlanPortState(if (it == 8) 2 else 3, false, false, 0) })
        json(configurationJson(original)); json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"status":"ok"}""")
        api.updateVlanConfiguration(original, original.ports.take(8).toMutableList().also { it[0] = it[0].copy(membership = 2) })
        server.takeRequest(); server.takeRequest(); val body = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body.contains("membership_0=2")); assertTrue(body.contains("membership_8=2")); assertTrue(body.contains("membership_15=3"))
        val different = original.copy(ports = original.ports.toMutableList().also { it[1] = it[1].copy(mode = 2) })
        json(configurationJson(different))
        assertThrows(SwitchFailure::class.java) { api.updateVlanConfiguration(original, original.ports.take(8)) }; assertEquals(4, server.requestCount)
    }
    @Test fun currentPvidCannotBeExcludedFromVlan() {
        val original = VlanConfiguration(20, List(16) { VlanPortState(3, false, true, 2) })
        json(configurationJson(original))
        assertThrows(SwitchFailure::class.java) { api.updateVlanConfiguration(original, original.ports.take(8).toMutableList().also { it[0] = it[0].copy(membership = 0) }) }
        assertEquals(1, server.requestCount)
    }
    @Test fun portVlanUpdateMatchesVendorDisabledControlRules() {
        val current = """{"data":{"mode":2,"pvid":1,"accFrameType":1,"ingressFilter":true,"uplink":false,"tpid":"0x88a8"}}"""
        json(current); json("""{"data":{"adminVlans":[{"vlan":1},{"vlan":20}]}}"""); json(current)
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"status":"ok"}""")
        api.updatePortVlan(PortVlanSettings(2,2,1), 0,20)
        repeat(4) { server.takeRequest() }; val body = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body.contains("portList=2")); assertTrue(body.contains("pvid=20")); assertTrue(body.contains("accFrameType=1")); assertTrue(body.contains("ingressFilter=on")); assertFalse(body.contains("tpid=")); assertFalse(body.contains("uplink="))
    }
    @Test fun backupDownloadsFromSamePinnedDeviceAndRejectsSecondHost() {
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"filename":"/backups/config.cfg"}"""); json("! configuration\nhostname test\n")
        assertTrue(api.backup(BackupKind.RUNNING).bytes.toString(Charsets.UTF_8).contains("hostname test"))
        repeat(3) { server.takeRequest() }
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"filename":"https://example.com/steal"}""")
        assertThrows(SwitchFailure::class.java) { api.backup(BackupKind.STARTUP) }; assertEquals(5, server.requestCount)
    }
    @Test fun validFirmwareUploadUsesNativeMultipartAndWaitsForDeviceResult() {
        json("""{"data":{"csrfToken":"fixture-csrf"}}""")
        server.enqueue(MockResponse().setBody("<script>location='?httpuploadimg=1'</script>").setHeader("Content-Type", "text/html"))
        json("""{"data":{"status":"success"}}""")
        val file = FileValidation.inspect("test.fmw", validFirmware(), ImportKind.FIRMWARE)
        api.upload(file)
        server.takeRequest(); val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/cgi-bin/httpupload.cgi?_csrf=fixture-csrf"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8(); assertTrue(body.contains("name=\"fileName\"; filename=\"test.fmw\"")); assertTrue(body.contains("name=\"img_action\"")); assertTrue(body.contains("upgrade"))
        assertEquals(3, server.requestCount)
    }
    @Test fun failedUploadIsNotRetriedOrReportedAsSuccess() {
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); server.enqueue(MockResponse().setBody("error"))
        val file = FileValidation.inspect("test.fmw", validFirmware(), ImportKind.FIRMWARE)
        assertThrows(SwitchFailure::class.java) { api.upload(file) }; assertEquals(2, server.requestCount)
    }

    @Test fun normalDisconnectReleasesOnlyItsOwnAuthenticatedSession() {
        json("""{"data":{"csrfToken":"fixture-csrf"}}"""); json("""{"status":"ok"}""")
        api.endSession()
        server.takeRequest(); val logout = server.takeRequest()
        assertEquals("/cgi/set.cgi?cmd=home_logout", logout.path)
        assertTrue(logout.body.readUtf8().contains("_csrf=fixture-csrf"))
    }
    @Test fun exhaustedSessionCapacityHasSpecificErrorWithoutRetrying() {
        json("""{"data":{}}"""); json("""{"status":"ok"}"""); json("""{"data":{"status":"fail","failReason":"lang('login','errLoginSessionNum')"}}""")
        val failure = assertThrows(SwitchFailure::class.java) { api.login("test", "test".toCharArray()) }
        assertEquals(Failure.SESSION_LIMIT, failure.failure); assertEquals(3, server.requestCount)
    }
    @Test fun actualSwitchReadOnlySmokeTest() {
        val host = System.getenv("FN_SWITCH_TEST_HOST")
        val password = System.getenv("FN_SWITCH_TEST_PASSWORD")
        val expectedPin = System.getenv("FN_SWITCH_TEST_PIN")
        assumeNotNull(host, password, expectedPin)
        val target = DeviceAddress.parse(host!!)
        assertEquals(expectedPin, CertificateProbe.inspect(target).fingerprint)
        val real = SwitchApi(target, expectedPin!!)
        try {
            real.login(System.getenv("FN_SWITCH_TEST_USER") ?: "admin", password!!.toCharArray())
            val snapshot = real.snapshot()
            assertEquals("FN SWITCH 8 PRO", snapshot.model)
            assertEquals(8, snapshot.ports.size)
            assertTrue(snapshot.firmware.startsWith("V1.1."))
            assertTrue(snapshot.vlans.isNotEmpty())
            if (System.getenv("FN_SWITCH_TEST_DEEP") == "1") {
                val backup = real.backup(BackupKind.RUNNING)
                assertTrue(backup.bytes.size > 100)
                assertTrue(backup.bytes.toString(Charsets.UTF_8).startsWith("SYSTEM CONFIG FILE"))
                FileValidation.inspect(backup.name, backup.bytes, ImportKind.STARTUP)
                snapshot.features.forEach { feature ->
                    val response = real.proxy("GET", feature.path)
                    assertEquals("Page: ${feature.path}", 200, response.status)
                    assertTrue("HTML: ${feature.path}", response.contentType.contains("html"))
                }
                println("Read-only console coverage: ${snapshot.features.size} advertised pages, running configuration export")
            }
            println("Read-only device validation: ${snapshot.model}, ${snapshot.firmware}, ${snapshot.ports.size} ports")
        } finally { real.endSession() }
    }
}

fun fixtureSnapshot(): Snapshot {
    fun data(name: String): JSONObject = JSONObject(SwitchApiTest::class.java.getResource("/fixtures/$name.json")!!.readText()).getJSONObject("data")
    return Snapshot.parse(data("info"), data("ports"), data("vlans"), data("pvid"), data("usage"), data("time"))
}
