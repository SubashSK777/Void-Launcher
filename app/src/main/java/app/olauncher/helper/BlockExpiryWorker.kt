package app.olauncher.helper

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.showToast

class BlockExpiryWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(context)
        val currentTime = System.currentTimeMillis()
        var hasChanges = false

        // Get current blocked apps and timestamps
        val blockedApps = prefs.blockedApps.toMutableSet()
        val timestamps = prefs.blockedAppsTimestamps.toMutableMap()

        // Check for expired blocks
        val expiredApps = timestamps.filter { it.value <= currentTime }.keys
        if (expiredApps.isNotEmpty()) {
            // Remove expired apps
            blockedApps.removeAll(expiredApps)
            expiredApps.forEach { timestamps.remove(it) }
            
            // Update preferences
            prefs.blockedApps = blockedApps
            prefs.blockedAppsTimestamps = timestamps
            hasChanges = true

            // Notify user about unblocked apps
            expiredApps.forEach { packageName ->
                try {
                    val appName = context.packageManager
                        .getApplicationInfo(packageName, 0)
                        .loadLabel(context.packageManager)
                        .toString()
                    context.showToast(
                        context.getString(R.string.app_unblocked_auto, appName)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return if (hasChanges) Result.success() else Result.success()
    }
} 