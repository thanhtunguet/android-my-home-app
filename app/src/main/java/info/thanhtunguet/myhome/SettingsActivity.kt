package info.thanhtunguet.myhome

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("myhome_prefs", MODE_PRIVATE)

        fun bind(id: Int, key: String): EditText {
            val et = findViewById<EditText>(id)
            et.setText(prefs.getString(key, "") ?: "")
            return et
        }

        val etToken = bind(R.id.etCfToken, "CLOUDFLARE_API_TOKEN")
        val etEmail = bind(R.id.etCfEmail, "CLOUDFLARE_API_EMAIL")
        val etKey = bind(R.id.etCfKey, "CLOUDFLARE_API_KEY")
        val etZone = bind(R.id.etCfZone, "CLOUDFLARE_ZONE_ID")
        val etRecordId = bind(R.id.etCfRecordId, "CLOUDFLARE_RECORD_ID")
        val etRecordName = bind(R.id.etCfRecordName, "CLOUDFLARE_RECORD_NAME")

        val etTgToken = bind(R.id.etTgToken, "TELEGRAM_BOT_TOKEN")
        val etTgChat = bind(R.id.etTgChat, "TELEGRAM_CHAT_ID")

        val etPcIp = bind(R.id.etPcIp, "PC_IP_ADDRESS")
        val etPcMac = bind(R.id.etPcMac, "PC_MAC_ADDRESS")
        val etPcPort = bind(R.id.etPcPort, "PC_PROBE_PORT")
        val etShutdown = bind(R.id.etPcShutdown, "PC_SHUTDOWN_COMMAND")

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit()
                .putString("CLOUDFLARE_API_TOKEN", etToken.text.toString().trim())
                .putString("CLOUDFLARE_API_EMAIL", etEmail.text.toString().trim())
                .putString("CLOUDFLARE_API_KEY", etKey.text.toString().trim())
                .putString("CLOUDFLARE_ZONE_ID", etZone.text.toString().trim())
                .putString("CLOUDFLARE_RECORD_ID", etRecordId.text.toString().trim())
                .putString("CLOUDFLARE_RECORD_NAME", etRecordName.text.toString().trim())
                .putString("TELEGRAM_BOT_TOKEN", etTgToken.text.toString().trim())
                .putString("TELEGRAM_CHAT_ID", etTgChat.text.toString().trim())
                .putString("PC_IP_ADDRESS", etPcIp.text.toString().trim())
                .putString("PC_MAC_ADDRESS", etPcMac.text.toString().trim())
                .putString("PC_PROBE_PORT", etPcPort.text.toString().trim())
                .putString("PC_SHUTDOWN_COMMAND", etShutdown.text.toString().trim())
                .apply()
            finish()
        }
    }
}


