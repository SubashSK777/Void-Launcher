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

        // Check for expired blocks and manage breaks
        val expiredApps = timestamps.filter { it.value <= currentTime }.keys
        if (expiredApps.isNotEmpty()) {
            expiredApps.forEach { packageName ->
                if (!prefs.breaksDisabled) {
                    // Calculate next block period based on break settings
                    val breakInterval = prefs.breakInterval * 60 * 60 * 1000L // Convert hours to milliseconds
                    val breakDuration = prefs.breakDuration * 60 * 1000L // Convert minutes to milliseconds
                    
                    // If the app was just unblocked, schedule the next block after the break
                    val nextBlockStart = currentTime + breakDuration
                    val nextBlockEnd = nextBlockStart + breakInterval
                    
                    // Update timestamps for the next block period
                    timestamps[packageName] = nextBlockEnd
                    
                    // Notify user about the break
                    try {
                        val appName = context.packageManager
                            .getApplicationInfo(packageName, 0)
                            .loadLabel(context.packageManager)
                            .toString()
                        context.showToast(
                            context.getString(R.string.app_unblocked_auto, appName) + 
                            " for ${prefs.breakDuration} minutes"
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    // If breaks are disabled, just remove the app from blocked list
                    blockedApps.remove(packageName)
                    timestamps.remove(packageName)
                    
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
            
            // Update preferences
            prefs.blockedApps = blockedApps
            prefs.blockedAppsTimestamps = timestamps
            hasChanges = true
        }

        return if (hasChanges) Result.success() else Result.success()
    }
} 