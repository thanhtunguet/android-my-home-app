package info.thanhtunguet.myhome

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import android.view.Menu
import android.view.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the background service
        val serviceIntent = Intent(this, NetworkManagementService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)

        val tvPublicIp = findViewById<TextView>(R.id.tvPublicIp)
        val tvDnsIp = findViewById<TextView>(R.id.tvDnsIp)
        val tvNextCheck = findViewById<TextView>(R.id.tvNextCheck)
        val tvPcStatus = findViewById<TextView>(R.id.tvPcStatus)
        val btnTurnOn = findViewById<Button>(R.id.btnTurnOn)
        val btnTurnOff = findViewById<Button>(R.id.btnTurnOff)
        val btnCheckOnline = findViewById<Button>(R.id.btnCheckOnline)
        // Removed text Settings button in favor of top-right icon
        
        val btnSettingsIcon = findViewById<View>(R.id.btnSettingsIcon)
        val btnOpenPlayStore = findViewById<Button>(R.id.btnOpenPlayStore)
        val btnOpenApps = findViewById<Button>(R.id.btnOpenApps)
        val btnOpenSystemSettings = findViewById<Button>(R.id.btnOpenSystemSettings)

        fun refreshUi() {
            tvPublicIp.text = ServiceStatus.currentPublicIp ?: "—"
            tvDnsIp.text = ServiceStatus.currentDnsIp ?: "—"
            val nextAt = ServiceStatus.nextCheckAt
            tvNextCheck.text = if (nextAt > 0) java.text.DateFormat.getTimeInstance().format(java.util.Date(nextAt)) else "—"
            tvPcStatus.text = when (ServiceStatus.currentPcOnline) {
                true -> "Online"
                false -> "Offline"
                else -> "Unknown"
            }
        }

        btnTurnOn.setOnClickListener {
            uiScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                ControlActions.sendWakeOnLan(cfg.pcMacAddress)
            }
        }
        btnTurnOff.setOnClickListener {
            uiScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                ControlActions.sendShutdownCommand(cfg.pcIpAddress, cfg.pcShutdownCommand, 10675)
            }
        }
        btnCheckOnline.setOnClickListener {
            uiScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                val online = ControlActions.isPcOnline(cfg.pcIpAddress, cfg.pcProbePort)
                ServiceStatus.currentPcOnline = online
            }
        }

        val openSettings: (View) -> Unit = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSettingsIcon.setOnClickListener(openSettings)

        uiScope.launch {
            while (true) {
                refreshUi()
                delay(1000)
            }
        }

        

        btnOpenPlayStore.setOnClickListener {
            // Try launch Play Store app
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage("com.android.vending")
            if (intent != null) startActivity(intent) else startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://play.google.com/store")
            })
        }

        btnOpenApps.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        btnOpenSystemSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Removed local HTTP calls; actions are now invoked directly

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}