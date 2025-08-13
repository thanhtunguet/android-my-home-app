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
        ControlActions.sendWakeOnLan(appConfig.pcMacAddress)
    }
    
    private fun sendShutdownCommand() {
        // Send shutdown command via TCP
        serverScope.launch {
            ControlActions.sendShutdownCommand(appConfig.pcIpAddress, appConfig.pcShutdownCommand, 10675)
        }
    }
    
    private fun isPcOnline(): Boolean = ControlActions.isPcOnline(appConfig.pcIpAddress, appConfig.pcProbePort)
    
    private fun sendTelegramMessage(message: String) {
        serverScope.launch {
            // In a real implementation, you would make an HTTP request to the Telegram API
            // For now, we'll just log the message
            Log.d(TAG, "Would send Telegram message: $message")
        }
    }
}