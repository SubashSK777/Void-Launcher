package app.olauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.ActivityMainBinding
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hasBeenDays
import app.olauncher.helper.hasBeenHours
import app.olauncher.helper.hasBeenMinutes
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isDaySince
import app.olauncher.helper.isDefaultLauncher
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.resetLauncherViaFakeActivity
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.shareApp
import app.olauncher.helper.showLauncherSelector
import app.olauncher.helper.showToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import androidx.work.WorkRequest
import app.olauncher.helper.BlockExpiryWorker
import app.olauncher.helper.OtpHelper

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var timerJob: Job? = null

    override fun onBackPressed() {
        if (navController.currentDestination?.id != R.id.mainFragment)
            super.onBackPressed()
    }

    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        newConfig.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navController = Navigation.findNavController(this, R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.resetLauncherLiveData.call()
        }

        initClickListeners()
        initObservers(viewModel)
        viewModel.getAppList()
        setupOrientation()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        if (intent.getBooleanExtra("show_blocked_dialog", false)) {
            val packageName = intent.getStringExtra("blocked_package") ?: return
            showBlockedAppDialog(packageName)
        }

        if (intent?.getBooleanExtra("show_blocked_content_dialog", false) == true) {
            val packageName = intent.getStringExtra("blocked_package") ?: return
            val blockedText = intent.getStringExtra("blocked_text") ?: return
            showBlockedContentDialog(packageName, blockedText)
        }

        scheduleBlockExpiryCheck()
        initializeWorkManager()
    }

    override fun onStart() {
        super.onStart()
        checkTheme()
    }

    override fun onStop() {
        backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        backToHomeScreen()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        if (prefs.dailyWallpaper && AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            setPlainWallpaper()
            viewModel.setWallpaperWorker()
            recreate()
        }
    }

    private fun initClickListeners() {
        binding.ivClose.setOnClickListener {
            binding.messageLayout.visibility = View.GONE
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
        viewModel.checkForMessages.observe(this) {
            checkForMessages()
        }
        viewModel.showDialog.observe(this) {
            when (it) {
                Constants.Dialog.ABOUT -> {
                    showMessageDialog(getString(R.string.app_name), getString(R.string.welcome_to_olauncher_settings), getString(R.string.okay)) {
                        binding.messageLayout.visibility = View.GONE
                    }
                }

                Constants.Dialog.WALLPAPER -> {
                    prefs.wallpaperMsgShown = true
                    prefs.userState = Constants.UserState.REVIEW
                    showMessageDialog(getString(R.string.did_you_know), getString(R.string.wallpaper_message), getString(R.string.enable)) {
                        binding.messageLayout.visibility = View.GONE
                        prefs.dailyWallpaper = true
                        viewModel.setWallpaperWorker()
                        showToast(getString(R.string.your_wallpaper_will_update_shortly))
                    }
                }

                Constants.Dialog.REVIEW -> {
                    prefs.userState = Constants.UserState.RATE
                    showMessageDialog(getString(R.string.hey), getString(R.string.review_message), getString(R.string.leave_a_review)) {
                        binding.messageLayout.visibility = View.GONE
                        prefs.rateClicked = true
                        showToast("😇❤️")
                        rateApp()
                    }
                }

                Constants.Dialog.RATE -> {
                    prefs.userState = Constants.UserState.SHARE
                    showMessageDialog(getString(R.string.app_name), getString(R.string.rate_us_message), getString(R.string.rate_now)) {
                        binding.messageLayout.visibility = View.GONE
                        prefs.rateClicked = true
                        showToast("🤩❤️")
                        rateApp()
                    }
                }

                Constants.Dialog.SHARE -> {
                    prefs.shareShownTime = System.currentTimeMillis()
                    showMessageDialog(getString(R.string.hey), getString(R.string.share_message), getString(R.string.share_now)) {
                        binding.messageLayout.visibility = View.GONE
                        showToast("😊❤️")
                        shareApp()
                    }
                }

                Constants.Dialog.HIDDEN -> {
                    showMessageDialog(getString(R.string.hidden_apps), getString(R.string.hidden_apps_message), getString(R.string.okay)) {
                        binding.messageLayout.visibility = View.GONE
                    }
                }

                Constants.Dialog.KEYBOARD -> {
                    showMessageDialog(getString(R.string.app_name), getString(R.string.keyboard_message), getString(R.string.okay)) {
                        binding.messageLayout.visibility = View.GONE
                    }
                }

                Constants.Dialog.DIGITAL_WELLBEING -> {
                    showMessageDialog(getString(R.string.screen_time), getString(R.string.app_usage_message), getString(R.string.permission)) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }

                Constants.Dialog.PRO_MESSAGE -> {
                    showMessageDialog(getString(R.string.hey), getString(R.string.pro_message), getString(R.string.olauncher_pro)) {
                        openUrl(Constants.URL_OLAUNCHER_PRO)
                    }
                }
            }
        }
    }

    private fun showMessageDialog(title: String, message: String, action: String, clickListener: () -> Unit) {
        binding.tvTitle.text = title
        binding.tvMessage.text = message
        binding.tvAction.text = action
        binding.tvAction.setOnClickListener { clickListener() }
        binding.messageLayout.visibility = View.VISIBLE
    }

    private fun checkForMessages() {
        if (prefs.firstOpenTime == 0L)
            prefs.firstOpenTime = System.currentTimeMillis()

        when (prefs.userState) {
            Constants.UserState.START -> {
                if (prefs.firstOpenTime.hasBeenMinutes(10))
                    prefs.userState = Constants.UserState.WALLPAPER
            }

            Constants.UserState.WALLPAPER -> {
                if (prefs.wallpaperMsgShown || prefs.dailyWallpaper)
                    prefs.userState = Constants.UserState.REVIEW
                else if (isOlauncherDefault(this))
                    viewModel.showDialog.postValue(Constants.Dialog.WALLPAPER)
            }

            Constants.UserState.REVIEW -> {
                if (prefs.rateClicked)
                    prefs.userState = Constants.UserState.SHARE
                else if (isOlauncherDefault(this) && prefs.firstOpenTime.hasBeenHours(1))
                    viewModel.showDialog.postValue(Constants.Dialog.REVIEW)
            }

            Constants.UserState.RATE -> {
                if (prefs.rateClicked)
                    prefs.userState = Constants.UserState.SHARE
                else if (isOlauncherDefault(this)
                    && prefs.firstOpenTime.isDaySince() >= 7
                    && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 16
                ) viewModel.showDialog.postValue(Constants.Dialog.RATE)
            }

            Constants.UserState.SHARE -> {
                if (isOlauncherDefault(this) && prefs.firstOpenTime.hasBeenDays(14)
                    && prefs.shareShownTime.isDaySince() >= 70
                    && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 16
                ) viewModel.showDialog.postValue(Constants.Dialog.SHARE)
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupOrientation() {
        if (isTablet(this) || Build.VERSION.SDK_INT == Build.VERSION_CODES.O)
            return
        // In Android 8.0, windowIsTranslucent cannot be used with screenOrientation=portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun backToHomeScreen() {
        binding.messageLayout.visibility = View.GONE
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    private fun setPlainWallpaper() {
        if (this.isDarkThemeOn())
            setPlainWallpaper(this, android.R.color.black)
        else setPlainWallpaper(this, android.R.color.white)
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun checkTheme() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            delay(200)
            if ((prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.white))
                || (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_NO && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.black))
            ) recreate()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_ENABLE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK)
                    prefs.lockModeOn = true
            }

            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == Activity.RESULT_OK)
                    resetLauncherViaFakeActivity()
            }
        }
    }

    @SuppressLint("StringFormatInvalid")
    private  fun showBlockedAppDialog(packageName: String) {
        val appName = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            packageName
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_blocked))
            .setMessage(getString(R.string.app_is_blocked, appName))
            .setPositiveButton(getString(R.string.request_otp)) { _, _ ->
                showProgressDialog(getString(R.string.sending_otp))
               lifecycleScope.launch {
                    OtpHelper.generateAndSendOTP(this@MainActivity, packageName) { success ->
                            hideProgressDialog()
                            if (success) {
                                showOtpInputDialog(packageName)
                            } else {
                                showMessageDialog(
                                    getString(R.string.error),
                                    getString(R.string.otp_send_failed),
                                    getString(R.string.okay)
                                ) {
                                    binding.messageLayout.visibility = View.GONE
                                }
                            }
                        }
                    }
                }


            .setNegativeButton(getString(R.string.okay)) { _, _ ->
                binding.messageLayout.visibility = View.GONE
            }
            .create()
        
        dialog.show()
    }

    private lateinit var progressDialog: AlertDialog

    private fun showProgressDialog(message: String) {
        progressDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        progressDialog.show()
    }

    private fun hideProgressDialog() {
        if (::progressDialog.isInitialized && progressDialog.isShowing) {
            progressDialog.dismiss()
        }
    }


    private fun showOtpInputDialog(packageName: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_otp))
            .setView(input)
            .setPositiveButton(getString(R.string.verify)) { _, _ ->
                val enteredOTP = input.text.toString()
                if (OtpHelper.verifyOTP(this, enteredOTP)) {
                    // Remove app from blocked list
                    val newBlockedApps = prefs.blockedApps.toMutableSet()
                    newBlockedApps.remove(packageName)
                    prefs.blockedApps = newBlockedApps
                    
                    val newTimestamps = prefs.blockedAppsTimestamps.toMutableMap()
                    newTimestamps.remove(packageName)
                    prefs.blockedAppsTimestamps = newTimestamps
                    
                    showToast(R.string.app_unblocked)
                } else {
                    showToast(R.string.invalid_otp)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showBlockedContentDialog(packageName: String, blockedText: String) {
        val appName = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            packageName
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.content_blocked))
            .setMessage(getString(R.string.content_blocked_message, appName))
            .setPositiveButton(getString(R.string.okay)) { _, _ -> }
            .create()
            .show()
    }

    private fun scheduleBlockExpiryCheck() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<BlockExpiryWorker>(
            15, TimeUnit.MINUTES,  // Minimum interval allowed by WorkManager
            5, TimeUnit.MINUTES    // Flex interval for battery optimization
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "block_expiry_check",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
    }

    private fun initializeWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<BlockExpiryWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "block_expiry_check",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
    }
}

