package com.soul667.x5remote

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val BE80 = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
private val BE81 = UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
private val BE82 = UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class CommandResult(
    val ok: Boolean,
    val ackCode: Int,
    val dispatchNanos: Long,
    val doneNanos: Long,
    val body: ByteArray,
    val error: String?,
) {
    val rttMillis: Double get() = (doneNanos - dispatchNanos) / 1e6
}

/**
 * One BLE connection to an Insta360 X5, speaking the network-framed protocol:
 * connect -> subscribe BE82 -> SYNC handshake -> 2s keep-alive, then commands
 * are matched to acknowledgements by the echoed 24-bit sequence number.
 */
class BleCamera(
    private val appContext: Context,
    private val device: BluetoothDevice,
    val label: String,
) {
    val address: String get() = device.address

    @Volatile var connected = false; private set
    @Volatile var ready = false; private set
    @Volatile var lastError: String? = null; private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val servicesReady = CompletableDeferred<Unit>()
    private val syncEcho = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<X5Protocol.Message>>()
    private val cmdMutex = Mutex()
    private var seqCounter = 1
    private var rxBuf = ByteArray(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    private var closed = false

    private fun nextSeq(): Int {
        val s = seqCounter
        seqCounter = if (seqCounter >= 254) 1 else seqCounter + 1
        return s
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                g.discoverServices()
            } else {
                connected = false
                ready = false
                if (!servicesReady.isCompleted) {
                    servicesReady.completeExceptionally(
                        IllegalStateException("disconnected (status=$status)")
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BE80)
            if (svc == null) {
                fail("BE80 service not found")
                return
            }
            val wc = svc.getCharacteristic(BE81)
            val nc = svc.getCharacteristic(BE82)
            if (wc == null || nc == null) {
                fail("BE81/BE82 not found")
                return
            }
            writeChar = wc
            notifyChar = nc
            g.setCharacteristicNotification(nc, true)
            val desc = nc.getDescriptor(CCCD)
            if (desc == null) {
                fail("CCCD descriptor not found")
                return
            }
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(desc)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!servicesReady.isCompleted) servicesReady.complete(Unit)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33; still the compatibility path on all versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleBytes(c.value ?: return)
        }
    }

    suspend fun connect(): Boolean {
        return try {
            if (closed) return false
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return fail("connectGatt returned null")
            withTimeout(15_000) { servicesReady.await() }
            // SYNC handshake (optional per protocol notes, recommended anyway)
            write(X5Protocol.syncFrame())
            withTimeoutOrNull(3_000) { syncEcho.await() }
            ready = true
            lastError = null
            startKeepalive()
            true
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
            false
        }
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        ready = false
        return false
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && ready) {
                delay(2_000)
                try {
                    write(X5Protocol.keepaliveFrame())
                } catch (_: Throwable) {
                    // keep-alive failures are non-fatal
                }
            }
        }
    }

    @Synchronized
    private fun handleBytes(b: ByteArray) {
        rxBuf += b
        var consumed = 0
        while (rxBuf.size - consumed >= 4) {
            val total = (rxBuf[consumed].toInt() and 0xFF) or
                    ((rxBuf[consumed + 1].toInt() and 0xFF) shl 8) or
                    ((rxBuf[consumed + 2].toInt() and 0xFF) shl 16) or
                    ((rxBuf[consumed + 3].toInt() and 0xFF) shl 24)
            if (total < 4 || total > 4096) {
                consumed = rxBuf.size
                break
            }
            if (rxBuf.size - consumed < total) break
            processFrame(rxBuf.copyOfRange(consumed, consumed + total))
            consumed += total
        }
        rxBuf = if (consumed >= rxBuf.size) ByteArray(0) else rxBuf.copyOfRange(consumed, rxBuf.size)
    }

    private fun processFrame(frame: ByteArray) {
        if (frame.size <= 4) return
        val payload = frame.copyOfRange(4, frame.size)
        when (payload[0].toInt() and 0xFF) {
            X5Protocol.PKT_KEEPALIVE -> Unit
            X5Protocol.PKT_SYNC -> if (!syncEcho.isCompleted) syncEcho.complete(Unit)
            X5Protocol.PKT_MESSAGE -> {
                val m = X5Protocol.parseMessage(payload) ?: return
                if (m.code in X5Protocol.RESP_OK..X5Protocol.RESP_NOT_IMPL) {
                    pending.remove(m.seq)?.complete(m)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun write(frame: ByteArray) = withContext(Dispatchers.IO) {
        val g = gatt ?: throw IllegalStateException("not connected")
        val c = writeChar ?: throw IllegalStateException("no write characteristic")
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + 20, frame.size)
            val chunk = frame.copyOfRange(offset, end)
            c.value = chunk
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!g.writeCharacteristic(c)) throw IllegalStateException("writeCharacteristic failed")
            offset = end
            if (offset < frame.size) delay(15)
        }
    }

    /** Send a command and wait for the 200 ACK (matched by sequence number). */
    suspend fun sendCommand(
        code: Int,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = 8_000,
    ): CommandResult = cmdMutex.withLock {
        val dispatch = SystemClock.elapsedRealtimeNanos()
        val seq = nextSeq()
        val deferred = CompletableDeferred<X5Protocol.Message>()
        pending[seq] = deferred
        try {
            write(X5Protocol.messageFrame(code, seq, body))
        } catch (t: Throwable) {
            pending.remove(seq)
            return@withLock CommandResult(
                false, 0, dispatch, SystemClock.elapsedRealtimeNanos(), ByteArray(0),
                t.message ?: t.toString(),
            )
        }
        val msg = withTimeoutOrNull(timeoutMs) { deferred.await() }
        val done = SystemClock.elapsedRealtimeNanos()
        if (msg == null) {
            pending.remove(seq)
            CommandResult(false, 0, dispatch, done, ByteArray(0), "timeout (${timeoutMs}ms)")
        } else {
            CommandResult(
                ok = msg.code == X5Protocol.RESP_OK,
                ackCode = msg.code,
                dispatchNanos = dispatch,
                doneNanos = done,
                body = msg.body,
                error = if (msg.code == X5Protocol.RESP_OK) null else X5Protocol.statusName(msg.code),
            )
        }
    }

    fun disconnect() {
        closed = true
        keepaliveJob?.cancel()
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        ready = false
        connected = false
        scope.cancel()
    }
}
