package info.thanhtunguet.myhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.app.UiModeManager
import androidx.appcompat.app.AppCompatDelegate

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting NetworkManagementService")
            try {
                // Prefer per-app dark mode (no special permission)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                // If available (API 31+), also set application night mode via UiModeManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val uiMode = context.getSystemService(UiModeManager::class.java)
                    uiMode?.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to set dark mode on boot", e)
            }
            
            val serviceIntent = Intent(context, NetworkManagementService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}