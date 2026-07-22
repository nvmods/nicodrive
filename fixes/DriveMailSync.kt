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
        """https://[^\s"'<>]*bondecommande[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    private val RE_IMAGE = Regex("""\.(png|jpe?g|gif|webp|svg)(\?|$)""", RegexOption.IGNORE_CASE)

    /** Choisit le meilleur candidat : d'abord le .aspx, jamais une image. */
    private fun pickOrderLink(body: String): String? {
        val candidates = RE_LINK.findAll(body).map { it.value }.toList()
        return candidates.firstOrNull { it.contains("bondecommande.aspx", ignoreCase = true) }
            ?: candidates.firstOrNull { !RE_IMAGE.containsMatchIn(it) }
    }

    data class MailConfig(val host: String, val user: String, val password: String)

    data class SyncReport(
        val mailsScanned: Int,
        val leclercMails: Int,
        val linksFound: Int,
        val pdfs: List<File>,
        val failures: List<String>
    )

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

    private fun ensureMailcap() {
        val mc = CommandMap.getDefaultCommandMap() as MailcapCommandMap
        mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
        mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
        mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(mc)
    }

    /** Répare un corps encore en quoted-printable (=3D, coupures =\r\n). */
    private fun cleanBody(raw: String): String =
        raw.replace("=\r\n", "")
            .replace("=\n", "")
            .replace("=3D", "=")
            .replace("=3d", "=")
            .replace("&amp;", "&")

    fun sync(context: Context, config: MailConfig, lookback: Int = 150): SyncReport {
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

        var scanned = 0
        var leclerc = 0
        var links = 0
        val pdfs = mutableListOf<File>()
        val failures = mutableListOf<String>()

        store.connect(config.host, config.user, config.password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val count = inbox.messageCount
                if (count > 0) {
                    val start = (count - lookback + 1).coerceAtLeast(1)
                    val messages = inbox.getMessages(start, count)
                    scanned = messages.size

                    for (msg in messages) {
                        val from = msg.from?.joinToString { it.toString() }
                            ?.lowercase() ?: ""
                        val subject = msg.subject?.lowercase() ?: ""
                        if (!from.contains("leclerc") && !subject.contains("leclerc"))
                            continue
                        leclerc++

                        val body = cleanBody(extractText(msg))
                        val link = pickOrderLink(body) ?: continue
                        links++

                        val result = downloadPdf(context, link)
                        if (result.second != null) {
                            pdfs.add(result.second!!)
                        } else {
                            failures.add(result.first)
                        }
                    }
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
        return SyncReport(scanned, leclerc, links, pdfs, failures)
    }

    private fun extractText(part: Part): String = try {
        when {
            part.isMimeType("text/*") -> part.content?.toString() ?: ""
            part.isMimeType("multipart/*") -> {
                val mp = (part.content as? Multipart)
                    ?: MimeMultipart(part.dataHandler.dataSource)
                (0 until mp.count).joinToString("\n") { extractText(mp.getBodyPart(it)) }
            }
            else -> ""
        }
    } catch (e: Exception) {
        try {
            part.inputStream.bufferedReader().readText()
        } catch (e2: Exception) {
            ""
        }
    }

    /** Retourne (raison d'échec, fichier) — fichier null si échec. */
    private fun downloadPdf(context: Context, url: String): Pair<String, File?> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
            )
            val code = conn.responseCode
            val type = conn.contentType ?: "?"
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                if (!bytes.decodeToString(0, 4.coerceAtMost(bytes.size))
                        .startsWith("%PDF")
                ) {
                    "HTTP $code, type $type (pas un PDF) : ${url.take(80)}…" to null
                } else {
                    val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                    file.writeBytes(bytes)
                    "" to file
                }
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}" to null
        }
    }
}
