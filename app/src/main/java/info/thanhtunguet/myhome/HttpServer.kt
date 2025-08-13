package info.thanhtunguet.myhome

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class HttpServer(private val appConfig: AppConfig) {
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    
    companion object {
        private const val TAG = "HttpServer"
        private const val PORT = 8080
    }
    
    fun start() {
        if (isRunning) return
        
        isRunning = true
        serverScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "HTTP server started on port $PORT")
                
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let {
                            handleRequest(it)
                        }
                    } catch (e: SocketException) {
                        // This is expected when the server is stopped
                        if (isRunning) {
                            Log.e(TAG, "Socket error", e)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling client request", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting HTTP server", e)
            }
        }
    }
    
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        Log.d(TAG, "HTTP server stopped")
    }
    
    private fun handleRequest(clientSocket: Socket) {
        serverScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(OutputStreamWriter(clientSocket.getOutputStream()))
                
                // Read the request line
                val requestLine = reader.readLine()
                Log.d(TAG, "Received request: $requestLine")
                
                // Parse the request
                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0]
                    val path = parts[1]
                    
                    when (path) {
                        "/turn-on" -> handleTurnOn(writer)
                        "/turn-off" -> handleTurnOff(writer)
                        "/is-online" -> handleIsOnline(writer)
                        else -> handleNotFound(writer)
                    }
                } else {
                    handleNotFound(writer)
                }
                
                writer.close()
                reader.close()
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request", e)
            }
        }
    }
    
    private fun handleTurnOn(writer: PrintWriter) {
        try {
            sendWakeOnLan()
            sendTelegramMessage("PC is turning ON")
            sendResponse(writer, 200, "OK", "PC turn on command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error turning on PC", e)
            sendResponse(writer, 500, "Internal Server Error", "Error: ${e.message}")
        }
    }
    
    private fun handleTurnOff(writer: PrintWriter) {
        try {
            sendShutdownCommand()
            sendTelegramMessage("PC is turning OFF")
            sendResponse(writer, 200, "OK", "PC turn off command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error turning off PC", e)
            sendResponse(writer, 500, "Internal Server Error", "Error: ${e.message}")
        }
    }
    
    private fun handleIsOnline(writer: PrintWriter) {
        try {
            val isOnline = isPcOnline()
            ServiceStatus.currentPcOnline = isOnline
            sendResponse(writer, 200, "OK", if (isOnline) "true" else "false")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PC status", e)
            sendResponse(writer, 500, "Internal Server Error", "Error: ${e.message}")
        }
    }
    
    private fun handleNotFound(writer: PrintWriter) {
        sendResponse(writer, 404, "Not Found", "Endpoint not found")
    }
    
    private fun sendResponse(writer: PrintWriter, statusCode: Int, statusText: String, body: String) {
        writer.println("HTTP/1.1 $statusCode $statusText")
        writer.println("Content-Type: text/plain")
        writer.println("Content-Length: ${body.length}")
        writer.println()
        writer.println(body)
        writer.flush()
    }
    
    private fun sendWakeOnLan() {
        try {
            val macBytes = appConfig.pcMacAddress.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val packet = ByteArray(6 + (16 * macBytes.size))
            
            // First 6 bytes are 0xFF
            for (i in 0..5) {
                packet[i] = 0xFF.toByte()
            }
            
            // Repeat MAC address 16 times
            var index = 6
            for (i in 0..15) {
                for (j in macBytes.indices) {
                    packet[index] = macBytes[j]
                    index++
                }
            }
            
            val socket = DatagramSocket()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val datagramPacket = DatagramPacket(packet, packet.size, broadcastAddress, 9)
            socket.send(datagramPacket)
            socket.close()
            
            Log.d(TAG, "Wake-on-LAN packet sent to ${appConfig.pcMacAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Wake-on-LAN packet", e)
            throw e
        }
    }
    
    private fun sendShutdownCommand() {
        // Send shutdown command via TCP
        serverScope.launch {
            try {
				val socket = Socket(appConfig.pcIpAddress, 10675)
                val outputStream = socket.getOutputStream()
                outputStream.write(appConfig.pcShutdownCommand.toByteArray())
                outputStream.flush()
                socket.close()
                Log.d(TAG, "Shutdown command sent via TCP")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending shutdown command via TCP", e)
            }
        }
        
        // Send shutdown command via UDP
        serverScope.launch {
            try {
                val socket = DatagramSocket()
                val data = appConfig.pcShutdownCommand.toByteArray()
				val packet = DatagramPacket(data, data.size, InetAddress.getByName(appConfig.pcIpAddress), 10675)
                socket.send(packet)
                socket.close()
                Log.d(TAG, "Shutdown command sent via UDP")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending shutdown command via UDP", e)
            }
        }
    }
    
	private fun isPcOnline(): Boolean {
		return try {
			var socket: Socket? = null
			try {
				socket = Socket(appConfig.pcIpAddress, 3389)
				true
			} catch (e: Exception) {
				false
			} finally {
				socket?.close()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error checking PC status", e)
			false
		}
	}
    
    private fun sendTelegramMessage(message: String) {
        serverScope.launch {
            // In a real implementation, you would make an HTTP request to the Telegram API
            // For now, we'll just log the message
            Log.d(TAG, "Would send Telegram message: $message")
        }
    }
}