class MyAccessibilityService : android.accessibilityservice.AccessibilityService() {

    private lateinit var prefs: Prefs

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        prefs = Prefs(applicationContext)
        prefs.lockModeOn = true
        super.onServiceConnected()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent) {
        if (!prefs.keywordFilterEnabled) return

        when (event.eventType) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                try {
                    val source = event.source ?: return
                    val text = getTextFromNode(source)

                    // Skip system UI and launcher
                    if (event.packageName == "com.android.systemui" ||
                        event.packageName == "app.olauncher") return

                    if (containsBlockedKeyword(text)) {
                        // Don't block system notifications
                        if (event.packageName == "android") return

                        performGlobalAction(GLOBAL_ACTION_HOME)
                        showBlockedContentDialog(event.packageName.toString(), text)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getTextFromNode(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val text = StringBuilder()

        try {
            // Get node's text
            if (node.text != null) {
                text.append(node.text).append(" ")
            }

            // Get content description
            if (node.contentDescription != null) {
                text.append(node.contentDescription).append(" ")
            }

            // Get text from child nodes
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    text.append(getTextFromNode(child)).append(" ")
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return text.toString().lowercase()
    }

    private fun containsBlockedKeyword(text: String): Boolean {
        val keywords = prefs.blockedKeywords
        if (keywords.isEmpty()) return false

        val normalizedText = text.lowercase().trim()
        return keywords.any { keyword ->
            val normalizedKeyword = keyword.lowercase().trim()
            normalizedText.contains(normalizedKeyword)
        }
    }

    private fun isSystemPackage(packageName: String?): Boolean {
        return packageName?.startsWith("com.android.") == true ||
               packageName == "android" ||
               packageName == "com.google.android.packageinstaller"
    }

    private fun showBlockedContentDialog(packageName: String, text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("show_blocked_content_dialog", true)
            putExtra("blocked_package", packageName)
            putExtra("blocked_text", text)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {

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

            // Notify user
            applicationContext.showToast(
                getString(R.string.app_unblocked_auto,
                    getAppName(packageName)
                )
            )
            return false
        }
        return true
    }

    private fun getAppName(packageName: String): String {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(packageManager)
                .toString()
        } catch (e: Exception) {
            packageName
        }
    }
}