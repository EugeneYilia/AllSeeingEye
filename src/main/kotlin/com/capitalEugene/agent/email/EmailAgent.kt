package com.capitalEugene.agent.email

import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.secrets.defaultEmailAccount
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.util.Properties

object EmailAgent {
    val logger = LoggerFactory.getLogger("email_agent")

    fun sendEmail(
        subject: String,
        content: String,
        toList: List<String>,                    // 支持多个主收件人
        ccList: List<String> = emptyList(),      // 可选：抄送
        bccList: List<String> = emptyList(),     // 可选：密送
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
                setFrom(InternetAddress(defaultEmailAccount.smtpUsername, ApplicationConstants.applicationChineseName))

                setRecipients(Message.RecipientType.TO, toList.map { InternetAddress(it) }.toTypedArray())
                if (ccList.isNotEmpty()) {
                    setRecipients(Message.RecipientType.CC, ccList.map { InternetAddress(it) }.toTypedArray())
                }
                if (bccList.isNotEmpty()) {
                    setRecipients(Message.RecipientType.BCC, bccList.map { InternetAddress(it) }.toTypedArray())
                }

                setSubject(subject)
                setContent(content, "text/html; charset=UTF-8")
            }

            Transport.send(message)
            logger.info("✅ Email sent to: ${toList.joinToString(", ")}")

        } catch (e: MessagingException) {
            logger.error("❌ Failed to send email: ${e.message}")
            e.printStackTrace()
        }
    }
}