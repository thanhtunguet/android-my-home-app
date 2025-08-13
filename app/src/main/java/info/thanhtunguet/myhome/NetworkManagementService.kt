package info.thanhtunguet.myhome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class NetworkManagementService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = 1
    private val channelId = "MyHomeServiceChannel"
    private lateinit var appConfig: AppConfig
    private lateinit var httpServer: HttpServer
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    
    companion object {
        private const val TAG = "NetworkMgmtService" // Shortened tag name
        private const val CHECK_INTERVAL: Long = 60 * 1000 // 1 minute
    }
    
    override fun onCreate() {
        super.onCreate()
        appConfig = AppConfig.fromBuildConfig()
        httpServer = HttpServer(appConfig)
        createNotificationChannel()
        startForeground(notificationId, createNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the HTTP server
        httpServer.start()
        
        // Start the periodic DNS check
        serviceScope.launch {
            while (true) {
                try {
                    checkAndUpdateDns()
                    delay(CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in service loop", e)
                    delay(CHECK_INTERVAL)
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        httpServer.stop()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MyHome Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MyHome Service")
            .setContentText("Managing your home network")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }
    
    private suspend fun checkAndUpdateDns() {
        try {
            val currentIp = getPublicIpAddress()
            if (currentIp != null) {
                val dnsIp = getCurrentDnsIp()
                if (currentIp != dnsIp) {
                    updateDnsRecord(currentIp)
                    sendTelegramMessage("Public IP changed from $dnsIp to $currentIp")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking and updating DNS", e)
        }
    }
    
    private fun getPublicIpAddress(): String? {
        return try {
            val url = URL("https://icanhazip.com")
            val connection = url.openConnection() as HttpsURLConnection
            connection.inputStream.bufferedReader().readLine()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting public IP", e)
            null
        }
    }
    
    private suspend fun getCurrentDnsIp(): String? {
        return try {
            val response: String = httpClient.get("https://cloudflare-dns.com/dns-query") {
                url {
                    parameters.append("name", appConfig.cloudflareRecordName)
                    parameters.append("type", "A")
                }
                headers {
                    append("accept", "application/dns-json")
                }
            }.body()
            
            // Parse the JSON response to extract the IP
            // This is a simplified parsing, in reality you'd want to use a proper JSON library
            val answerStart = response.indexOf("\"Answer\":")
            if (answerStart != -1) {
                val dataStart = response.indexOf("\"data\":\"", answerStart)
                if (dataStart != -1) {
                    val ipStart = dataStart + 8 // Length of "\"data\":\""
                    val ipEnd = response.indexOf("\"", ipStart)
                    if (ipEnd != -1) {
                        return response.substring(ipStart, ipEnd)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current DNS IP", e)
            null
        }
    }
    
    private suspend fun updateDnsRecord(newIp: String) {
        try {
            val response = httpClient.patch("https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflareZoneId}/dns_records/${appConfig.cloudflareRecordId}") {
                headers {
                    append("Authorization", "Bearer ${appConfig.cloudflareApiToken}")
                    append("Content-Type", "application/json")
                }
                setBody(mapOf(
                    "type" to "A",
                    "name" to appConfig.cloudflareRecordName,
                    "content" to newIp,
                    "ttl" to 120
                ))
            }
            
            if (response.status == HttpStatusCode.OK) {
                Log.d(TAG, "DNS record updated successfully")
            } else {
                Log.e(TAG, "Failed to update DNS record: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating DNS record", e)
        }
    }
    
    private fun isPcOnline(): Boolean {
        return try {
            // Try SSH port first
            var socket: Socket? = null
            try {
                socket = Socket(appConfig.pcIpAddress, 22)
                return true
            } catch (e: Exception) {
                // SSH failed, try RDP
                try {
                    socket = Socket(appConfig.pcIpAddress, 3389)
                    return true
                } catch (e: Exception) {
                    return false
                }
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PC status", e)
            false
        }
    }
    
    private suspend fun sendTelegramMessage(message: String) {
        try {
            val response = httpClient.post("https://api.telegram.org/bot${appConfig.telegramBotToken}/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "chat_id" to appConfig.telegramChatId,
                    "text" to message
                ))
            }
            
            if (response.status != HttpStatusCode.OK) {
                Log.e(TAG, "Failed to send Telegram message: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Telegram message", e)
        }
    }
}