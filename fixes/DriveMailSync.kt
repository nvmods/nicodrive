package com.example.nicobudget.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Properties
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMultipart

object DriveMailSync {

    private const val MAX_SCAN = 150
    private const val MAX_RECOVERY_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val RETRY_403_AGE_MS = 24L * 60L * 60L * 1000L
    private const val MAX_PROCESSED_KEYS = 500

    private const val PREF_PROCESSED_MESSAGES = "processed_mail_keys_v2"
    private const val PREF_PROCESSED_LINKS = "processed_drive_links_v2"

    private val RE_URL = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)
    private val RE_IMAGE = Regex(
        """\.(png|jpe?g|gif|webp|svg|ico)(\?|$)""",
        RegexOption.IGNORE_CASE
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
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
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

    private fun loadKeySet(context: Context, key: String): LinkedHashSet<String> {
        val raw = prefs(context).getString(key, null).orEmpty()
        if (raw.isBlank()) return LinkedHashSet()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun saveKeySet(context: Context, key: String, values: LinkedHashSet<String>) {
        while (values.size > MAX_PROCESSED_KEYS) {
            val first = values.firstOrNull() ?: break
            values.remove(first)
        }
        prefs(context).edit().putString(key, values.joinToString("\n")).apply()
    }

    private fun markProcessed(
        context: Context,
        processedMessages: LinkedHashSet<String>,
        messageKey: String
    ) {
        processedMessages.remove(messageKey)
        processedMessages.add(messageKey)
        saveKeySet(context, PREF_PROCESSED_MESSAGES, processedMessages)
    }

    private fun markLinkProcessed(
        context: Context,
        processedLinks: LinkedHashSet<String>,
        linkKey: String
    ) {
        processedLinks.remove(linkKey)
        processedLinks.add(linkKey)
        saveKeySet(context, PREF_PROCESSED_LINKS, processedLinks)
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
            .replace("&#38;", "&")
            .replace("&#x26;", "&", ignoreCase = true)

    private fun directVariant(u: String): String? {
        if (!RE_BDC.containsMatchIn(u)) return null
        val direct = u.replace("/rapport/", "/")
            .replace("bon-de-commande.aspx", "bondecommande.aspx", ignoreCase = true)
        return if (direct != u) direct else null
    }

    private fun normalizeLinkForKey(url: String): String {
        return try {
            val uri = URI(url)
            val query = uri.rawQuery.orEmpty()
            val interesting = query.split('&')
                .filter {
                    it.startsWith("iIdC=", ignoreCase = true) ||
                        it.startsWith("dDtC=", ignoreCase = true)
                }
            if (interesting.isNotEmpty()) {
                interesting.sorted().joinToString("&")
            } else {
                url.lowercase()
            }
        } catch (_: Exception) {
            url.lowercase()
        }
    }

    private fun messageKey(msg: Message): String {
        val messageId = try {
            msg.getHeader("Message-ID")?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (!messageId.isNullOrBlank()) return messageId.trim()

        val date = (msg.receivedDate ?: msg.sentDate)?.time ?: 0L
        val from = try {
            msg.from?.joinToString("|") { it.toString() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return "$date|${msg.subject.orEmpty()}|$from"
    }

    private fun messageAgeMs(msg: Message, now: Long): Long {
        val date = (msg.receivedDate ?: msg.sentDate)?.time ?: return Long.MAX_VALUE
        return (now - date).coerceAtLeast(0L)
    }

    /** Liens candidats du mail, du plus prometteur au moins prometteur. */
    private fun candidateLinks(body: String): List<String> {
        val all = RE_URL.findAll(body)
            .map { it.value.trimEnd('.', ',', ';') }
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

    fun sync(context: Context, config: MailConfig, lookback: Int = MAX_SCAN): SyncReport {
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
        var relevantLeclerc = 0
        var tried = 0
        val pdfs = mutableListOf<File>()
        val failures = mutableListOf<String>()

        val processedMessages = loadKeySet(context, PREF_PROCESSED_MESSAGES)
        val processedLinks = loadKeySet(context, PREF_PROCESSED_LINKS)
        val now = System.currentTimeMillis()

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

                    // Traite du plus ancien au plus récent : si plusieurs notifications
                    // concernent la même commande, le premier PDF réussi suffit.
                    for (msg in messages.sortedBy {
                        (it.receivedDate ?: it.sentDate)?.time ?: Long.MAX_VALUE
                    }) {
                        val key = messageKey(msg)
                        if (processedMessages.contains(key)) continue

                        val from = try {
                            msg.from?.joinToString { it.toString() }?.lowercase().orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        val subject = msg.subject?.lowercase().orEmpty()
                        if (!from.contains("leclerc") && !subject.contains("leclerc")) {
                            continue
                        }

                        val ageMs = messageAgeMs(msg, now)

                        // Les liens "bon de commande" E.Leclerc sont temporaires.
                        // Inutile de retenter des notifications anciennes à chaque synchro :
                        // elles provoquent précisément les HTTP 403 observés après retrait.
                        if (ageMs > MAX_RECOVERY_AGE_MS) {
                            markProcessed(context, processedMessages, key)
                            continue
                        }

                        val body = cleanBody(extractText(msg))
                        val bdcLinks = candidateLinks(body)
                            .filter { RE_BDC.containsMatchIn(it) }
                            .take(6)

                        // Mail Leclerc sans lien de bon de commande (prêt, info, marketing...).
                        if (bdcLinks.isEmpty()) {
                            markProcessed(context, processedMessages, key)
                            continue
                        }

                        relevantLeclerc++

                        // Si le même lien de commande a déjà été récupéré via une autre
                        // notification, ce mail est un doublon logique.
                        val alreadyDone = bdcLinks.any {
                            processedLinks.contains(normalizeLinkForKey(it))
                        }
                        if (alreadyDone) {
                            markProcessed(context, processedMessages, key)
                            continue
                        }

                        var found = false
                        var firstFailure: String? = null
                        var firstFailureCode: Int? = null

                        for (link in bdcLinks) {
                            tried++
                            val result = downloadPdf(context, link)
                            if (result.file != null) {
                                pdfs.add(result.file)
                                markLinkProcessed(
                                    context,
                                    processedLinks,
                                    normalizeLinkForKey(link)
                                )
                                markProcessed(context, processedMessages, key)
                                found = true
                                break
                            } else if (firstFailure == null) {
                                firstFailure = result.detail
                                firstFailureCode = result.httpCode
                            }
                        }

                        if (!found && firstFailure != null) {
                            // Un 403 sur un vieux mail signifie généralement que le lien
                            // temporaire est expiré. On l'abandonne après 24 h pour éviter
                            // une erreur permanente toutes les 6 h.
                            if (firstFailureCode == 403 && ageMs > RETRY_403_AGE_MS) {
                                markProcessed(context, processedMessages, key)
                            } else {
                                // Pour un mail récent, on garde le message non traité :
                                // le Worker réessaiera à la prochaine synchro.
                                failures.add(firstFailure)
                            }
                        }
                    }
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }

        return SyncReport(scanned, relevantLeclerc, tried, pdfs, failures)
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
    } catch (_: Exception) {
        try {
            part.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
    }

    private data class HttpResult(
        val code: Int,
        val type: String,
        val bytes: ByteArray,
        val finalUrl: String
    )

    private data class PdfDownloadResult(
        val detail: String,
        val file: File?,
        val httpCode: Int? = null
    )

    /**
     * GET avec cookies RFC gérés par java.net.CookieManager.
     * Le Referer est mis à jour à chaque redirection, contrairement à l'ancien
     * jar artisanal qui pouvait présenter un contexte incohérent au serveur.
     */
    private fun fetch(
        url: String,
        cookies: CookieManager,
        referer: String? = null
    ): HttpResult? {
        var current = url
        var currentReferer = referer
        var hops = 0

        while (true) {
            val uri = URI(current)
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.requestMethod = "GET"

            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "application/pdf,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")
            conn.setRequestProperty(
                "Sec-Fetch-Site",
                if (currentReferer == null) "none" else "same-origin"
            )
            if (currentReferer == null) {
                conn.setRequestProperty("Sec-Fetch-User", "?1")
            } else {
                conn.setRequestProperty("Referer", currentReferer)
            }

            val requestCookies = try {
                cookies.get(uri, emptyMap())["Cookie"].orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            if (requestCookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", requestCookies.joinToString("; "))
            }

            val code = conn.responseCode

            try {
                val headers = conn.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                cookies.put(uri, headers)
            } catch (_: Exception) {
                // Un cookie malformé ne doit pas bloquer la récupération.
            }

            if (code in 300..399 && hops < 8) {
                val location = conn.getHeaderField("Location") ?: return null
                val previous = current
                current = if (location.startsWith("http", ignoreCase = true)) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
                currentReferer = previous
                hops++
                conn.disconnect()
                continue
            }

            val type = conn.contentType ?: "?"
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            conn.disconnect()
            return HttpResult(code, type, bytes, current)
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.decodeToString(0, 4).startsWith("%PDF")

    private fun htmlTargets(baseUrl: String, res: HttpResult): List<String> {
        if (!res.type.contains("html", ignoreCase = true)) return emptyList()

        val html = res.bytes.decodeToString()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        val found = RE_ASPX.findAll(html).map { match ->
            val raw = match.value
            try {
                URL(URL(baseUrl), raw).toString()
            } catch (_: Exception) {
                raw
            }
        }.toList()

        return found.flatMap { target ->
            listOfNotNull(target, directVariant(target))
        }.distinct()
    }

    /**
     * Essaie plusieurs parcours compatibles avec les anciens mails E.Leclerc :
     * 1. URL du bouton telle quelle ;
     * 2. liens ASPX découverts dans la page ;
     * 3. variante historique /bondecommande.aspx avec les mêmes jetons.
     *
     * Le point important du correctif v1.5 est surtout de ne plus rejouer
     * indéfiniment les liens temporaires de vieux mails déjà traités/expirés.
     */
    private fun downloadPdf(context: Context, url: String): PdfDownloadResult {
        return try {
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

            var res = fetch(url, cookies)
                ?: return PdfDownloadResult("redirection sans Location", null)

            if (isPdf(res.bytes)) {
                val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                file.writeBytes(res.bytes)
                return PdfDownloadResult("", file, res.code)
            }

            val targets = buildList {
                addAll(htmlTargets(res.finalUrl, res))
                directVariant(url)?.let { add(it) }
            }.distinct()

            var last = res
            for (target in targets) {
                if (target == last.finalUrl) continue
                val next = fetch(target, cookies, referer = last.finalUrl) ?: continue
                last = next
                if (isPdf(next.bytes)) {
                    val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                    file.writeBytes(next.bytes)
                    return PdfDownloadResult("", file, next.code)
                }
            }

            val suffix = last.finalUrl.takeLast(72)
            PdfDownloadResult(
                "HTTP ${last.code}, ${last.type} : …$suffix",
                null,
                last.code
            )
        } catch (e: Exception) {
            PdfDownloadResult(
                "${e.javaClass.simpleName}: ${e.message}",
                null,
                null
            )
        }
    }
}
