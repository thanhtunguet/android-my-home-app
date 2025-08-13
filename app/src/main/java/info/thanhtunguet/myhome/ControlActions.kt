package info.thanhtunguet.myhome

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

object ControlActions {
    private const val TAG = "ControlActions"

    fun sendWakeOnLan(macAddress: String) {
        try {
            Log.d(TAG, "sendWakeOnLan: mac=$macAddress")
            val macBytes = macAddress.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val packet = ByteArray(6 + (16 * macBytes.size))
            for (i in 0..5) packet[i] = 0xFF.toByte()
            var index = 6
            for (i in 0..15) {
                for (b in macBytes) {
                    packet[index++] = b
                }
            }
            val socket = DatagramSocket().apply { broadcast = true }
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val datagramPacket = DatagramPacket(packet, packet.size, broadcastAddress, 9)
            socket.send(datagramPacket)
            socket.close()
            Log.d(TAG, "Wake-on-LAN packet sent to $macAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Wake-on-LAN packet", e)
            throw e
        }
    }

    fun sendShutdownCommand(ipAddress: String, shutdownCommand: String, port: Int = 10675) {
        Log.d(TAG, "sendShutdownCommand: ip=$ipAddress port=$port")
        // TCP
        try {
            val socket = Socket(ipAddress, port)
            val outputStream = socket.getOutputStream()
            outputStream.write(shutdownCommand.toByteArray())
            outputStream.flush()
            socket.close()
            Log.d(TAG, "Shutdown command sent via TCP")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending shutdown command via TCP", e)
        }
        // UDP
        try {
            val socket = DatagramSocket().apply { broadcast = true }
            val data = shutdownCommand.toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(ipAddress), port)
            socket.send(packet)
            socket.close()
            Log.d(TAG, "Shutdown command sent via UDP")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending shutdown command via UDP", e)
        }
    }

    fun isPcOnline(ipAddress: String, probePort: Int): Boolean {
        Log.d(TAG, "isPcOnline: ip=$ipAddress port=$probePort")
        return try {
            var socket: Socket? = null
            try {
                socket = Socket(ipAddress, probePort)
                true
            } catch (e: Exception) {
                false
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PC status", e)
            false
        }
    }
}


