package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import kotlin.random.Random

object OtpHelper {
    private lateinit var emailService: EmailService

    fun generateAndSendOTP(context: Context, packageName: String, callback: (Boolean) -> Unit) {
        val prefs = Prefs(context)
        
        // Check if in lockout period
        if (isInLockoutPeriod(prefs)) {
            callback(false)
            return
        }
        
        // Generate new 6-digit OTP
        val otp = Random.nextInt(100000, 999999).toString()
        val currentTime = System.currentTimeMillis()
        
        // Save OTP details
        prefs.otpValue = otp
        prefs.otpTimestamp = currentTime
        prefs.otpAttempts = 0
        
        // Get app name
        val appName = try {
            context.packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(context.packageManager).toString()
        } catch (e: Exception) {
            packageName
        }

        // Send email
        val partnerEmail = prefs.partnerEmail
        if (partnerEmail.isBlank()) {
            callback(false)
            return
        }

        emailService = EmailService(context)
        emailService.sendOtpEmail(partnerEmail, otp, appName) { success ->
            callback(success)
        }
    }

    fun verifyOTP(context: Context, enteredOTP: String): Boolean {
        val prefs = Prefs(context)
        
        // Check if in lockout period
        if (isInLockoutPeriod(prefs)) return false
        
        // Check if OTP is expired
        if (isOtpExpired(prefs)) {
            prefs.otpValue = ""
            return false
        }
        
        // Verify OTP
        if (enteredOTP == prefs.otpValue) {
            prefs.otpValue = ""
            prefs.otpAttempts = 0
            return true
        }
        
        // Handle failed attempt
        prefs.otpAttempts++
        if (prefs.otpAttempts >= Constants.MAX_OTP_ATTEMPTS) {
            prefs.otpLockoutTime = System.currentTimeMillis()
        }
        
        return false
    }

    private fun isOtpExpired(prefs: Prefs): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - prefs.otpTimestamp > Constants.OTP_EXPIRY_TIME
    }

    private fun isInLockoutPeriod(prefs: Prefs): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - prefs.otpLockoutTime < Constants.OTP_LOCKOUT_DURATION
    }
} 