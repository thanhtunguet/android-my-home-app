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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private val uiRefreshIntervalMs: Long = 90_000
    private lateinit var tvPublicIp: TextView
    private lateinit var tvDnsIp: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var tvPcStatus: TextView
    private lateinit var tvMemory: TextView
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NetworkManagementService.ACTION_STATUS_UPDATE) {
                refreshUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the background service after initial layout to avoid startup jank
        window.decorView.post {
            val serviceIntent = Intent(this, NetworkManagementService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        }

        tvPublicIp = findViewById(R.id.tvPublicIp)
        tvDnsIp = findViewById(R.id.tvDnsIp)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        tvPcStatus = findViewById(R.id.tvPcStatus)
        tvMemory = findViewById(R.id.tvMemory)
        val btnTurnOn = findViewById<Button>(R.id.btnTurnOn)
        val btnTurnOff = findViewById<Button>(R.id.btnTurnOff)
        val btnCheckOnline = findViewById<Button>(R.id.btnCheckOnline)
        // Removed text Settings button in favor of top-right icon
        
        val btnSettingsIcon = findViewById<View>(R.id.btnSettingsIcon)
        val btnOpenPlayStore = findViewById<Button>(R.id.btnOpenPlayStore)
        val btnOpenApps = findViewById<Button>(R.id.btnOpenApps)
        val btnOpenSystemSettings = findViewById<Button>(R.id.btnOpenSystemSettings)

        refreshUi()

        btnTurnOn.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                ControlActions.sendWakeOnLan(cfg.pcMacAddress)
            }
        }
        btnTurnOff.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                ControlActions.sendShutdownCommand(cfg.pcIpAddress, cfg.pcShutdownCommand, 10675)
            }
        }
        btnCheckOnline.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val cfg = AppConfig.load(this@MainActivity)
                val online = ControlActions.isPcOnline(cfg.pcIpAddress, cfg.pcProbePort)
                ServiceStatus.currentPcOnline = online
            }
        }

        val openSettings: (View) -> Unit = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSettingsIcon.setOnClickListener(openSettings)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshUi()
                while (true) {
                    delay(uiRefreshIntervalMs)
                    refreshUi()
                }
            }
        }

        // Receiver will be registered in onStart to align with visibility

        

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

    private fun refreshUi() {
        tvPublicIp.text = ServiceStatus.currentPublicIp ?: "—"
        tvDnsIp.text = ServiceStatus.currentDnsIp ?: "—"
        val nextAt = ServiceStatus.nextCheckAt
        tvNextCheck.text = if (nextAt > 0) java.text.DateFormat.getTimeInstance().format(java.util.Date(nextAt)) else "—"
        tvMemory.text = formatMemoryStatus()
        tvPcStatus.text = when (ServiceStatus.currentPcOnline) {
            true -> "Online"
            false -> "Offline"
            else -> {
                // If unknown, try a one-shot background probe to get a quick answer for the UI
                lifecycleScope.launch(Dispatchers.IO) {
                    val cfg = AppConfig.load(this@MainActivity)
                    val online = ControlActions.isPcOnline(cfg.pcIpAddress, cfg.pcProbePort)
                    ServiceStatus.currentPcOnline = online
                    launch(Dispatchers.Main) { refreshUi() }
                }
                "Unknown"
            }
        }
    }

    private fun formatMemoryStatus(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val avail = mi.availMem
        val total = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) mi.totalMem else 0L
        return humanReadableBytes(avail) + " / " + (if (total > 0) humanReadableBytes(total) else "?")
    }

    private fun humanReadableBytes(bytes: Long): String {
        val unit = 1024
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), pre)
    }

    override fun onStart() {
        super.onStart()
        try { registerReceiver(statusReceiver, IntentFilter(NetworkManagementService.ACTION_STATUS_UPDATE)) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }
}