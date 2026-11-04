package com.wledambilight.tv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends colors to WLED using the DNRGB realtime UDP protocol (port 21324). Not for the main thread. */
class WledSender {
    private val socket = DatagramSocket()
    private var host: String? = null
    private var address: InetAddress? = null

    fun send(ip: String, rgb: ByteArray, timeoutSec: Int = 2) {
        if (ip != host || address == null) {
            address = InetAddress.getByName(ip)
            host = ip
        }
        val total = rgb.size / 3
        var start = 0
        while (start < total) {
            val n = minOf(CHUNK, total - start)
            val buf = ByteArray(4 + n * 3)
            buf[0] = 4 // DNRGB
            buf[1] = timeoutSec.toByte()
            buf[2] = (start shr 8).toByte()
            buf[3] = start.toByte()
            System.arraycopy(rgb, start * 3, buf, 4, n * 3)
            socket.send(DatagramPacket(buf, buf.size, address, PORT))
            start += n
        }
    }

    fun close() {
        socket.close()
    }

    companion object {
        const val PORT = 21324
        const val CHUNK = 489
    }
}
