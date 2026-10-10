package com.soul667.x5remote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Insta360 X5 BLE "network frame" protocol (reverse-engineered).
 *
 * Every BLE packet is: [u32 total_len LE (includes itself)][payload], where the
 * payload starts with a packet type byte:
 *   0x04 MESSAGE   -> 12-byte header {type, 00 00, code u16 LE, 02, seq u24 LE, 80, 00 00} + body
 *   0x05 KEEPALIVE -> "05 00 00"
 *   0x06 SYNC      -> "06 00 00" + "syNceNdinS"
 *
 * See the project docs (docs/x5-ble-protocol.md in insta360-x5-toolkit) for
 * the full specification and captured traffic.
 */
object X5Protocol {
    const val PKT_MESSAGE = 0x04
    const val PKT_KEEPALIVE = 0x05
    const val PKT_SYNC = 0x06

    const val CMD_TAKE_PHOTO = 0x03
    const val CMD_START_CAPTURE = 0x04
    const val CMD_STOP_CAPTURE = 0x05
    const val CMD_GET_CAPTURE_STATUS = 0x0F
    const val CMD_SET_HIGHLIGHT = 0x3C

    const val RESP_OK = 0x00C8
    const val RESP_BAD_REQUEST = 0x0190
    const val RESP_ERROR = 0x01F4
    const val RESP_NOT_IMPL = 0x01F5

    /** Wrap a payload with the 4-byte little-endian total-length prefix. */
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + payload.size)
        payload.copyInto(out, 4)
        return out
    }

    fun syncFrame(): ByteArray =
        frame(byteArrayOf(PKT_SYNC.toByte(), 0, 0) + "syNceNdinS".toByteArray(Charsets.US_ASCII))

    fun keepaliveFrame(): ByteArray = frame(byteArrayOf(PKT_KEEPALIVE.toByte(), 0, 0))

    fun messageFrame(code: Int, seq: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val p = ByteArray(12 + body.size)
        p[0] = PKT_MESSAGE.toByte()
        p[3] = (code and 0xFF).toByte()
        p[4] = ((code ushr 8) and 0xFF).toByte()
        p[5] = 0x02
        p[6] = (seq and 0xFF).toByte()
        p[7] = ((seq ushr 8) and 0xFF).toByte()
        p[8] = ((seq ushr 16) and 0xFF).toByte()
        p[9] = 0x80.toByte()
        body.copyInto(p, 12)
        return frame(p)
    }

    data class Message(val code: Int, val seq: Int, val body: ByteArray)

    /** Parse a MESSAGE payload (bytes after the length prefix). */
    fun parseMessage(payload: ByteArray): Message? {
        if (payload.size < 12 || (payload[0].toInt() and 0xFF) != PKT_MESSAGE) return null
        val code = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
        val seq = (payload[6].toInt() and 0xFF) or
                ((payload[7].toInt() and 0xFF) shl 8) or
                ((payload[8].toInt() and 0xFF) shl 16)
        val body = if (payload.size > 12) payload.copyOfRange(12, payload.size) else ByteArray(0)
        return Message(code, seq, body)
    }

    /** Extract printable ASCII runs (>= minLen chars) from a protobuf body. */
    fun printableStrings(body: ByteArray, minLen: Int = 4): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v in 32..126) {
                cur.append(v.toChar())
            } else {
                if (cur.length >= minLen) out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.length >= minLen) out.add(cur.toString())
        return out
    }

    private val FILE_RE = Regex("""VID_\d{8}_\d{6}""")

    /** The STOP_CAPTURE ACK carries the recorded file path; returns it if present. */
    fun recordedFileFromAck(body: ByteArray): String? =
        printableStrings(body).firstOrNull { FILE_RE.containsMatchIn(it) }

    fun statusName(code: Int): String = when (code) {
        RESP_OK -> "OK"
        RESP_BAD_REQUEST -> "400 Bad Request"
        RESP_ERROR -> "500 Error"
        RESP_NOT_IMPL -> "501 Not Implemented"
        else -> "0x%04X".format(code)
    }
}
