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
        private const val KEY_CARRYOVER_TIME = "carryover_time"
        private const val KEY_NEXT_BREAK_TIME = "next_break_time"
        private const val NOTIFICATION_INTERVAL = 5 * 60 * 1000L // 5 minutes in milliseconds
        private const val MAX_CARRYOVER_TIME = 45 * 60 * 1000L // 45 minutes in milliseconds
        private const val BREAK_CHECK_INTERVAL = 60 * 1000L // 1 minute in milliseconds
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

    // Check if breaks are disabled for a specific app
    fun areBreaksDisabledForApp(packageName: String): Boolean {
        val appPrefs = Prefs(context)
        return appPrefs.appsWithDisabledBreaks.contains(packageName)
    }

    // Enable or disable breaks for a specific app
    fun setBreaksDisabledForApp(packageName: String, disabled: Boolean) {
        val appPrefs = Prefs(context)
        val currentDisabledApps = appPrefs.appsWithDisabledBreaks.toMutableSet()
        
        if (disabled) {
            currentDisabledApps.add(packageName)
            // Cancel any active break if disabling breaks
            stopBreak(packageName)
        } else {
            currentDisabledApps.remove(packageName)
            // Reschedule breaks if they were previously disabled
            if (isAppBlocked(packageName)) {
                val breakDuration = appPrefs.breakDuration * 60 * 1000L
                setRemainingBreakTime(packageName, breakDuration)
                scheduleBreakNotifications(packageName, breakDuration)
            }
        }
        
        appPrefs.appsWithDisabledBreaks = currentDisabledApps
    }

    // Check if app is blocked
    private fun isAppBlocked(packageName: String): Boolean {
        val appPrefs = Prefs(context)
        return appPrefs.blockedApps.contains(packageName)
    }

    // Override the existing isInBreakPeriod to check for disabled breaks
    override fun isInBreakPeriod(packageName: String): Boolean {
        val appPrefs = Prefs(context)
        if (appPrefs.breaksDisabled || areBreaksDisabledForApp(packageName)) return false

        val lastBreakStart = getLastBreakStart(packageName)
        val breakDuration = appPrefs.breakDuration * 60 * 1000L
        val currentTime = System.currentTimeMillis()

        return currentTime - lastBreakStart < breakDuration
    }

    // Override startBreak to check for disabled breaks
    override fun startBreak(packageName: String) {
        if (areBreaksDisabledForApp(packageName)) return

        val currentTime = System.currentTimeMillis()
        setLastBreakStart(packageName, currentTime)
        
        val appPrefs = Prefs(context)
        val standardBreakDuration = appPrefs.breakDuration * 60 * 1000L
        val carryoverTime = getCarryoverTime(packageName)
        val totalBreakTime = standardBreakDuration + carryoverTime

        setRemainingBreakTime(packageName, totalBreakTime)
        scheduleBreakNotifications(packageName, totalBreakTime)
        
        // Reset carryover time since we're using it now
        setCarryoverTime(packageName, 0L)
        
        // Schedule next break
        scheduleNextBreak(packageName)
    }

    // Update break usage time and check for expiration
    fun updateBreakUsage(packageName: String, usageTime: Long) {
        val remainingTime = getRemainingBreakTime(packageName)
        val newRemainingTime = maxOf(0L, remainingTime - usageTime)
        setRemainingBreakTime(packageName, newRemainingTime)

        if (newRemainingTime <= 0) {
            val unusedTime = maxOf(0L, remainingTime)
            if (unusedTime > 0) {
                // Save unused time as carryover
                val currentCarryover = getCarryoverTime(packageName)
                val newCarryover = minOf(currentCarryover + unusedTime, MAX_CARRYOVER_TIME)
                setCarryoverTime(packageName, newCarryover)
            }
            showForceCloseDialog(packageName)
        }
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
                    showForceCloseDialog(packageName)
                    return
                }

                when {
                    remainingTime <= 60 * 1000 -> { // Less than 1 minute
                        context.showToast(context.getString(R.string.break_time_ending))
                        handler.postDelayed(this, remainingTime) // Schedule exact end time
                    }
                    remainingTime <= 5 * 60 * 1000 -> { // Less than 5 minutes
                        context.showToast(context.getString(R.string.break_time_remaining, formatRemainingTime(remainingTime)))
                        handler.postDelayed(this, 60 * 1000) // Check every minute
                    }
                    else -> {
                        context.showToast(context.getString(R.string.break_time_remaining, formatRemainingTime(remainingTime)))
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

    private fun showForceCloseDialog(packageName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_force_close_dialog", true)
            putExtra("package_name", packageName)
        }
        context.startActivity(intent)
    }

    fun stopBreak(packageName: String) {
        val remainingTime = getRemainingBreakTime(packageName)
        if (remainingTime > 0) {
            // Add current remaining time to any existing carryover time
            val currentCarryover = getCarryoverTime(packageName)
            val newCarryover = minOf(currentCarryover + remainingTime, MAX_CARRYOVER_TIME)
            setCarryoverTime(packageName, newCarryover)
        }
        
        cancelBreakNotifications(packageName)
        setRemainingBreakTime(packageName, 0L)
    }

    fun getCarryoverTime(packageName: String): Long {
        val carryoverTimesStr = prefs.getString(KEY_CARRYOVER_TIME, "{}")
        val carryoverTimes = carryoverTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        return carryoverTimes[packageName] ?: 0L
    }

    private fun setCarryoverTime(packageName: String, carryoverTime: Long) {
        val carryoverTimesStr = prefs.getString(KEY_CARRYOVER_TIME, "{}")
        val carryoverTimes = carryoverTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        carryoverTimes[packageName] = minOf(carryoverTime, MAX_CARRYOVER_TIME)
        prefs.edit().putString(KEY_CARRYOVER_TIME, formatBreakTimes(carryoverTimes)).apply()
    }

    fun getTotalBreakTime(packageName: String): Long {
        val appPrefs = Prefs(context)
        val standardBreakTime = appPrefs.breakDuration * 60 * 1000L
        val carryoverTime = getCarryoverTime(packageName)
        return standardBreakTime + carryoverTime
    }

    // Track next scheduled break time
    private fun setNextBreakTime(packageName: String, nextBreakTime: Long) {
        val nextBreakTimesStr = prefs.getString(KEY_NEXT_BREAK_TIME, "{}")
        val nextBreakTimes = nextBreakTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        nextBreakTimes[packageName] = nextBreakTime
        prefs.edit().putString(KEY_NEXT_BREAK_TIME, formatBreakTimes(nextBreakTimes)).apply()
    }

    private fun getNextBreakTime(packageName: String): Long {
        val nextBreakTimesStr = prefs.getString(KEY_NEXT_BREAK_TIME, "{}")
        val nextBreakTimes = nextBreakTimesStr?.let { parseBreakTimes(it) } ?: mutableMapOf()
        return nextBreakTimes[packageName] ?: 0L
    }

    // Enhanced break scheduling
    fun scheduleNextBreak(packageName: String) {
        val appPrefs = Prefs(context)
        if (appPrefs.breaksDisabled || areBreaksDisabledForApp(packageName)) return

        val breakInterval = appPrefs.breakInterval * 60 * 60 * 1000L
        val nextBreakTime = System.currentTimeMillis() + breakInterval
        setNextBreakTime(packageName, nextBreakTime)

        // Schedule notification for next break
        scheduleBreakNotification(packageName, nextBreakTime)
    }

    // Improved break notification scheduling
    private fun scheduleBreakNotification(packageName: String, breakTime: Long) {
        cancelBreakNotifications(packageName)

        val runnable = object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val timeUntilBreak = breakTime - currentTime

                when {
                    timeUntilBreak <= 0 -> {
                        // Break time has arrived
                        startBreak(packageName)
                    }
                    timeUntilBreak <= 5 * 60 * 1000 -> { // 5 minutes warning
                        context.showToast("Break for $packageName starting in ${formatRemainingTime(timeUntilBreak)}")
                        handler.postDelayed(this, 60 * 1000) // Check every minute
                    }
                    else -> {
                        handler.postDelayed(this, BREAK_CHECK_INTERVAL)
                    }
                }
            }
        }

        toastRunnables[packageName] = runnable
        handler.postDelayed(runnable, BREAK_CHECK_INTERVAL)
    }

    override fun onDestroy() {
        toastRunnables.forEach { (_, runnable) ->
            handler.removeCallbacks(runnable)
        }
        toastRunnables.clear()
    }
} 