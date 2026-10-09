// JugglucoNG — Ottai / Syai driver
// OttaiCloudUploader.kt — sends accepted readings to the Syai cloud.
//
// With this on, the Syai Tag app, the syai.com website and anyone the account shares with
// (a linked doctor, followers) see the same readings as JugglucoNG, and the official reports
// are built from them.
//
// The request copies what the Syai Tag app sends. Its shape comes from the xDrip4iOS port of
// this driver by Lubor Jurena (luborjurena/xdripswift, branch feat/ottai-syai,
// OttaiCloudUploader.swift), where it was taken from the app's own requests. It is unofficial:
// the vendor can change or refuse it at any time. Off by default; Syai accounts only.
//
// Differences from the Swift version, because of Android:
//  - the queue is kept in a file, so readings waiting for the network survive the process
//    being killed;
//  - an unknown account binding is never treated as "unbound" (see OttaiCloudClient.bindState).

package tk.glucodata.drivers.ottai

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Log
import tk.glucodata.R
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** The pure parts of the upload: request shape, queue rules and persistence format. */
internal object OttaiCloudUploadProtocol {

    // Values seen in the Syai Tag app requests (see the file header).
    const val APP_NAME = "Syai Tag"
    const val PACKAGE_NAME = "com.syai.tag"
    const val VERSION_NAME = "1.23.0"
    const val VERSION_CODE = "261933"
    const val USER_AGENT = "Dart/3.10 (dart:io)"
    const val UNIT = "mmol_L"
    /** The app sends 1 for a reading from the sensor; other values are unknown. */
    const val DATA_TYPE_SENSOR = 1
    const val GLUCOSE_STATUS_NORMAL = 0

    const val MAX_BATCH = 200
    /** More than this and the oldest readings are dropped (about 3 days of 1-minute readings). */
    const val MAX_PENDING = 4000
    /** How many uploaded dataNos are remembered to skip a re-delivered reading. */
    const val MAX_REMEMBERED_UPLOADED = 8000

