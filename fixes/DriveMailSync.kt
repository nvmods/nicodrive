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

    private val RE_URL = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)
    private val RE_IMAGE = Regex(
        """\.(png|jpe?g|gif|webp|svg|ico)(\?|$)""", RegexOption.IGNORE_CASE
    )
    private val RE_JUNK = Regex(
        """unsubscribe|desinscri|desabonn|facebook|instagram|twitter|youtube|""" +
            """apple\.com|google\.com|play\.google|mentions|cgv|contact""",
        RegexOption.IGNORE_CASE
    )
    private val RE_ASPX = Regex(
        """https?://[^\s"'<>\\]+bon-?de-?commande\.aspx[^\s"'<>\\]*""",
        RegexOption.IGNORE_CASE
    )
    private val RE_BDC = Regex("""bon-?de-?commande\.aspx""", RegexOption.IGNORE_CASE)

    data class MailConfig(val host: String, val user: String, val password: String)

    data class SyncReport(
        val mailsScanned: Int,
        val leclercMails: Int,
        val linksTried: Int,
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

    private fun cleanBody(raw: String): String =
        raw.replace("=\r\n", "")
            .replace("=\n", "")
            .replace("=3D", "=")
            .replace("=3d", "=")
            .replace("&amp;", "&")

    private fun directVariant(u: String): String? {
        if (!RE_BDC.containsMatchIn(u)) return null
        val direct = u.replace("/rapport/", "/")
            .replace("bon-de-commande.aspx", "bondecommande.aspx", ignoreCase = true)
        return if (direct != u) direct else null
    }

    /** Liens candidats du mail, le bouton bon-de-commande en tête. */
    private fun candidateLinks(body: String): List<String> {
        val all = RE_URL.findAll(body).map { it.value.trimEnd('.', ',', ';') }
            .distinct()
            .filter { !RE_IMAGE.containsMatchIn(it) && !RE_JUNK.containsMatchIn(it) }
            .toList()
        fun score(u: String): Int {
            var s = 0
            if (RE_BDC.containsMatchIn(u)) s += 100
            if (u.contains("bon-de-commande", true) ||
                u.contains("bondecommande", true)
            ) s += 40
            if (u.contains("commande", true)) s += 20
            if (u.contains("leclercdrive", true)) s += 10
            if (u.contains("iIdC", true) || u.contains("dDtC", true)) s += 50
            return s
        }
        return all.sortedByDescending { score(it) }
    }

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
        var tried = 0
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
                        var found = false
                        var firstFailure: String? = null
                        for (link in candidateLinks(body).take(10)) {
                            tried++
                            val result = downloadPdf(context, link)
                            if (result.second != null) {
                                pdfs.add(result.second!!)
                                found = true
                                break
                            } else if (firstFailure == null) {
                                firstFailure = result.first
                            }
                        }
                        if (!found && firstFailure != null) failures.add(firstFailure)
                    }
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
        return SyncReport(scanned, leclerc, tried, pdfs, failures)
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

    /** Réponse HTTP : code, type, corps, cookies posés, URL finale. */
    private data class HttpResult(
        val code: Int,
        val type: String,
        val bytes: ByteArray,
        val finalUrl: String
    )

    /** Jar de cookies minimal, partagé au sein d'une même tentative. */
    private class CookieJar {
        private val cookies = LinkedHashMap<String, String>()
        fun absorb(conn: HttpURLConnection) {
            conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty()) cookies[name] = value
                }
        }
        fun header(): String? =
            if (cookies.isEmpty()) null
            else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun fetch(url: String, jar: CookieJar, referer: String? = null): HttpResult? {
        var current = url
        var hops = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "application/pdf,image/avif,image/webp,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
            referer?.let { conn.setRequestProperty("Referer", it) }
            jar.header()?.let { conn.setRequestProperty("Cookie", it) }

            val code = conn.responseCode
            jar.absorb(conn)
            if (code in 300..399 && hops < 8) {
                val loc = conn.getHeaderField("Location") ?: return null
                current = if (loc.startsWith("http")) loc
                else URL(URL(current), loc).toString()
                hops++
                continue
            }
            val type = conn.contentType ?: "?"
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            return HttpResult(code, type, bytes, current)
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.decodeToString(0, 4).startsWith("%PDF")

    /**
     * Reproduit le parcours navigateur : ouvre l'URL du mail (la page
     * /rapport/ pose ses cookies), puis suit vers le PDF avec ces cookies
     * et le bon Referer.
     */
    private fun downloadPdf(context: Context, url: String): Pair<String, File?> {
        return try {
            val jar = CookieJar()

            // 1) Requête initiale (URL du mail telle quelle)
            var res = fetch(url, jar)
                ?: return "redirection sans Location" to null

            // 2) Si HTML : chercher un lien bon de commande dans la page,
            //    sinon dériver la variante directe de l'URL — et suivre
            //    avec cookies + Referer.
            if (!isPdf(res.bytes)) {
                val nextTargets = mutableListOf<String>()
                if (res.type.contains("html", ignoreCase = true)) {
                    val html = res.bytes.decodeToString()
                        .replace("&amp;", "&")
                        .replace("\\/", "/")
                    RE_ASPX.find(html)?.value?.let {
                        nextTargets.add(it)
                        directVariant(it)?.let { d -> nextTargets.add(d) }
                    }
                }
                directVariant(url)?.let { nextTargets.add(it) }

                for (target in nextTargets.distinct()) {
                    if (target == res.finalUrl) continue
                    val second = fetch(target, jar, referer = res.finalUrl) ?: continue
                    if (isPdf(second.bytes)) {
                        res = second
                        break
                    }
                    res = second
                }
            }

            if (!isPdf(res.bytes)) {
                "HTTP ${res.code}, ${res.type} : …${res.finalUrl.takeLast(50)}" to null
            } else {
                val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                file.writeBytes(res.bytes)
                "" to file
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}" to null
        }
    }
}
