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
    private val jsonIgnoreUnknown = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val TAG = "NetworkMgmtService" // Shortened tag name
        private const val CHECK_INTERVAL: Long = 5 * 60 * 1000 // 5 minutes
        const val ACTION_STATUS_UPDATE: String = "info.thanhtunguet.myhome.STATUS_UPDATE"
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
        // Start the HTTP server off the main thread (Service lifecycle callbacks run on main)
        serviceScope.launch { httpServer.start() }
        
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
        // no-op
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
            // Always check PC online state regardless of public IP/DNS results
            try {
                ServiceStatus.currentPcOnline = ControlActions.isPcOnline(appConfig.pcIpAddress, appConfig.pcProbePort)
            } catch (e: Exception) {
                Log.e(TAG, "PC online check failed", e)
                ServiceStatus.currentPcOnline = null
            }
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
        } finally {
            // Always notify UI about latest status
            broadcastStatus()
        }
    }

    private fun broadcastStatus() {
        try {
            val intent = Intent(ACTION_STATUS_UPDATE).apply {
                putExtra("publicIp", ServiceStatus.currentPublicIp)
                putExtra("dnsIp", ServiceStatus.currentDnsIp)
                putExtra("lastCheckAt", ServiceStatus.lastCheckAt)
                putExtra("nextCheckAt", ServiceStatus.nextCheckAt)
                putExtra("pcOnline", ServiceStatus.currentPcOnline)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {
        }
    }
    
    private fun getPublicIpv4Address(): String? {
        val endpoints = listOf(
            "https://api4.ipify.org",
            "https://ipv4.icanhazip.com"
        )
        for (endpoint in endpoints) {
            var connection: HttpsURLConnection? = null
            try {
                val url = URL(endpoint)
                connection = url.openConnection() as HttpsURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.inputStream.bufferedReader().use { reader ->
                    val ip = reader.readLine()?.trim()
                    if (ip != null && isValidIpv4(ip)) {
                        Log.d(TAG, "Public IPv4 source $endpoint -> $ip")
                        return ip
                    } else {
                        Log.w(TAG, "Non-IPv4/empty from $endpoint: '$ip'")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "IPv4 endpoint failed: $endpoint", e)
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
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
            val url = URL("https://dns.google/resolve?name=${appConfig.cloudflareRecordName}&type=A")
            var conn: HttpsURLConnection? = null
            try {
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/json")
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = jsonIgnoreUnknown.decodeFromString(DnsResolveResponse.serializer(), response)
                val answer = parsed.Answer?.firstOrNull { it.type == 1 }
                val ip = answer?.data?.trim()
                if (ip != null && isValidIpv4(ip)) ip else null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current DNS IP", e)
            null
        }
    }
    
    private suspend fun updateDnsRecord(newIp: String) {
        try {
            Log.d(TAG, "Updating Cloudflare DNS A '${appConfig.cloudflareRecordName}' (zone=${appConfig.cloudflareZoneId}, record=${appConfig.cloudflareRecordId}) to $newIp")
            val body = jsonIgnoreUnknown.encodeToString(CloudflareDnsUpdateRequest.serializer(),
                CloudflareDnsUpdateRequest(name = appConfig.cloudflareRecordName, content = newIp)
            )
            val url = URL("https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflareZoneId}/dns_records/${appConfig.cloudflareRecordId}")
            var conn: HttpsURLConnection? = null
            try {
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    // Auth header
                    if (appConfig.cloudflareApiToken.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer ${appConfig.cloudflareApiToken}")
                    } else {
                        if (appConfig.cloudflareApiEmail.isNotEmpty()) setRequestProperty("X-Auth-Email", appConfig.cloudflareApiEmail)
                        if (appConfig.cloudflareApiKey.isNotEmpty()) setRequestProperty("X-Auth-Key", appConfig.cloudflareApiKey)
                    }
                    try {
                        requestMethod = "PATCH"
                    } catch (e: java.net.ProtocolException) {
                        requestMethod = "POST"
                        setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    }
                }
                conn.outputStream.bufferedWriter().use { it.write(body) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "DNS record updated successfully")
                    return
                } else {
                    val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    Log.e(TAG, "Cloudflare update failed (${code}). body=${err ?: "<no body>"}")
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating DNS record", e)
        }
    }
    
    private fun isPcOnline(): Boolean = ControlActions.isPcOnline(appConfig.pcIpAddress, appConfig.pcProbePort)
    
    private suspend fun sendTelegramMessage(message: String) {
        try {
            val payload = "{" +
                "\"chat_id\":\"${appConfig.telegramChatId}\"," +
                "\"text\":\"" + message.replace("\"", "\\\"") + "\"" +
                "}"
            val url = URL("https://api.telegram.org/bot${appConfig.telegramBotToken}/sendMessage")
            var conn: HttpsURLConnection? = null
            try {
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.bufferedWriter().use { it.write(payload) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    Log.e(TAG, "Failed to send Telegram message: ${code} body=${err ?: "<no body>"}")
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Telegram message", e)
        }
    }
}