    /** One accepted reading, with the raw fields the Syai server wants. */
    data class Sample(
        val dataNo: Int,
        val runtimeSec: Int,
        val voltage: Int,
        val rawCurrent: Int,
        val temperatureC: Double,
        /** Glucose in mmol/L (the sensor formula result). */
        val mmol: Double,
        /** When the reading was taken, ms since 1970. */
        val sampleMs: Long,
        /** When the app received the packet, ms since 1970. */
        val receivedAtMs: Long,
        /** The 12-byte record (00 00 ‖ dataNo ‖ 8 bytes), sent as "origin". */
        val origin: IntArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Sample && dataNo == other.dataNo && runtimeSec == other.runtimeSec &&
                voltage == other.voltage && rawCurrent == other.rawCurrent &&
                temperatureC == other.temperatureC && mmol == other.mmol &&
                sampleMs == other.sampleMs && receivedAtMs == other.receivedAtMs &&
                origin.contentEquals(other.origin)

        override fun hashCode(): Int = dataNo * 31 + sampleMs.hashCode()

        companion object {
            fun from(
                record: OttaiRecord,
                mmol: Double,
                sampleMs: Long,
                receivedAtMs: Long,
            ): Sample = Sample(
                dataNo = record.dataNo,
                runtimeSec = record.runtimeSec,
                voltage = record.voltage,
                rawCurrent = record.rawCurrent,
                temperatureC = record.temperatureC,
                mmol = mmol,
                sampleMs = sampleMs,
                receivedAtMs = receivedAtMs,
                origin = IntArray(record.recordBytes.size) { record.recordBytes[it].toInt() and 0xFF },
            )
        }
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    fun dataListEntry(s: Sample): JSONObject {
        val mmol = round1(s.mmol)
        return JSONObject().apply {
            put("runSec", s.runtimeSec)
            put("voltage", s.voltage)
            put("timeAppReceive", s.receivedAtMs)
            put("frontIdx", s.dataNo)
            put("glucose", mmol)
            put("cgmGlucose", mmol)
            put("adjGlucose", mmol)
            put("current", s.rawCurrent)
            put("time", s.sampleMs)
            put("glucoseStatus", GLUCOSE_STATUS_NORMAL)
            put("alignId", JSONObject.NULL)
            put("temperature", round1(s.temperatureC))
            put("origin", JSONArray().apply { s.origin.forEach { put(it) } })
            put("dataType", DATA_TYPE_SENSOR)
        }
    }

    fun requestBody(deviceId: Int, deviceVersion: String, batch: List<Sample>): JSONObject =
        JSONObject().apply {
            put("deviceId", deviceId)
            put("embeddedSoftVersion", embeddedSoftVersion(deviceVersion))
            put("dataList", JSONArray().apply { batch.forEach { put(dataListEntry(it)) } })
        }

    /**
     * The cloud stores the version as "E1.1.4(V1.7.S2530.1)". The app sends the part in the
     * brackets, the sensor firmware. Old versions have no brackets and are sent as they are.
     */
    fun embeddedSoftVersion(deviceVersion: String): String {
        val v = deviceVersion.trim()
        val open = v.indexOf('(')
        val close = v.lastIndexOf(')')
        if (open >= 0 && close > open) {
            val inner = v.substring(open + 1, close).trim()
            if (inner.isNotEmpty()) return inner
        }
        return v
    }

    fun headers(
        accessToken: String,
        customerId: String,
        deviceUuid: String,
        deviceModel: String,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        traceId: String = UUID.randomUUID().toString(),
    ): Map<String, String> {
        val country = locale.country.ifBlank { "GB" }
        val language = locale.language.ifBlank { "en" }
        val now = Date(nowMs)
        val h = linkedMapOf(
            "user-agent" to USER_AGENT,
            "ua" to "android",
            "deviceid" to "$APP_NAME:a:n:$deviceUuid",
            "authorization" to accessToken,
            "appname" to APP_NAME,
            "content-type" to "application/json",
            "timestamp" to nowMs.toString(),
            "versioncode" to VERSION_CODE,
            "country" to country,
            "traceid" to traceId.lowercase(Locale.ROOT),
            "language" to language,
            "timezone" to (timeZone.getOffset(nowMs) / 1000).toString(),
            "region" to country,
            "packagename" to PACKAGE_NAME,
            "unit" to UNIT,
            "timezonename" to timeZone.getDisplayName(timeZone.inDaylightTime(now), TimeZone.SHORT, Locale.ROOT),
            "devicemodel" to deviceModel.ifBlank { "Android" },
            "versionname" to VERSION_NAME,
        )
        if (customerId.isNotBlank()) h["customerid"] = customerId
        return h
    }

    /** The customerId anywhere in the account profile, or null. */
    fun findCustomerId(profile: JSONObject?): String? {
        if (profile == null) return null
        val keys = profile.keys().asSequence().toList()
        for (key in keys) {
            val k = key.lowercase(Locale.ROOT)
            if (k == "customerid" || k == "customer_id" || k == "customerno") {
                val value = profile.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    val s = value.toString()
                    if (s.isNotBlank() && s != "null") return s
                }
            }
        }
        for (key in keys) {
            findCustomerId(profile.optJSONObject(key))?.let { return it }
        }
        return null
    }

    /** True when the upload response is a success. */
    fun isSuccess(httpCode: Int, body: JSONObject?): Boolean {
        if (httpCode !in 200..299) return false
        val code = body?.opt("code")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        return code.isBlank() || code == "200" || code.equals("OK", ignoreCase = true)
    }

