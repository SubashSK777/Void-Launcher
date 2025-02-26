package app.olauncher.helper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailService(private val context: Context) {
    suspend fun sendOtpEmail(recipientEmail: String, otp: String, appName: String) {
        withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.socketFactory.port", "465")
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.port", "465")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(SMTP_EMAIL, SMTP_PASSWORD)
                    }
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(SMTP_EMAIL))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail))
                    subject = "OTP for Unblocking $appName"
                    setText("Your OTP for unblocking $appName is: $otp\n\nThis OTP will expire in 5 minutes.")
                }

                Transport.send(message)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        // Replace these with actual SMTP credentials
        private const val SMTP_EMAIL = "your-email@gmail.com"
        private const val SMTP_PASSWORD = "your-app-specific-password"
    }
} 