package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import app.olauncher.MainActivity
import app.olauncher.data.Prefs
import app.olauncher.helper.showToast
import java.util.concurrent.TimeUnit

class BreakManager(private val context: Context) {
    private val PREFS_NAME = "break_manager_prefs"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var toastRunnables = mutableMapOf<String, Runnable>()
    
    companion object {
        private const val KEY_BREAK_USAGE = "break_usage"  // Map of packageName to used break time
        private const val KEY_LAST_BREAK_START = "last_break_start"  // Map of packageName to last break start time
        private const val KEY_REMAINING_BREAK_TIME = "remaining_break_time"  // Map of packageName to remaining break time
        private const val NOTIFICATION_INTERVAL = 5 * 60 * 1000L // 5 minutes in milliseconds
    }

    // Get remaining block time for an app
    fun getRemainingBlockTime(packageName: String): Long {
        val appPrefs = Prefs(context)
        val timestamps = appPrefs.blockedAppsTimestamps
        val blockEndTime = timestamps[packageName] ?: return 0L
        return maxOf(0L, blockEndTime - System.currentTimeMillis())
    }

    // Format remaining time as hours and minutes
    fun formatRemainingTime(remainingTime: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(remainingTime)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60
        return when {
            hours > 0 -> "$hours hours ${if (minutes > 0) "$minutes minutes" else ""}"
            minutes > 0 -> "$minutes minutes"
            else -> "less than a minute"
        }
    }

    // Check if app is currently in a break period
    fun isInBreakPeriod(packageName: String): Boolean {
        val appPrefs = Prefs(context)
        if (appPrefs.breaksDisabled) return false

        val lastBreakStart = getLastBreakStart(packageName)
        val breakDuration = appPrefs.breakDuration * 60 * 1000L // Convert to milliseconds
        val currentTime = System.currentTimeMillis()

        return currentTime - lastBreakStart < breakDuration
    }

    // Start a break period for an app
    fun startBreak(packageName: String) {
        val currentTime = System.currentTimeMillis()
        setLastBreakStart(packageName, currentTime)
        
        // Initialize remaining break time if not set
        if (getRemainingBreakTime(packageName) == 0L) {
            val appPrefs = Prefs(context)
            val breakDuration = appPrefs.breakDuration * 60 * 1000L // Convert to milliseconds
            setRemainingBreakTime(packageName, breakDuration)
            scheduleBreakNotifications(packageName, breakDuration)
        }
    }

    // Update break usage time
    fun updateBreakUsage(packageName: String, usageTime: Long) {
        val remainingTime = getRemainingBreakTime(packageName)
        val newRemainingTime = maxOf(0L, remainingTime - usageTime)
        setRemainingBreakTime(packageName, newRemainingTime)
    }

    // Get remaining break time for an app
    fun getRemainingBreakTime(packageName: String): Long {
        val breakTimesStr = prefs.getString(KEY_REMAINING_BREAK_TIME, "{}")
        val breakTimes = breakTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        return breakTimes[packageName] ?: 0L
    }

    private fun setRemainingBreakTime(packageName: String, remainingTime: Long) {
        val breakTimesStr = prefs.getString(KEY_REMAINING_BREAK_TIME, "{}")
        val breakTimes = breakTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        breakTimes[packageName] = remainingTime
        prefs.edit().putString(KEY_REMAINING_BREAK_TIME, formatBreakTimes(breakTimes)).apply()
    }

    private fun getLastBreakStart(packageName: String): Long {
        val breakStartsStr = prefs.getString(KEY_LAST_BREAK_START, "{}")
        val breakStarts = breakStartsStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        return breakStarts[packageName] ?: 0L
    }

    private fun setLastBreakStart(packageName: String, startTime: Long) {
        val breakStartsStr = prefs.getString(KEY_LAST_BREAK_START, "{}")
        val breakStarts = breakStartsStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        breakStarts[packageName] = startTime
        prefs.edit().putString(KEY_LAST_BREAK_START, formatBreakTimes(breakStarts)).apply()
    }

    // Helper functions to parse and format break times
    private fun parseBreakTimes(json: String): MutableMap<String, Long> {
        return try {
            json.removeSurrounding("{", "}")
                .split(",")
                .filter { it.isNotEmpty() }
                .map { 
                    val parts = it.split(":")
                    parts[0].trim() to parts[1].trim().toLong()
                }
                .toMap()
                .toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun formatBreakTimes(map: Map<String, Long>): String {
        return map.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }
    }

    private fun scheduleBreakNotifications(packageName: String, totalBreakTime: Long) {
        // Cancel any existing notifications for this package
        cancelBreakNotifications(packageName)

        val runnable = object : Runnable {
            override fun run() {
                val remainingTime = getRemainingBreakTime(packageName)
                if (remainingTime <= 0) {
                    showBreakEndedDialog(packageName)
                    return
                }

                when {
                    remainingTime <= 60 * 1000 -> { // Less than 1 minute
                        context.showToast("Break time ending in 1 minute")
                        handler.postDelayed(this, remainingTime)
                    }
                    remainingTime <= 5 * 60 * 1000 -> { // Less than 5 minutes
                        context.showToast("${formatRemainingTime(remainingTime)} remaining")
                        handler.postDelayed(this, 60 * 1000) // Check every minute
                    }
                    else -> {
                        context.showToast("${formatRemainingTime(remainingTime)} remaining")
                        handler.postDelayed(this, NOTIFICATION_INTERVAL)
                    }
                }
            }
        }

        toastRunnables[packageName] = runnable
        handler.postDelayed(runnable, NOTIFICATION_INTERVAL)
    }

    private fun cancelBreakNotifications(packageName: String) {
        toastRunnables[packageName]?.let { runnable ->
            handler.removeCallbacks(runnable)
            toastRunnables.remove(packageName)
        }
    }

    private fun showBreakEndedDialog(packageName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_break_ended_dialog", true)
            putExtra("package_name", packageName)
        }
        context.startActivity(intent)
    }

    fun stopBreak(packageName: String) {
        cancelBreakNotifications(packageName)
        setRemainingBreakTime(packageName, 0L)
    }

    override fun onDestroy() {
        toastRunnables.forEach { (_, runnable) ->
            handler.removeCallbacks(runnable)
        }
        toastRunnables.clear()
    }
} 