    fun failureText(httpCode: Int, body: JSONObject?): String {
        val code = body?.opt("code")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        val msg = listOf("message", "msg", "detailMessage")
            .map { body?.opt(it)?.takeUnless { v -> v == JSONObject.NULL }?.toString().orEmpty() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
        return "http=$httpCode biz=$code ${msg.take(120)}".trim()
    }

    /**
     * Add [sample] to [pending]: skipped when already uploaded, replacing a queued reading with the
     * same dataNo, and dropping the oldest when the queue is full. Returns true when it changed.
     */
    fun addPending(pending: MutableList<Sample>, uploaded: Set<Int>, sample: Sample): Boolean {
        if (sample.dataNo in uploaded) return false
        val index = pending.indexOfFirst { it.dataNo == sample.dataNo }
        if (index >= 0) {
            if (pending[index] == sample) return false
            pending[index] = sample
            return true
        }
        pending.add(sample)
        if (pending.size > MAX_PENDING) {
            pending.subList(0, pending.size - MAX_PENDING).clear()
        }
        return true
    }

    /** Oldest readings first, so the cloud fills in the order they were taken. */
    fun nextBatch(pending: List<Sample>): List<Sample> =
        pending.sortedBy { it.sampleMs }.take(MAX_BATCH)

    // ---- the queue file ----

    fun toJson(s: Sample): JSONObject = JSONObject().apply {
        put("d", s.dataNo)
        put("r", s.runtimeSec)
        put("v", s.voltage)
        put("c", s.rawCurrent)
        put("t", s.temperatureC)
        put("g", s.mmol)
        put("s", s.sampleMs)
        put("a", s.receivedAtMs)
        put("o", JSONArray().apply { s.origin.forEach { put(it) } })
    }

    fun fromJson(o: JSONObject): Sample? {
        if (!o.has("d") || !o.has("s")) return null
        val originJson = o.optJSONArray("o") ?: JSONArray()
        return Sample(
            dataNo = o.optInt("d"),
            runtimeSec = o.optInt("r"),
            voltage = o.optInt("v"),
            rawCurrent = o.optInt("c"),
            temperatureC = o.optDouble("t"),
            mmol = o.optDouble("g"),
            sampleMs = o.optLong("s"),
            receivedAtMs = o.optLong("a"),
            origin = IntArray(originJson.length()) { originJson.optInt(it) },
        ).takeIf { it.sampleMs > 0L && it.mmol.isFinite() }
    }

    fun encodeQueue(pending: List<Sample>): String =
        JSONArray().apply { pending.forEach { put(toJson(it)) } }.toString()

    fun decodeQueue(text: String): List<Sample> {
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    }
}

/**
 * One uploader per sensor. All state lives on one background thread; [enqueue] may be called
 * from any thread.
 */
internal class OttaiCloudUploader(
    context: Context,
    val sensorId: String,
    private val uploadUrl: String = OttaiConstants.API_BASE_SYAI + OttaiConstants.EP_COLLECT_GLUCOSE_V2,
) {

    private val appContext: Context = context.applicationContext ?: context
    private val pending = ArrayList<OttaiCloudUploadProtocol.Sample>()
    private val uploaded = LinkedHashSet<Int>()
    private var flushTask: ScheduledFuture<*>? = null
    private var saveTask: ScheduledFuture<*>? = null
    private var retryDelayMs = FIRST_RETRY_DELAY_MS
    private var loaded = false
    private var cachedCustomerId: String? = null
    private var bindConfirmed = false
    private var nextBindCheckMs = 0L
    /** Added after the status while the Syai app cannot show this sensor. */
    private var bindNotice: String? = null

    /** Queue one accepted reading. Does nothing while the upload is off. */
    fun enqueue(record: OttaiRecord, mmol: Double, sampleMs: Long, receivedAtMs: Long) {
        if (!OttaiRegistry.loadCloudUploadEnabled(appContext)) return
        if (OttaiRegistry.loadApiBase(appContext) != OttaiConstants.API_BASE_SYAI) return
        val sample = OttaiCloudUploadProtocol.Sample.from(record, mmol, sampleMs, receivedAtMs)
        executor.execute {
            runCatching {
                ensureLoaded()
                if (OttaiCloudUploadProtocol.addPending(pending, uploaded, sample)) scheduleSave()
                // Only the short delay when no retry is already waiting.
                if (flushTask == null) scheduleFlush(FLUSH_DELAY_MS)
            }.onFailure { Log.stack(TAG, "enqueue", it) }
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        flushTask?.cancel(false)
        flushTask = executor.schedule({
            flushTask = null
            runCatching { flush() }.onFailure {
                Log.stack(TAG, "flush", it)
                scheduleRetry()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleRetry() {
        scheduleFlush(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun flush() {
        ensureLoaded()
        if (pending.isEmpty()) return
        unavailableReason(appContext)?.let { reason ->
            Log.i(TAG, "cloud upload skipped: $reason")
            if (!OttaiRegistry.loadCloudUploadEnabled(appContext)) {
                // Switched off: forget the queue instead of sending it later.
                pending.clear()
                saveNow()
                return
            }
            setStatus(reason)
            scheduleRetry()
            return
        }

        var materials = OttaiRegistry.loadMaterials(appContext, sensorId)
        if (materials.deviceId <= 0) {
            Log.w(TAG, "cloud upload skipped: no cloud device id for $sensorId")
            setStatus(appContext.getString(R.string.ottai_cloud_upload_no_device_id))
            pending.clear()
            saveNow()
            return
        }
        materials = ensureBound(materials)
        val customerId = resolveCustomerId()
        val batch = OttaiCloudUploadProtocol.nextBatch(pending)
        val body = OttaiCloudUploadProtocol.requestBody(materials.deviceId, materials.deviceVersion, batch)
        val headers = OttaiCloudUploadProtocol.headers(
            accessToken = OttaiRegistry.loadAccessToken(appContext),
            customerId = customerId,
            deviceUuid = OttaiRegistry.loadOrCreateCloudUploadDeviceUuid(appContext),
            deviceModel = Build.MODEL.orEmpty(),
            nowMs = System.currentTimeMillis(),
        )
        Log.i(TAG, "cloud upload: posting ${batch.size} readings " +
            "(dataNo ${batch.minOf { it.dataNo }}..${batch.maxOf { it.dataNo }}) deviceId=${materials.deviceId}")
        val (httpCode, response, networkError) = post(uploadUrl, body.toString(), headers)
        if (networkError != null) {
            fail("network: $networkError")
            return
        }
        if (!OttaiCloudUploadProtocol.isSuccess(httpCode, response)) {
            fail(OttaiCloudUploadProtocol.failureText(httpCode, response))
            return
        }
        val sent = batch.mapTo(HashSet()) { it.dataNo }
        pending.removeAll { it.dataNo in sent }
        rememberUploaded(sent)
        saveNow()
        retryDelayMs = FIRST_RETRY_DELAY_MS
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val ok = appContext.getString(R.string.ottai_cloud_upload_ok, batch.size, stamp)
        setStatus(bindNotice?.let { "$ok — $it" } ?: ok)
        Log.i(TAG, "cloud upload: ok count=${batch.size} pending=${pending.size}")
        if (pending.isNotEmpty()) scheduleFlush(NEXT_BATCH_DELAY_MS)
    }

    private fun fail(reason: String) {
        Log.w(TAG, "cloud upload failed: $reason (pending=${pending.size}, retry in ${retryDelayMs / 1000}s)")
        setStatus(appContext.getString(R.string.ottai_cloud_upload_failed, reason))
        scheduleRetry()
    }

    private fun rememberUploaded(dataNos: Collection<Int>) {
        uploaded.addAll(dataNos)
        val excess = uploaded.size - OttaiCloudUploadProtocol.MAX_REMEMBERED_UPLOADED
        if (excess > 0) {
            val iterator = uploaded.iterator()
            repeat(excess) { iterator.next(); iterator.remove() }
        }
    }

    /**
     * The server takes readings of an unbound sensor, but the Syai app shows only the account's
     * bound sensor. Bind this one when the account has none; leave another bound sensor alone.
     * Readings go to the deviceId of the bound record.
     */
    private fun ensureBound(materials: OttaiRegistry.DeviceMaterials): OttaiRegistry.DeviceMaterials {
        val now = System.currentTimeMillis()
        if (bindConfirmed || now < nextBindCheckMs) return materials
        nextBindCheckMs = now + BIND_CHECK_DELAY_MS
        when (val state = OttaiCloudClient.bindState(appContext, sensorId)) {
            is OttaiCloudClient.BindState.ThisSensor -> {
                bindConfirmed = true
                bindNotice = null
                if (state.deviceId > 0 && state.deviceId != materials.deviceId) {
                    Log.w(TAG, "cloud bind: bound deviceId=${state.deviceId}, saved=${materials.deviceId}; using the bound one")
                    OttaiRegistry.saveDeviceId(appContext, sensorId, state.deviceId)
                    return materials.copy(deviceId = state.deviceId)
                }
            }
            is OttaiCloudClient.BindState.Other -> {
                Log.w(TAG, "cloud bind: the account has ${state.mac} bound, not $sensorId")
                bindNotice = appContext.getString(R.string.ottai_cloud_upload_other_bound, state.mac)
            }
            OttaiCloudClient.BindState.Unbound -> {
                if (materials.activeTimeMs <= 0L) {
                    // Wait for the confirmed start from the first live readings.
                    nextBindCheckMs = now + FIRST_RETRY_DELAY_MS
                } else if (OttaiCloudClient.bindPermanently(appContext, sensorId, materials)) {
                    Log.i(TAG, "cloud bind: $sensorId bound, activeTime=${materials.activeTimeMs / 1000}")
                    // Check again on the next upload to take the deviceId of the new record.
                    nextBindCheckMs = 0L
                    bindNotice = null
                } else {
                    Log.w(TAG, "cloud bind failed: ${OttaiCloudClient.lastError}")
                    bindNotice = appContext.getString(R.string.ottai_cloud_upload_bind_failed, OttaiCloudClient.lastError)
                }
            }
            null -> Log.w(TAG, "cloud bind check failed: ${OttaiCloudClient.lastError}")
        }
        return materials
    }

    /**
     * The customerid header. Always the signed-in account: read once from its profile, or its
     * user id when the profile has none. It can not be typed in, so an upload can never go to
     * another account.
     */
    private fun resolveCustomerId(): String {
        cachedCustomerId?.let { return it }
        val profile = OttaiCloudClient.fetchUserProfile(appContext)
        val found = OttaiCloudUploadProtocol.findCustomerId(profile)
        val resolved = found ?: OttaiRegistry.loadUserId(appContext)
        Log.i(TAG, when {
            found != null -> "cloud upload: customerId from the account profile"
            profile != null -> "cloud upload: no customerId in the profile, using the user id"
            else -> "cloud upload: profile not readable now, using the user id for this upload"
        })
        // A profile that could not be read is asked again next time, not settled for good.
        if (profile != null) cachedCustomerId = resolved
        return resolved
    }

    private fun setStatus(text: String) {
        OttaiRegistry.saveCloudUploadStatus(appContext, text)
    }

    // ---- queue file ----

    private fun queueFile(): File =
        File(File(appContext.filesDir, QUEUE_DIR), "${sensorId.filter { it.isLetterOrDigit() }}.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = queueFile()
        if (!file.exists()) return
        val restored = runCatching { OttaiCloudUploadProtocol.decodeQueue(file.readText()) }
            .onFailure { Log.stack(TAG, "read upload queue", it) }
            .getOrDefault(emptyList())
        restored.forEach { OttaiCloudUploadProtocol.addPending(pending, uploaded, it) }
        if (pending.isNotEmpty()) {
            Log.i(TAG, "cloud upload: restored ${pending.size} queued readings for $sensorId")
            if (flushTask == null) scheduleFlush(FLUSH_DELAY_MS)
        }
    }

    /** Writes are coalesced: a reading a minute does not need a file write a minute. */
    private fun scheduleSave() {
        if (saveTask != null) return
        saveTask = executor.schedule({
            saveTask = null
            saveNow()
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun saveNow() {
        saveTask?.cancel(false)
        saveTask = null
        runCatching {
            val file = queueFile()
            if (pending.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(OttaiCloudUploadProtocol.encodeQueue(pending))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.stack(TAG, "write upload queue", it) }
    }

    // ---- HTTP ----

    private data class PostResult(val httpCode: Int, val body: JSONObject?, val networkError: String?)

    /**
     * Its own connection rather than OttaiCloudClient's request helper: that one records the
     * result in OttaiCloudClient.lastFailure, which the setup screen shows, and a background
     * upload must not overwrite what the user is reading there.
     */
    private fun post(url: String, body: String, headers: Map<String, String>): PostResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                setRequestProperty("content-length", bytes.size.toString())
                outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            PostResult(code, json, null)
        } catch (t: Throwable) {
            PostResult(-1, null, t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = OttaiConstants.TAG
        private const val QUEUE_DIR = "ottai_cloud_upload"
        private const val FLUSH_DELAY_MS = 3_000L
        private const val NEXT_BATCH_DELAY_MS = 500L
        private const val SAVE_DELAY_MS = 5_000L
        private const val FIRST_RETRY_DELAY_MS = 60_000L
        private const val MAX_RETRY_DELAY_MS = 15 * 60_000L
        private const val BIND_CHECK_DELAY_MS = 15 * 60_000L
        private const val TIMEOUT_MS = 30_000

        private val executor: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "OttaiCloudUpload").apply { isDaemon = true }
            }
        }

        private val uploaders = HashMap<String, OttaiCloudUploader>()

        /** The uploader of a sensor; one per sensor for the life of the process. */
        @JvmStatic
        fun forSensor(context: Context, sensorId: String): OttaiCloudUploader {
            val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
            return synchronized(uploaders) {
                uploaders.getOrPut(id) { OttaiCloudUploader(context, id) }
            }
        }

        /**
         * Forget every queued reading. Called when the user switches the upload off, so switching
         * it on again later does not send readings from before.
         */
        @JvmStatic
        fun discardQueues(context: Context) {
            val appContext = context.applicationContext ?: context
            val all = synchronized(uploaders) { uploaders.values.toList() }
            executor.execute {
                all.forEach { it.pending.clear(); it.loaded = true }
                runCatching { File(appContext.filesDir, QUEUE_DIR).deleteRecursively() }
                    .onFailure { Log.stack(TAG, "discard upload queues", it) }
            }
        }

        /** Why the upload cannot run right now, or null when it can. */
        @JvmStatic
        fun unavailableReason(context: Context): String? = when {
            !OttaiRegistry.loadCloudUploadEnabled(context) ->
                context.getString(R.string.ottai_cloud_upload_off)
            OttaiRegistry.loadApiBase(context) != OttaiConstants.API_BASE_SYAI ->
                context.getString(R.string.ottai_cloud_upload_needs_syai)
            OttaiRegistry.loadAccessToken(context).isBlank() ->
                context.getString(R.string.ottai_cloud_upload_not_signed_in)
            else -> null
        }
    }
}
