package tk.glucodata.drivers.ottai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class OttaiCloudUploaderTests {

    private fun sample(dataNo: Int, mmol: Double = 5.55, sampleMs: Long = 1_760_000_000_000L + dataNo * 60_000L) =
        OttaiCloudUploadProtocol.Sample(
            dataNo = dataNo,
            runtimeSec = dataNo * 60,
            voltage = 71,
            rawCurrent = 15391,
            temperatureC = 30.137,
            mmol = mmol,
            sampleMs = sampleMs,
            receivedAtMs = sampleMs + 1_500L,
            origin = intArrayOf(0, 0, dataNo and 0xFF, (dataNo shr 8) and 0xFF, 71, 1, 2, 3, 0x1F, 0x3C, 0xC5, 0x0B),
        )

    @Test
    fun dataListEntryCarriesTheSyaiTagFields() {
        val entry = OttaiCloudUploadProtocol.dataListEntry(sample(19585, mmol = 6.04))
        assertEquals(19585, entry.getInt("frontIdx"))
        assertEquals(19585 * 60, entry.getInt("runSec"))
        assertEquals(71, entry.getInt("voltage"))
        assertEquals(15391, entry.getInt("current"))
        // Glucose and temperature are sent with one decimal, glucose three times.
        assertEquals(6.0, entry.getDouble("glucose"), 0.0)
        assertEquals(6.0, entry.getDouble("cgmGlucose"), 0.0)
        assertEquals(6.0, entry.getDouble("adjGlucose"), 0.0)
        assertEquals(30.1, entry.getDouble("temperature"), 0.0)
        assertEquals(0, entry.getInt("glucoseStatus"))
        assertEquals(1, entry.getInt("dataType"))
        assertTrue(entry.isNull("alignId"))
        assertEquals(12, entry.getJSONArray("origin").length())
        assertEquals(0xC5, entry.getJSONArray("origin").getInt(10))
        assertEquals(sample(19585).sampleMs, entry.getLong("time"))
        assertEquals(sample(19585).receivedAtMs, entry.getLong("timeAppReceive"))
    }

    @Test
    fun requestBodySendsTheFirmwarePartOfTheVersion() {
        val body = OttaiCloudUploadProtocol.requestBody(4242, "E1.1.4(V1.7.S2530.1)", listOf(sample(1), sample(2)))
        assertEquals(4242, body.getInt("deviceId"))
        assertEquals("V1.7.S2530.1", body.getString("embeddedSoftVersion"))
        assertEquals(2, body.getJSONArray("dataList").length())
    }

    @Test
    fun embeddedSoftVersionKeepsAVersionWithoutBrackets() {
        assertEquals("V1.5.S2401.2", OttaiCloudUploadProtocol.embeddedSoftVersion(" V1.5.S2401.2 "))
        assertEquals("E1.1.4()", OttaiCloudUploadProtocol.embeddedSoftVersion("E1.1.4()"))
        assertEquals("", OttaiCloudUploadProtocol.embeddedSoftVersion(""))
    }

    @Test
    fun headersImitateTheSyaiTagApp() {
        val h = OttaiCloudUploadProtocol.headers(
            accessToken = "token",
            customerId = "C123",
            deviceUuid = "uuid-1",
            deviceModel = "Pixel 8",
            nowMs = 1_760_000_000_000L,
            timeZone = TimeZone.getTimeZone("Europe/Bratislava"),
            locale = Locale.forLanguageTag("sk-SK"),
            traceId = "ABC",
        )
        assertEquals("Syai Tag", h["appname"])
        assertEquals("com.syai.tag", h["packagename"])
        assertEquals("Syai Tag:a:n:uuid-1", h["deviceid"])
        assertEquals("token", h["authorization"])
        assertEquals("C123", h["customerid"])
        assertEquals("android", h["ua"])
        assertEquals("SK", h["country"])
        assertEquals("SK", h["region"])
        assertEquals("sk", h["language"])
        assertEquals("7200", h["timezone"])
        assertEquals("CEST", h["timezonename"])
        assertEquals("abc", h["traceid"])
        assertEquals("Pixel 8", h["devicemodel"])
        assertEquals("mmol_L", h["unit"])
        assertEquals("1760000000000", h["timestamp"])
    }

    @Test
    fun headersLeaveOutABlankCustomerId() {
        val h = OttaiCloudUploadProtocol.headers("t", "", "u", "", 0L)
        assertFalse(h.containsKey("customerid"))
        assertEquals("Android", h["devicemodel"])
    }

    @Test
    fun customerIdIsFoundAnywhereInTheProfile() {
        assertEquals("777", OttaiCloudUploadProtocol.findCustomerId(JSONObject("""{"customerId":777}""")))
        assertEquals(
            "C-9",
            OttaiCloudUploadProtocol.findCustomerId(JSONObject("""{"user":{"name":"x","info":{"customer_id":"C-9"}}}""")),
        )
        assertNull(OttaiCloudUploadProtocol.findCustomerId(JSONObject("""{"customerId":null,"userName":"a"}""")))
        assertNull(OttaiCloudUploadProtocol.findCustomerId(null))
    }

    @Test
    fun successNeedsHttpOkAndAnOkBusinessCode() {
        assertTrue(OttaiCloudUploadProtocol.isSuccess(200, JSONObject("""{"code":"OK"}""")))
        assertTrue(OttaiCloudUploadProtocol.isSuccess(200, JSONObject("""{"code":200}""")))
        assertTrue(OttaiCloudUploadProtocol.isSuccess(200, null))
        assertFalse(OttaiCloudUploadProtocol.isSuccess(200, JSONObject("""{"code":"AuthFailed_TokenInvalid"}""")))
        assertFalse(OttaiCloudUploadProtocol.isSuccess(500, JSONObject("""{"code":"OK"}""")))
        assertEquals(
            "http=401 biz=AuthFailed_TokenInvalid expired",
            OttaiCloudUploadProtocol.failureText(401, JSONObject("""{"code":"AuthFailed_TokenInvalid","message":"expired"}""")),
        )
    }

    @Test
    fun queueSkipsUploadedReplacesSameDataNoAndCapsItsSize() {
        val pending = mutableListOf<OttaiCloudUploadProtocol.Sample>()
        assertTrue(OttaiCloudUploadProtocol.addPending(pending, emptySet(), sample(1)))
        assertFalse(OttaiCloudUploadProtocol.addPending(pending, emptySet(), sample(1)))
        assertTrue(OttaiCloudUploadProtocol.addPending(pending, emptySet(), sample(1, mmol = 7.0)))
        assertEquals(1, pending.size)
        assertEquals(7.0, pending.single().mmol, 0.0)
        assertFalse(OttaiCloudUploadProtocol.addPending(pending, setOf(2), sample(2)))
        assertEquals(1, pending.size)

        pending.clear()
        for (n in 0 until OttaiCloudUploadProtocol.MAX_PENDING + 10) {
            OttaiCloudUploadProtocol.addPending(pending, emptySet(), sample(n))
        }
        assertEquals(OttaiCloudUploadProtocol.MAX_PENDING, pending.size)
        assertEquals(10, pending.first().dataNo)
    }

    @Test
    fun batchesGoOldestFirstAndAreBounded() {
        val pending = (500 downTo 1).map { sample(it) }
        val batch = OttaiCloudUploadProtocol.nextBatch(pending)
        assertEquals(OttaiCloudUploadProtocol.MAX_BATCH, batch.size)
        assertEquals(1, batch.first().dataNo)
        assertEquals(OttaiCloudUploadProtocol.MAX_BATCH, batch.last().dataNo)
    }

    @Test
    fun queueFileRoundTrips() {
        val pending = listOf(sample(1), sample(2, mmol = 3.9))
        val restored = OttaiCloudUploadProtocol.decodeQueue(OttaiCloudUploadProtocol.encodeQueue(pending))
        assertEquals(pending, restored)
        assertTrue(OttaiCloudUploadProtocol.decodeQueue("").isEmpty())
        assertTrue(OttaiCloudUploadProtocol.decodeQueue("not json").isEmpty())
    }

    @Test
    fun sampleFromRecordKeepsTheRawRecordAsUnsignedBytes() {
        val bytes = byteArrayOf(0, 0, 0x81.toByte(), 0x4C, 71, 1, 2, 3, 0x1F, 0x3C, 0xC5.toByte(), 0x0B)
        val record = OttaiRecord(19585, 71, 1_175_100, 15391, 30.13, bytes)
        val s = OttaiCloudUploadProtocol.Sample.from(record, 6.04, 10L, 20L)
        assertEquals(19585, s.dataNo)
        assertEquals(0x81, s.origin[2])
        assertEquals(0xC5, s.origin[10])
        assertEquals(1_175_100, s.runtimeSec)
    }

    // ---- account binding ----

    private fun bindState(body: String?, failure: OttaiCloudClient.CloudFailure? = null) =
        OttaiCloudClient.parseBindState(
            OttaiCloudClient.CloudRequestResult(body?.let(::JSONObject), failure),
            "70D07E2552DB",
        )

    @Test
    fun bindStateRecognisesThisSensorAndItsRecordId() {
        assertEquals(
            OttaiCloudClient.BindState.ThisSensor(321),
            bindState("""{"data":{"cgmDeviceRespVO":{"mac":"70:d0:7e:25:52:db","id":321}}}"""),
        )
    }

    @Test
    fun bindStateReportsAnotherBoundSensor() {
        assertEquals(
            OttaiCloudClient.BindState.Other("18690ADED9B3"),
            bindState("""{"data":{"mac":"18690ADED9B3"}}"""),
        )
    }

    @Test
    fun onlyAnExplicitlyEmptyAnswerMeansUnbound() {
        assertEquals(OttaiCloudClient.BindState.Unbound, bindState("""{"data":null}"""))
        assertEquals(OttaiCloudClient.BindState.Unbound, bindState("""{"data":{}}"""))
        // Unknown must never become Unbound: Unbound leads to a bind.
        assertNull(bindState("""{"data":{"something":"else"}}"""))
        assertNull(bindState(null))
        assertNull(bindState("""{"data":null}""", OttaiCloudClient.CloudFailure("http=500")))
    }
}
