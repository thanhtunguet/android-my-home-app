package info.thanhtunguet.myhome

import android.content.Context

data class AppConfig(
    val cloudflareApiToken: String,
    val cloudflareApiEmail: String,
    val cloudflareApiKey: String,
    val cloudflareZoneId: String,
    val cloudflareRecordId: String,
    val cloudflareRecordName: String,
    val telegramBotToken: String,
    val telegramChatId: String,
    val pcIpAddress: String,
    val pcMacAddress: String,
    val pcShutdownCommand: String,
    val pcProbePort: Int
) {
    companion object {
        private const val PREFS_NAME = "myhome_prefs"

        fun fromBuildConfig(): AppConfig {
            return AppConfig(
                cloudflareApiToken = BuildConfig.CLOUDFLARE_API_TOKEN,
                cloudflareApiEmail = BuildConfig.CLOUDFLARE_API_EMAIL,
                cloudflareApiKey = BuildConfig.CLOUDFLARE_API_KEY,
                cloudflareZoneId = BuildConfig.CLOUDFLARE_ZONE_ID,
                cloudflareRecordId = BuildConfig.CLOUDFLARE_RECORD_ID,
                cloudflareRecordName = BuildConfig.CLOUDFLARE_RECORD_NAME,
                telegramBotToken = BuildConfig.TELEGRAM_BOT_TOKEN,
                telegramChatId = BuildConfig.TELEGRAM_CHAT_ID,
                pcIpAddress = BuildConfig.PC_IP_ADDRESS,
                pcMacAddress = BuildConfig.PC_MAC_ADDRESS,
                pcShutdownCommand = BuildConfig.PC_SHUTDOWN_COMMAND,
                pcProbePort = 3389
            )
        }

        fun load(context: Context): AppConfig {
            val base = fromBuildConfig()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            fun pref(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
            fun prefInt(key: String, default: Int): Int = prefs.getString(key, null)?.toIntOrNull() ?: default

            return AppConfig(
                cloudflareApiToken = pref("CLOUDFLARE_API_TOKEN") ?: base.cloudflareApiToken,
                cloudflareApiEmail = pref("CLOUDFLARE_API_EMAIL") ?: base.cloudflareApiEmail,
                cloudflareApiKey = pref("CLOUDFLARE_API_KEY") ?: base.cloudflareApiKey,
                cloudflareZoneId = pref("CLOUDFLARE_ZONE_ID") ?: base.cloudflareZoneId,
                cloudflareRecordId = pref("CLOUDFLARE_RECORD_ID") ?: base.cloudflareRecordId,
                cloudflareRecordName = pref("CLOUDFLARE_RECORD_NAME") ?: base.cloudflareRecordName,
                telegramBotToken = pref("TELEGRAM_BOT_TOKEN") ?: base.telegramBotToken,
                telegramChatId = pref("TELEGRAM_CHAT_ID") ?: base.telegramChatId,
                pcIpAddress = pref("PC_IP_ADDRESS") ?: base.pcIpAddress,
                pcMacAddress = pref("PC_MAC_ADDRESS") ?: base.pcMacAddress,
                pcShutdownCommand = pref("PC_SHUTDOWN_COMMAND") ?: base.pcShutdownCommand,
                pcProbePort = prefInt("PC_PROBE_PORT", base.pcProbePort)
            )
        }
    }
}

object ServiceStatus {
    @Volatile var currentPublicIp: String? = null
    @Volatile var currentDnsIp: String? = null
    @Volatile var lastCheckAt: Long = 0L
    @Volatile var nextCheckAt: Long = 0L
    @Volatile var currentPcOnline: Boolean? = null
}