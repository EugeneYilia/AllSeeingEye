package com.capitalEugene.agent.email

import com.capitalEugene.secrets.defaultEmailAccount
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

object EmailAgent {

    fun sendEmail(
        toList: List<String>,                    // 支持多个主收件人
        ccList: List<String> = emptyList(),      // 可选：抄送
        bccList: List<String> = emptyList(),     // 可选：密送
        subject: String,
        content: String
    ) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", defaultEmailAccount.smtpHost)
            put("mail.smtp.port", defaultEmailAccount.smtpPort)
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(defaultEmailAccount.smtpUsername, defaultEmailAccount.smtpPassword)
            }
        })

        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(defaultEmailAccount.smtpUsername, "尤金资本"))

                setRecipients(Message.RecipientType.TO, toList.map { InternetAddress(it) }.toTypedArray())
                if (ccList.isNotEmpty()) {
                    setRecipients(Message.RecipientType.CC, ccList.map { InternetAddress(it) }.toTypedArray())
                }
                if (bccList.isNotEmpty()) {
                    setRecipients(Message.RecipientType.BCC, bccList.map { InternetAddress(it) }.toTypedArray())
                }

                setSubject(subject)
                setText(content)
            }

            Transport.send(message)
            println("✅ Email sent to: ${toList.joinToString(", ")}")

        } catch (e: MessagingException) {
            println("❌ Failed to send email: ${e.message}")
            e.printStackTrace()
        }
    }
}