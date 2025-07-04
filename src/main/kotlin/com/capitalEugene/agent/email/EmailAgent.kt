package com.capitalEugene.agent.email

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

object EmailAgent {
    fun sendEmail(
        smtpHost: String,
        smtpPort: String,
        username: String,
        password: String,
        to: String,
        subject: String,
        content: String
    ) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort)
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password)
            }
        })

        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                setText(content)
            }

            Transport.send(message)
            println("✅ Email sent successfully")

        } catch (e: MessagingException) {
            e.printStackTrace()
        }
    }
}