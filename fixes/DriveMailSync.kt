package com.example.nicobudget.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMultipart

object DriveMailSync {

    private val RE_LINK = Regex(
        """https://[^\s"'<>]*bondecommande\.aspx\?[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    data class MailConfig(val host: String, val user: String, val password: String)

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "drive_mail_config",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveConfig(context: Context, config: MailConfig) {
        prefs(context).edit()
            .putString("host", config.host)
            .putString("user", config.user)
            .putString("password", config.password)
            .apply()
    }

    fun loadConfig(context: Context): MailConfig? {
        val p = prefs(context)
        val host = p.getString("host", null) ?: return null
        val user = p.getString("user", null) ?: return null
        val pass = p.getString("password", null) ?: return null
        return MailConfig(host, user, pass)
    }

    /**
     * Les fichiers META-INF/mailcap sont exclus de l'APK : on enregistre les
     * décodeurs MIME de Jakarta Mail par code, sinon les mails multipart
     * ressortent en flux brut (IMAPInputStream cannot be cast to Multipart).
     */
    private fun ensureMailcap() {
        val mc = CommandMap.getDefaultCommandMap() as MailcapCommandMap
        mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
        mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
        mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(mc)
    }

    fun fetchOrderPdfs(context: Context, config: MailConfig, lookback: Int = 150): List<File> {
        ensureMailcap()
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.partialfetch", "false")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        val pdfs = mutableListOf<File>()

        store.connect(config.host, config.user, config.password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val count = inbox.messageCount
                if (count == 0) return emptyList()
                val start = (count - lookback + 1).coerceAtLeast(1)
                val messages = inbox.getMessages(start, count)

                for (msg in messages) {
                    val from = msg.from?.joinToString { it.toString() }?.lowercase() ?: ""
                    if (!from.contains("leclerc")) continue

                    val body = extractText(msg)
                        .replace("&amp;", "&")
                        .replace("=\r\n", "")
                        .replace("=\n", "")
                    val link = RE_LINK.find(body)?.value ?: continue

                    downloadPdf(context, link)?.let { pdfs.add(it) }
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
        return pdfs
    }

    private fun extractText(part: Part): String = try {
        when {
            part.isMimeType("text/*") -> part.content?.toString() ?: ""
            part.isMimeType("multipart/*") -> {
                // Repli si le décodeur n'est pas actif : reconstruire le
                // multipart depuis la source brute.
                val mp = (part.content as? Multipart)
                    ?: MimeMultipart(part.dataHandler.dataSource)
                (0 until mp.count).joinToString("\n") { extractText(mp.getBodyPart(it)) }
            }
            else -> ""
        }
    } catch (e: Exception) {
        // Dernier recours : lecture brute du flux (suffisant pour y trouver
        // une URL en clair).
        try {
            part.inputStream.bufferedReader().readText()
        } catch (e2: Exception) {
            ""
        }
    }

    private fun downloadPdf(context: Context, url: String): File? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                if (!bytes.decodeToString(0, 4.coerceAtMost(bytes.size))
                        .startsWith("%PDF")
                ) return null
                val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            null
        }
    }
}
