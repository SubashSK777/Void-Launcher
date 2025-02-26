package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.ui.MainActivity

class MyAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        prefs = Prefs(applicationContext)
        prefs.lockModeOn = true
        super.onServiceConnected()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            
            // Handle lock screen action
            if ((source.className == "android.widget.FrameLayout") and
                (source.contentDescription == getString(R.string.lock_layout_description))
            ) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                return
            }

            // Check if current app is blocked
            val packageName = event.packageName?.toString() ?: return
            if (isAppBlocked(packageName)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockedAppDialog(packageName)
            }
        } catch (e: Exception) {
            return
        }
    }

    private fun isAppBlocked(packageName: String): Boolean {
        val blockedApps = prefs.blockedApps
        if (!blockedApps.contains(packageName)) return false

        val timestamps = prefs.blockedAppsTimestamps
        val blockEndTime = timestamps[packageName] ?: return false
        
        if (blockEndTime < System.currentTimeMillis()) {
            // Block expired, remove from blocked apps
            val newBlockedApps = blockedApps.toMutableSet()
            newBlockedApps.remove(packageName)
            prefs.blockedApps = newBlockedApps
            
            val newTimestamps = timestamps.toMutableMap()
            newTimestamps.remove(packageName)
            prefs.blockedAppsTimestamps = newTimestamps
            return false
        }
        return true
    }

    private fun showBlockedAppDialog(packageName: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("show_blocked_dialog", true)
            putExtra("blocked_package", packageName)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {

    }
}