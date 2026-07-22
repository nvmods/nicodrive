package com.example.nicobudget.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session

/**
 * Synchronisation des commandes Leclerc Drive depuis la boîte mail (IMAP).
 * Cherche les mails Leclerc récents, extrait le lien du bon de commande,
 * télécharge le PDF dans le cache et le rend disponible pour import.
 * Tout est bloquant : à appeler depuis Dispatchers.IO uniquement.
 */
object DriveMailSync {

    private val RE_LINK = Regex(
        """https://[^\s"'<>]*bondecommande\.aspx\?[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    // ---------------- Configuration (stockage chiffré) ----------------

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

    // ---------------- Synchronisation ----------------

    /**
     * Retourne les PDF téléchargés (fichiers en cache), un par mail Leclerc
     * contenant un lien de bon de commande, parmi les [lookback] derniers
     * mails de la boîte. Les doublons sont filtrés plus loin par l'import
     * (idempotence sur le n° de commande).
     */
    fun fetchOrderPdfs(context: Context, config: MailConfig, lookback: Int = 150): List<File> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
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

    private fun extractText(part: Part): String = when {
        part.isMimeType("text/*") -> part.content?.toString() ?: ""
        part.isMimeType("multipart/*") -> {
            val mp = part.content as Multipart
            (0 until mp.count).joinToString("\n") { extractText(mp.getBodyPart(it)) }
        }
        else -> ""
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
                ) return null   // lien expiré ou redirection login
                val file = File.createTempFile("bdc_", ".pdf", context.cacheDir)
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            null
        }
    }
}
