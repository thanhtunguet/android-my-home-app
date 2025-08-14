package info.thanhtunguet.myhome

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import javax.net.ssl.SSLHandshakeException

class HttpServer(private val appConfig: AppConfig) {
    private val serverJob = SupervisorJob()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)
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
        try { serverSocket?.close() } catch (e: Exception) { Log.e(TAG, "Error closing server socket", e) }
        serverSocket = null
        serverJob.cancel()
        Log.d(TAG, "HTTP server stopped")
    }
    
    private fun handleRequest(clientSocket: Socket) {
        try { clientSocket.soTimeout = 5000 } catch (_: Exception) {}
        serverScope.launch {
            try {
                var reader: BufferedReader? = null
                var writer: PrintWriter? = null
                reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.UTF_8))
                writer = PrintWriter(OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8), /*autoFlush=*/true)
                
                // Read the request line with timeout handling
                val requestLine = try {
                    reader.readLine()
                } catch (toe: java.net.SocketTimeoutException) {
                    // Timed out waiting for the first line → reply 408
                    writer?.let { sendResponse(it, 408, "Request Timeout", "") }
                    return@launch
                }
                Log.d(TAG, "Received request: $requestLine")
                
                // Parse the request
                if (requestLine == null || requestLine.isBlank()) {
                    writer?.let { handleNotFound(it) }
                } else {
                    val parts = requestLine.split(" ")
                    if (parts.size >= 2) {
                        val method = parts[0]
                        val path = parts[1]
                    
                        when (path) {
                            "/turn-on" -> writer?.let { handleTurnOn(it) }
                            "/turn-off" -> writer?.let { handleTurnOff(it) }
                            "/is-online" -> writer?.let { handleIsOnline(it) }
                            else -> writer?.let { handleNotFound(it) }
                        }
                    } else {
                        writer?.let { handleNotFound(it) }
                    }
                }
                
                try { writer?.flush() } catch (_: Exception) {}
                try { writer?.close() } catch (_: Exception) {}
                try { reader?.close() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            } catch (e: SSLHandshakeException) {
                // A client attempted to speak HTTPS/TLS to this HTTP-only server; close quietly
                Log.w(TAG, "HTTPS/TLS connection attempted; closing")
                try { clientSocket.close() } catch (_: Exception) {}
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