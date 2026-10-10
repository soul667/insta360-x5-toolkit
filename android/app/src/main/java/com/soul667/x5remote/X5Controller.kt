package com.soul667.x5remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
)

data class CamResult(
    val label: String,
    val ok: Boolean,
    val ackCode: Int,
    val dispatchNanos: Long,
    val rttMillis: Double,
    val file: String?,
    val error: String?,
) {
    val dispatchMillis: Double get() = dispatchNanos / 1e6
}

class X5Controller(private val appContext: Context) {

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val cameras = MutableStateFlow<List<BleCamera>>(emptyList())
    val scanning = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val lastResults = MutableStateFlow<List<CamResult>>(emptyList())
    val lastAction = MutableStateFlow<String?>(null)
    val clockNote = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(seconds: Long = 12) {
        val a = adapter
        if (a == null) {
            message.value = "本机不支持蓝牙"
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            message.value = "请先打开蓝牙"
            return
        }
        stopScan()
        found.value = emptyList()
        scanning.value = true
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                } ?: return
                if (!name.startsWith("X5 ")) return
                val cur = found.value.toMutableList()
                val idx = cur.indexOfFirst { it.address == result.device.address }
                val fd = FoundDevice(result.device, name, result.device.address, result.rssi)
                if (idx >= 0) cur[idx] = fd else cur.add(fd)
                found.value = cur.sortedByDescending { it.rssi }
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        scanJob = scope.launch {
            delay(seconds * 1000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        val cb = scanCallback
        if (scanning.value && cb != null) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Throwable) {
            }
        }
        scanning.value = false
    }

    fun connect(devices: List<FoundDevice>) {
        scope.launch {
            disconnectAll()
            connecting.value = true
            message.value = "正在连接 ${devices.size} 台相机..."
            val cams = devices.map { BleCamera(appContext, it.device, it.name) }
            cameras.value = cams
            coroutineScope {
                cams.map { cam -> async(Dispatchers.IO) { cam.connect() } }.awaitAll()
            }
            connecting.value = false
            val okCount = cams.count { it.ready }
            val errs = cams.filter { !it.ready }
                .joinToString("") { "；${it.label}: ${it.lastError ?: "未连接"}" }
            message.value = "已就绪 $okCount/${cams.size} 台$errs"
        }
    }

    fun disconnectAll() {
        cameras.value.forEach { it.disconnect() }
        cameras.value = emptyList()
        recording.value = false
        lastResults.value = emptyList()
        lastAction.value = null
        clockNote.value = null
    }

    /**
     * Fire the same action on every ready camera from a shared barrier and
     * report the per-camera dispatch offsets and round-trip times.
     */
    suspend fun broadcast(
        name: String,
        delayMs: Long,
        action: suspend (BleCamera) -> CommandResult,
    ): List<CamResult> {
        val cams = cameras.value.filter { it.ready }
        if (cams.isEmpty()) {
            message.value = "没有已连接的相机"
            return emptyList()
        }
        busy.value = true
        try {
            val fireAt = SystemClock.elapsedRealtimeNanos() + delayMs * 1_000_000
            val started = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            val raw = coroutineScope {
                val deferreds = cams.map { cam ->
                    async(Dispatchers.IO) {
                        started.incrementAndGet()
                        gate.await()
                        while (true) {
                            val left = fireAt - SystemClock.elapsedRealtimeNanos()
                            if (left <= 0) break
                            if (left > 2_000_000) delay(left / 2_000_000) // coarse sleep
                            // else busy-spin for a sharp dispatch edge
                        }
                        action(cam)
                    }
                }
                while (started.get() < cams.size) delay(1)
                gate.complete(Unit)
                deferreds.awaitAll()
            }
            val base = raw.minOfOrNull { it.dispatchNanos } ?: 0L
            val mapped = raw.mapIndexed { i, r ->
                CamResult(
                    label = cams[i].label,
                    ok = r.ok,
                    ackCode = r.ackCode,
                    dispatchNanos = r.dispatchNanos - base,
                    rttMillis = r.rttMillis,
                    file = X5Protocol.recordedFileFromAck(r.body),
                    error = r.error,
                )
            }
            lastResults.value = mapped
            lastAction.value = name
            if (name == "record start" && mapped.all { it.ok }) recording.value = true
            if (name == "record stop") recording.value = false

            clockNote.value = null
            if (name == "record stop") {
                val stamps = mapped.mapNotNull { m ->
                    m.file?.let { f -> parseFileClock(f)?.let { t -> m.label to t } }
                }
                if (stamps.size >= 2) {
                    val diffSec = (stamps[0].second - stamps[1].second) / 1000.0
                    clockNote.value =
                        "${stamps[0].first} 的时钟比 ${stamps[1].first} 快 %+.0f 秒".format(diffSec)
                }
            }
            return mapped
        } finally {
            busy.value = false
        }
    }

    private val fileClockRe = Regex("""VID_(\d{8})_(\d{6})""")

    private fun parseFileClock(file: String): Long? {
        val m = fileClockRe.find(file) ?: return null
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .parse(m.groupValues[1] + "_" + m.groupValues[2])?.time
        } catch (_: Throwable) {
            null
        }
    }
}
