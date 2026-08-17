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
    private const val MAX_BACKGROUND_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val RETRY_403_AGE_MS = 24L * 60L * 60L * 1000L
    private const val MAX_PROCESSED_KEYS = 500

    // v3 remet volontairement à zéro les mails que la b50 avait marqués comme
    // "expirés". Les liens déjà récupérés restent en v2 afin d'éviter les doublons.
    private const val PREF_PROCESSED_MESSAGES = "processed_mail_keys_v3"
    private const val PREF_PROCESSED_LINKS = "processed_drive_links_v2"

    private const val PREF_AUTH_COOKIE_PREFIX = "leclerc_auth_cookie_v1_"
    private const val PREF_AUTH_USER_AGENT = "leclerc_auth_user_agent_v1"
    private const val PREF_AUTH_SAVED_AT = "leclerc_auth_saved_at_v1"

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

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
        val failures: List<String>,
        val authRequiredUrl: String? = null,
        val historicalSkipped: Int = 0
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

    fun setAutoSync(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_sync", enabled).apply()
    }

    fun isAutoSync(context: Context): Boolean =
        prefs(context).getBoolean("auto_sync", false)

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Enregistre les cookies créés après la connexion interactive dans le WebView.
     * Les identifiants E.Leclerc ne sont jamais lus ni stockés par l'application :
     * seule la session HTTP déjà authentifiée est conservée, dans les préférences
     * chiffrées existantes de NicoBudget.
     */
    fun saveBrowserSession(
        context: Context,
        originalUrl: String,
        currentUrl: String,
        originalCookies: String?,
        currentCookies: String?,
        userAgent: String
    ): Boolean {
        val editor = prefs(context).edit()
        var saved = false

        listOf(
            originalUrl to originalCookies,
            currentUrl to currentCookies
        ).forEach { (url, cookies) ->
            val host = hostOf(url)
            if (host != null && !cookies.isNullOrBlank()) {
                editor.putString(PREF_AUTH_COOKIE_PREFIX + host, cookies.trim())
                saved = true
            }
        }

        if (saved) {
            if (userAgent.isNotBlank()) {
                editor.putString(PREF_AUTH_USER_AGENT, userAgent)
            }
            editor.putLong(PREF_AUTH_SAVED_AT, System.currentTimeMillis())
            editor.apply()
        }
        return saved
    }

    fun clearBrowserSession(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys
            .filter { it.startsWith(PREF_AUTH_COOKIE_PREFIX) }
            .forEach { editor.remove(it) }
        editor.remove(PREF_AUTH_USER_AGENT)
        editor.remove(PREF_AUTH_SAVED_AT)
        editor.apply()
    }

    private fun browserCookieFor(context: Context, url: String): String? {
        val host = hostOf(url) ?: return null
        return prefs(context).getString(PREF_AUTH_COOKIE_PREFIX + host, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun browserUserAgent(context: Context): String =
        prefs(context).getString(PREF_AUTH_USER_AGENT, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_AGENT

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

    /**
     * Synchronisation normale : les vieux mails sont ignorés pour le Worker afin
     * de ne pas provoquer de reCAPTCHA en arrière-plan.
     *
     * includeHistorical=true est utilisé par le bouton manuel de l'écran Drive.
     * Dans ce mode les vieux liens sont essayés et un 401/403 remonte l'URL à ouvrir
     * dans le WebView de connexion E.Leclerc.
     */
    fun sync(
        context: Context,
        config: MailConfig,
        lookback: Int = MAX_SCAN,
        includeHistorical: Boolean = false
    ): SyncReport {
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
        var historicalSkipped = 0
        var authRequiredUrl: String? = null
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

                    // Le plus récent d'abord : on récupère immédiatement les liens encore
                    // anonymes, puis on demande une connexion seulement pour l'historique.
                    messageLoop@ for (msg in messages.sortedByDescending {
                        (it.receivedDate ?: it.sentDate)?.time ?: 0L
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
                        if (!includeHistorical && ageMs > MAX_BACKGROUND_AGE_MS) {
                            historicalSkipped++
                            continue
                        }

                        val body = cleanBody(extractText(msg))
                        val bdcLinks = candidateLinks(body)
                            .filter { RE_BDC.containsMatchIn(it) }
                            .take(6)

                        // Mail Leclerc sans bon de commande : on peut le considérer traité.
                        if (bdcLinks.isEmpty()) {
                            markProcessed(context, processedMessages, key)
                            continue
                        }

                        relevantLeclerc++

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
                            if (firstFailureCode == 401 || firstFailureCode == 403) {
                                if (includeHistorical) {
                                    authRequiredUrl = bdcLinks.firstOrNull()
                                    failures.add(
                                        "Connexion E.Leclerc requise pour récupérer l'historique."
                                    )
                                    break@messageLoop
                                } else if (ageMs > RETRY_403_AGE_MS) {
                                    // Ne pas marquer le mail traité : le mode manuel historique
                                    // doit pouvoir le reprendre plus tard après authentification.
                                    historicalSkipped++
                                } else {
                                    failures.add(firstFailure)
                                }
                            } else {
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

        return SyncReport(
            mailsScanned = scanned,
            leclercMails = relevantLeclerc,
            linksTried = tried,
            pdfs = pdfs,
            failures = failures,
            authRequiredUrl = authRequiredUrl,
            historicalSkipped = historicalSkipped
        )
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

    private fun cookiePairs(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { raw ->
            val part = raw.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }
    }

    private fun mergedCookieHeader(
        browserHeader: String?,
        requestHeaders: List<String>
    ): String? {
        val merged = LinkedHashMap<String, String>()
        cookiePairs(browserHeader).forEach { (name, value) -> merged[name] = value }
        requestHeaders.forEach { header ->
            cookiePairs(header).forEach { (name, value) -> merged[name] = value }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * GET avec les cookies de la tentative courante + la session authentifiée
     * capturée dans le WebView. Le User-Agent du WebView est également réutilisé
     * afin de rester cohérent avec la session qui a passé le reCAPTCHA.
     */
    private fun fetch(
        context: Context,
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

            conn.setRequestProperty("User-Agent", browserUserAgent(context))
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "application/pdf,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")

            val sameHost = try {
                currentReferer != null &&
                    URI(currentReferer).host.equals(uri.host, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
            conn.setRequestProperty(
                "Sec-Fetch-Site",
                when {
                    currentReferer == null -> "none"
                    sameHost -> "same-origin"
                    else -> "cross-site"
                }
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
            mergedCookieHeader(
                browserCookieFor(context, current),
                requestCookies
            )?.let { conn.setRequestProperty("Cookie", it) }

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
     * Essaie plusieurs parcours compatibles avec les mails E.Leclerc :
     * URL du bouton, liens ASPX trouvés dans la page et variante historique
     * /bondecommande.aspx. Une session WebView authentifiée est injectée si elle existe.
     */
    private fun downloadPdf(context: Context, url: String): PdfDownloadResult {
        return try {
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

            var res = fetch(context, url, cookies)
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
                val next = fetch(context, target, cookies, referer = last.finalUrl) ?: continue
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
