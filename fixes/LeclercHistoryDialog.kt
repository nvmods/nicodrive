package com.example.nicobudget.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nicobudget.data.model.BudgetViewModel
import com.example.nicobudget.drive.DriveImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

private data class LeclercHistoryItem(
    val orderId: String,
    val url: String
)

private data class LeclercHistoryPage(
    val year: String,
    val years: List<String>,
    val items: List<LeclercHistoryItem>,
    val nextActive: Boolean,
    val signature: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeclercHistoryDialog(
    initialUrl: String,
    viewModel: BudgetViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecte-toi si nécessaire puis lance la synchronisation.") }
    var progress by remember { mutableStateOf(0f) }

    suspend fun eval(view: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { raw ->
                if (cont.isActive) cont.resume(raw ?: "null")
            }
        }

    fun decodeJs(raw: String): String {
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is String -> value
                else -> value?.toString().orEmpty()
            }
        } catch (_: Exception) {
            raw.trim('"').replace("\\\"", "\"")
        }
    }

    suspend fun scrapePage(view: WebView): LeclercHistoryPage? {
        val js = """
            (function() {
              const links = Array.from(document.querySelectorAll('a[href*="bon-de-commande.aspx"]'));
              const items = links.map(a => {
                const row = a.closest('tr');
                const num = row ? row.querySelector('a.aNumCommande') : null;
                const orderId = (num ? num.textContent : '').replace(/\\D/g, '');
                return { orderId: orderId, url: a.href };
              });
              const sel = document.querySelector('select[id*="ddlFiltreAnnees"]');
              const next = document.querySelector('a[id$="_lbSuivant"]');
              const years = sel ? Array.from(sel.options).map(o => o.value) : [];
              const nextActive = !!(next && next.className.indexOf('Actif') >= 0 && next.className.indexOf('Inactif') < 0);
              return JSON.stringify({
                year: sel ? sel.value : '',
                years: years,
                items: items,
                nextActive: nextActive,
                signature: items.map(i => i.url).join('|')
              });
            })();
        """.trimIndent()

        val decoded = decodeJs(eval(view, js))
        if (decoded.isBlank() || decoded == "null") return null
        return try {
            val obj = JSONObject(decoded)
            val yearsJson = obj.optJSONArray("years") ?: JSONArray()
            val years = buildList {
                for (i in 0 until yearsJson.length()) add(yearsJson.optString(i))
            }
            val itemsJson = obj.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (i in 0 until itemsJson.length()) {
                    val row = itemsJson.optJSONObject(i) ?: continue
                    val url = row.optString("url")
                    if (url.isNotBlank()) {
                        add(LeclercHistoryItem(row.optString("orderId"), url))
                    }
                }
            }
            LeclercHistoryPage(
                year = obj.optString("year"),
                years = years,
                items = items,
                nextActive = obj.optBoolean("nextActive"),
                signature = obj.optString("signature")
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clickMoreIfPresent(view: WebView) {
        val js = """
            (function() {
              const candidates = Array.from(document.querySelectorAll('a'));
              const a = candidates.find(x => {
                const t = (x.textContent || '').trim().toLowerCase();
                return (t.includes('voir plus') || t.includes('toutes les commandes') || t.includes('plus de commandes')) && !t.includes('moins');
              });
              if (a) { a.click(); return 'clicked'; }
              return 'none';
            })();
        """.trimIndent()
        if (decodeJs(eval(view, js)) == "clicked") delay(1600)
    }

    suspend fun clickNext(view: WebView): Boolean {
        val js = """
            (function() {
              const a = document.querySelector('a[id$="_lbSuivant"]');
              if (!a || a.className.indexOf('Actif') < 0 || a.className.indexOf('Inactif') >= 0) return 'no';
              a.click();
              return 'yes';
            })();
        """.trimIndent()
        return decodeJs(eval(view, js)) == "yes"
    }

    suspend fun selectYear(view: WebView, year: String): Boolean {
        val safe = year.replace("'", "")
        val js = """
            (function() {
              const sel = document.querySelector('select[id*="ddlFiltreAnnees"]');
              if (!sel) return 'no';
              const custom = document.querySelector('.selectNV__option[data-val="$safe"]');
              if (custom) custom.click();
              sel.value = '$safe';
              sel.dispatchEvent(new Event('change', { bubbles: true }));
              return 'yes';
            })();
        """.trimIndent()
        return decodeJs(eval(view, js)) == "yes"
    }

    suspend fun waitForChange(view: WebView, oldSignature: String, expectedYear: String? = null) {
        repeat(12) {
            delay(650)
            val p = scrapePage(view)
            if (p != null && p.signature.isNotBlank()) {
                val yearOk = expectedYear == null || p.year == expectedYear
                if (yearOk && p.signature != oldSignature) return
            }
        }
    }

    suspend fun collectHistory(view: WebView): List<LeclercHistoryItem> {
        clickMoreIfPresent(view)
        var first = scrapePage(view)
            ?: throw IllegalStateException("La page Mes commandes n'est pas encore disponible.")
        if (first.items.isEmpty()) {
            throw IllegalStateException("Aucun bon détecté. Termine la connexion/reCAPTCHA puis réessaie.")
        }

        val all = LinkedHashMap<String, LeclercHistoryItem>()
        val years = if (first.years.isNotEmpty()) first.years else listOf(first.year).filter { it.isNotBlank() }
        var pageCounter = 0

        for ((yearIndex, year) in years.withIndex()) {
            if (yearIndex > 0) {
                status = "Ouverture de l'année $year…"
                val before = scrapePage(view)?.signature.orEmpty()
                if (!selectYear(view, year)) break
                waitForChange(view, before, year)
                clickMoreIfPresent(view)
                first = scrapePage(view) ?: continue
            }

            var page = first
            val seenSignatures = HashSet<String>()
            while (true) {
                pageCounter++
                if (pageCounter > 250) break
                if (!seenSignatures.add(page.signature)) break

                page.items.forEach { item -> all.putIfAbsent(item.url, item) }
                status = "Scan ${page.year.ifBlank { year }} : ${all.size} commande(s) trouvée(s)…"

                if (!page.nextActive) break
                val before = page.signature
                if (!clickNext(view)) break
                waitForChange(view, before)
                val nextPage = scrapePage(view) ?: break
                if (nextPage.signature == before) break
                page = nextPage
            }
        }

        return all.values.toList()
    }

    suspend fun downloadPdf(view: WebView, item: LeclercHistoryItem): File {
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookies = cm.getCookie(item.url).orEmpty()
        val userAgent = view.settings.userAgentString.orEmpty()
        val referer = currentUrl
        val appContext = view.context.applicationContext

        return withContext(Dispatchers.IO) {
            val conn = URL(item.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.requestMethod = "GET"
            if (userAgent.isNotBlank()) conn.setRequestProperty("User-Agent", userAgent)
            if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
            if (referer.startsWith("http", true)) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "application/pdf,*/*;q=0.9")
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")

            val code = conn.responseCode
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            conn.disconnect()

            val isPdf = bytes.size >= 4 && bytes.decodeToString(0, 4).startsWith("%PDF")
            if (code !in 200..299 || !isPdf) {
                throw IllegalStateException("HTTP $code lors du téléchargement du bon ${item.orderId.ifBlank { "?" }}")
            }
            File.createTempFile("bdc_history_", ".pdf", appContext.cacheDir).also { it.writeBytes(bytes) }
        }
    }

    fun startBatch(view: WebView) {
        if (running) return
        running = true
        progress = 0f
        scope.launch {
            try {
                status = "Analyse de l'historique E.Leclerc…"
                val items = collectHistory(view)
                if (items.isEmpty()) throw IllegalStateException("Aucune commande détectée.")

                val existing = viewModel.driveOrdersLiveData.value.orEmpty()
                    .map { it.orderId.filter(Char::isDigit) }
                    .filter { it.isNotBlank() }
                    .toHashSet()
                val missing = items.filter {
                    val id = it.orderId.filter(Char::isDigit)
                    id.isBlank() || id !in existing
                }

                if (missing.isEmpty()) {
                    status = "${items.size} commande(s) trouvée(s) : tout est déjà importé."
                    progress = 1f
                    running = false
                    return@launch
                }

                var imported = 0
                var duplicates = 0
                var failed = 0
                status = "${items.size} trouvée(s), ${items.size - missing.size} déjà importée(s), ${missing.size} à récupérer."

                missing.forEachIndexed { index, item ->
                    status = "Téléchargement ${index + 1}/${missing.size} — commande ${item.orderId.ifBlank { "?" }}…"
                    try {
                        val file = downloadPdf(view, item)
                        val result = withContext(Dispatchers.IO) {
                            DriveImporter.import(view.context.applicationContext, Uri.fromFile(file))
                        }
                        when (result) {
                            is DriveImporter.Result.Imported -> imported++
                            is DriveImporter.Result.Duplicate -> duplicates++
                            is DriveImporter.Result.Failed -> failed++
                        }
                        file.delete()
                    } catch (e: Exception) {
                        failed++
                        if (e.message?.contains("HTTP 403") == true) {
                            throw IllegalStateException("Session Leclerc refusée pendant le batch (403). Reconnecte-toi puis relance.")
                        }
                    }
                    progress = (index + 1).toFloat() / missing.size.toFloat()
                    delay(120)
                }

                viewModel.refreshBudget()
                viewModel.calculateCurrentWeekBudget()
                viewModel.loadExpensesByCategory()
                status = "Batch terminé : $imported importée(s), $duplicates doublon(s), $failed échec(s)."
                progress = 1f
            } catch (e: Exception) {
                status = e.message ?: "Erreur pendant la synchronisation de l'historique."
            } finally {
                running = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Historique E.Leclerc", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss, enabled = !running) { Text("Fermer") }
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).also { view ->
                            webView = view
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(view, true)
                            view.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                useWideViewPort = false
                                loadWithOverviewMode = false
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    url?.let { currentUrl = it }
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                    loading = false
                                }
                            }
                            view.loadUrl(initialUrl)
                        }
                    },
                    update = { webView = it }
                )

                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (running || progress > 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { webView?.goBack() },
                            enabled = webView?.canGoBack() == true && !running
                        ) { Text("Retour") }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !running && !loading,
                            onClick = {
                                val view = webView
                                if (view == null) status = "Navigateur non prêt."
                                else startBatch(view)
                            }
                        ) {
                            Text(if (running) "Synchronisation…" else "Scanner et importer")
                        }
                    }
                }
            }
        }
    }
}