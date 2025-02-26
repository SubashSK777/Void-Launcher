package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Prefs

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
        if (!prefs.keywordFilterEnabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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

    private fun getTextFromNode(node: AccessibilityNodeInfo): String {
        val text = StringBuilder()
        
        // Get node's text
        if (node.text != null) {
            text.append(node.text)
        }
        
        // Get text from child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                text.append(" ").append(getTextFromNode(child))
                child.recycle()
            }
        }
        
        return text.toString().lowercase()
    }

    private fun containsBlockedKeyword(text: String): Boolean {
        val keywords = prefs.blockedKeywords
        return keywords.any { keyword ->
            text.lowercase().contains(keyword.lowercase())
        }
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
}