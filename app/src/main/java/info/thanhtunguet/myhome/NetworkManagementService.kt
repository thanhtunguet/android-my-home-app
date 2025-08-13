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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@Serializable
data class CloudflareDnsUpdateRequest(
    val type: String = "A",
    val name: String,
    val content: String,
    val ttl: Int = 120,
    val proxied: Boolean = false
)

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
    private val jsonIgnoreUnknown = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val TAG = "NetworkMgmtService" // Shortened tag name
        private const val CHECK_INTERVAL: Long = 5 * 60 * 1000 // 5 minutes
    }
    
    override fun onCreate() {
        super.onCreate()
        appConfig = AppConfig.load(this)
        // Log which Cloudflare auth method will be used (masked values)
        val tokenPreview = if (appConfig.cloudflareApiToken.isNotEmpty()) appConfig.cloudflareApiToken.take(6) + "***" else "<empty>"
        val keyPreview = if (appConfig.cloudflareApiKey.isNotEmpty()) appConfig.cloudflareApiKey.take(6) + "***" else "<empty>"
        val emailPreview = if (appConfig.cloudflareApiEmail.isNotEmpty()) appConfig.cloudflareApiEmail else "<empty>"
        val authMethod = if (appConfig.cloudflareApiToken.isNotEmpty()) "API_TOKEN" else if (appConfig.cloudflareApiKey.isNotEmpty()) "GLOBAL_KEY" else "NONE"
        Log.d(TAG, "Cloudflare auth: method=$authMethod token=$tokenPreview key=$keyPreview email=$emailPreview")
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
            // Reload latest user settings before each cycle
            appConfig = AppConfig.load(this)
            ServiceStatus.lastCheckAt = System.currentTimeMillis()
            ServiceStatus.nextCheckAt = ServiceStatus.lastCheckAt + CHECK_INTERVAL
            val currentIp = getPublicIpv4Address()
            if (currentIp == null) {
                Log.w(TAG, "Public IPv4 not available; skipping DNS check")
                ServiceStatus.currentPublicIp = null
                return
            } else {
                Log.d(TAG, "Current public IPv4: $currentIp")
                ServiceStatus.currentPublicIp = currentIp
            }

            val dnsIp = getCurrentDnsIp()
            if (dnsIp == null) {
                Log.w(TAG, "DNS A record for ${appConfig.cloudflareRecordName} not found or not IPv4")
                ServiceStatus.currentDnsIp = null
            } else {
                Log.d(TAG, "Current DNS A for ${appConfig.cloudflareRecordName}: $dnsIp")
                ServiceStatus.currentDnsIp = dnsIp
            }

            if (dnsIp != null && currentIp != dnsIp) {
                Log.d(TAG, "IP mismatch; updating Cloudflare: ${dnsIp} -> $currentIp")
                updateDnsRecord(currentIp)
                sendTelegramMessage("Public IP changed from ${dnsIp} to $currentIp")
            } else if (dnsIp == null) {
                Log.w(TAG, "Skipping update because current DNS A could not be determined")
            } else {
                Log.d(TAG, "No change; public IP matches DNS: $currentIp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking and updating DNS", e)
        }
    }
    
    private fun getPublicIpv4Address(): String? {
        val endpoints = listOf(
            "https://api4.ipify.org",
            "https://ipv4.icanhazip.com"
        )
        for (endpoint in endpoints) {
            try {
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpsURLConnection
                val ip = connection.inputStream.bufferedReader().readLine()?.trim()
                if (ip != null && isValidIpv4(ip)) {
                    Log.d(TAG, "Public IPv4 source $endpoint -> $ip")
                    return ip
                } else {
                    Log.w(TAG, "Non-IPv4/empty from $endpoint: '$ip'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "IPv4 endpoint failed: $endpoint", e)
            }
        }
        Log.e(TAG, "All IPv4 endpoints failed")
        return null
    }

    private fun isValidIpv4(ip: String): Boolean {
        val regex = Regex("^(25[0-5]|2[0-4]\\d|[0-1]?\\d{1,2})(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d{1,2})){3}$")
        return regex.matches(ip)
    }
    
    @Serializable
    private data class DnsAnswer(val name: String, val type: Int, val TTL: Int? = null, val data: String)
    @Serializable
    private data class DnsResolveResponse(val Status: Int? = null, val Answer: List<DnsAnswer>? = null)

    private suspend fun getCurrentDnsIp(): String? {
        return try {
            val responseText: String = httpClient.get("https://dns.google/resolve") {
                url {
                    parameters.append("name", appConfig.cloudflareRecordName)
                    parameters.append("type", "A")
                }
                headers {
                    append("accept", "application/json")
                }
            }.body()
            val parsed = jsonIgnoreUnknown.decodeFromString(DnsResolveResponse.serializer(), responseText)
            val answer = parsed.Answer?.firstOrNull { it.type == 1 }
            val ip = answer?.data?.trim()
            if (ip != null && isValidIpv4(ip)) ip else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current DNS IP", e)
            null
        }
    }
    
    private suspend fun updateDnsRecord(newIp: String) {
        try {
            Log.d(TAG, "Updating Cloudflare DNS A '${appConfig.cloudflareRecordName}' (zone=${appConfig.cloudflareZoneId}, record=${appConfig.cloudflareRecordId}) to $newIp")
            val body = CloudflareDnsUpdateRequest(
                name = appConfig.cloudflareRecordName,
                content = newIp
            )

            // Attempt with API Token first if present, else with Global Key
            val triedToken = appConfig.cloudflareApiToken.isNotEmpty()
            val responsePrimary = httpClient.patch("https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflareZoneId}/dns_records/${appConfig.cloudflareRecordId}") {
                headers {
                    append("Accept", "application/json")
                    if (triedToken) {
                        append("Authorization", "Bearer ${appConfig.cloudflareApiToken}")
                    } else {
                        if (appConfig.cloudflareApiEmail.isNotEmpty()) append("X-Auth-Email", appConfig.cloudflareApiEmail)
                        if (appConfig.cloudflareApiKey.isNotEmpty()) append("X-Auth-Key", appConfig.cloudflareApiKey)
                    }
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (responsePrimary.status == HttpStatusCode.OK) {
                Log.d(TAG, "DNS record updated successfully")
                return
            }

            val primaryError = try { responsePrimary.body<String>() } catch (e: Exception) { "<no body>" }
            Log.e(TAG, "Cloudflare update failed (primary ${responsePrimary.status}). body=${primaryError}")

            val authError = primaryError.contains("\"code\":10001") || primaryError.contains("Unable to authenticate request", ignoreCase = true)
            val canTryFallback = (triedToken && (appConfig.cloudflareApiEmail.isNotEmpty() || appConfig.cloudflareApiKey.isNotEmpty())) || (!triedToken && appConfig.cloudflareApiToken.isNotEmpty())
            if (authError && canTryFallback) {
                // Retry with the alternative auth method
                val usingTokenNow = !triedToken
                Log.d(TAG, "Retrying Cloudflare update with ${if (usingTokenNow) "API_TOKEN" else "GLOBAL_KEY"} auth")
                val responseFallback = httpClient.patch("https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflareZoneId}/dns_records/${appConfig.cloudflareRecordId}") {
                    headers {
                        append("Accept", "application/json")
                        if (usingTokenNow) {
                            append("Authorization", "Bearer ${appConfig.cloudflareApiToken}")
                        } else {
                            if (appConfig.cloudflareApiEmail.isNotEmpty()) append("X-Auth-Email", appConfig.cloudflareApiEmail)
                            if (appConfig.cloudflareApiKey.isNotEmpty()) append("X-Auth-Key", appConfig.cloudflareApiKey)
                        }
                    }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (responseFallback.status == HttpStatusCode.OK) {
                    Log.d(TAG, "DNS record updated successfully (fallback auth)")
                    return
                } else {
                    val fbErr = try { responseFallback.body<String>() } catch (e: Exception) { "<no body>" }
                    Log.e(TAG, "Cloudflare update failed (fallback ${responseFallback.status}). body=${fbErr}")
                }
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
                val errorBody = try { response.body<String>() } catch (e: Exception) { "<no body>" }
                Log.e(TAG, "Failed to send Telegram message: ${response.status} body=${errorBody}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Telegram message", e)
        }
    }
}