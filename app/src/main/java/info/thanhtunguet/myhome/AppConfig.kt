package info.thanhtunguet.myhome

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
    val pcShutdownCommand: String
) {
    companion object {
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
                pcShutdownCommand = BuildConfig.PC_SHUTDOWN_COMMAND
            )
        }
    }
}