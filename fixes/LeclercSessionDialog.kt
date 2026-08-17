package com.example.nicobudget.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.ui.zIndex
import com.example.nicobudget.drive.DriveMailSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Connexion interactive E.Leclerc.
 *
 * Le reCAPTCHA reste entièrement manuel. NicoBudget ne lit jamais les
 * identifiants E.Leclerc : il réutilise uniquement la session/cookies créés
 * par le WebView après la connexion.
 *
 * Depuis b64, le PDF n'est plus récupéré en relançant ensuite le lien du mail
 * avec une nouvelle session HTTP. Quand le WebView authentifié déclenche le
 * téléchargement du bon, on récupère immédiatement CETTE URL finale avec les
 * cookies et le User-Agent exacts du WebView. C'est important car certains
 * anciens bons semblent être protégés individuellement.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeclercSessionDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onPdfCaptured: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var verifyingTarget by remember { mutableStateOf(false) }
    var capturingPdf by remember { mutableStateOf(false) }

    val initialHost = remember(initialUrl) {
        try {
            URI(initialUrl).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
    val initialPath = remember(initialUrl) {
        try {
            URI(initialUrl).path.orEmpty().lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    fun persistSession(view: WebView, sessionUrl: String = currentUrl): Boolean {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        return DriveMailSync.saveBrowserSession(
            context = view.context.applicationContext,
            originalUrl = initialUrl,
            currentUrl = sessionUrl,
            originalCookies = cookieManager.getCookie(initialUrl),
            currentCookies = cookieManager.getCookie(sessionUrl),
            userAgent = view.settings.userAgentString.orEmpty()
        )
    }

    fun isTargetLike(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty().lowercase()
            val sameHost = initialHost.isNotBlank() && host == initialHost
            val samePath = initialPath.isNotBlank() && path == initialPath
            val bdcPath = path.contains("bon") && path.contains("commande")
            sameHost && (samePath || bdcPath)
        } catch (_: Exception) {
            false
        }
    }

    fun verifySessionOnTarget(view: WebView) {
        if (capturingPdf) return

        if (!persistSession(view)) {
            message = "Aucun cookie de session détecté. Termine d'abord la connexion E.Leclerc."
            loading = false
            return
        }

        verifyingTarget = true
        loading = true
        message = "Connexion détectée. Ouverture du bon dans la session authentifiée…"
        view.loadUrl(initialUrl)
    }

    fun capturePdfFromDownload(
        view: WebView,
        downloadUrl: String,
        mimeType: String?
    ) {
        if (capturingPdf) return
        if (!downloadUrl.startsWith("http", ignoreCase = true)) {
            loading = false
            message = "Leclerc a proposé un téléchargement ${downloadUrl.substringBefore(':')} non récupérable directement."
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val cookieHeader = cookieManager.getCookie(downloadUrl).orEmpty()
        val userAgent = view.settings.userAgentString.orEmpty()
        val referer = currentUrl.takeIf { it.startsWith("http", ignoreCase = true) }
        val appContext = view.context.applicationContext

        // Sauvegarde également les cookies du domaine exact qui propose le PDF.
        DriveMailSync.saveBrowserSession(
            context = appContext,
            originalUrl = initialUrl,
            currentUrl = downloadUrl,
            originalCookies = cookieManager.getCookie(initialUrl),
            currentCookies = cookieHeader,
            userAgent = userAgent
        )

        capturingPdf = true
        verifyingTarget = false
        loading = true
        message = "Bon accessible dans le navigateur. Récupération du PDF…"

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    conn.useCaches = false
                    conn.requestMethod = "GET"
                    if (userAgent.isNotBlank()) {
                        conn.setRequestProperty("User-Agent", userAgent)
                    }
                    if (cookieHeader.isNotBlank()) {
                        conn.setRequestProperty("Cookie", cookieHeader)
                    }
                    if (!referer.isNullOrBlank() && referer != downloadUrl) {
                        conn.setRequestProperty("Referer", referer)
                    }
                    conn.setRequestProperty("Accept", "application/pdf,*/*;q=0.9")
                    conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                    conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
                    conn.setRequestProperty("Sec-Fetch-Dest", "document")

                    val code = conn.responseCode
                    val type = conn.contentType.orEmpty()
                    val bytes = try {
                        conn.inputStream.use { it.readBytes() }
                    } catch (_: Exception) {
                        conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    }
                    conn.disconnect()

                    val isPdf = bytes.size >= 4 &&
                        bytes.decodeToString(0, 4).startsWith("%PDF")
                    if (code in 200..299 && isPdf) {
                        val file = File.createTempFile("bdc_web_", ".pdf", appContext.cacheDir)
                        file.writeBytes(bytes)
                        file to null
                    } else {
                        null to "Téléchargement navigateur refusé : HTTP $code, ${type.ifBlank { mimeType ?: "type inconnu" }}."
                    }
                } catch (e: Exception) {
                    null to "Erreur récupération PDF : ${e.javaClass.simpleName}: ${e.message}"
                }
            }

            val file = result.first
            if (file != null) {
                loading = false
                capturingPdf = false
                message = "PDF récupéré. Import dans NicoBudget…"
                onPdfCaptured(file)
            } else {
                loading = false
                capturingPdf = false
                message = result.second
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 92.dp, bottom = 76.dp),
                    factory = { ctx ->
                        WebView(ctx).also { view ->
                            webView = view

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(view, true)

                            view.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }

                            view.isVerticalScrollBarEnabled = true
                            view.isHorizontalScrollBarEnabled = false

                            view.webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress >= 100 && !capturingPdf) {
                                        loading = false
                                    }
                                }
                            }

                            view.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    url?.let { currentUrl = it }
                                    loading = true
                                    if (!verifyingTarget && !capturingPdf) message = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                    val currentView = view ?: return

                                    persistSession(currentView, url ?: currentUrl)
                                    loading = false

                                    if (verifyingTarget && isTargetLike(url)) {
                                        verifyingTarget = false
                                        message = "Le bon est ouvert dans la session E.Leclerc. " +
                                            "S'il y a un bouton PDF/Télécharger dans la page, touche-le : NicoBudget capturera directement le fichier."
                                    } else if (verifyingTarget) {
                                        message = "Leclerc n'a pas encore ouvert le bon. " +
                                            "Si une connexion ou un reCAPTCHA est demandé, termine-le puis retouche « Vérifier le bon »."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        verifyingTarget = false
                                        message = "E.Leclerc a répondu HTTP ${errorResponse?.statusCode ?: "?"}. " +
                                            "La session n'est pas encore utilisable pour ce bon."
                                    }
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    loading = false
                                    verifyingTarget = false
                                    message = "Erreur de chargement : ${description ?: "inconnue"}"
                                }
                            }

                            // C'est ici que l'on obtient l'URL réellement autorisée par la
                            // session WebView. On ne la jette plus pour rescanner le mail :
                            // on tente immédiatement de récupérer ce PDF précis.
                            view.setDownloadListener { url, _, _, mimeType, _ ->
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    capturePdfFromDownload(view, url, mimeType)
                                }
                            }

                            view.loadUrl(initialUrl)
                        }
                    },
                    update = { view -> webView = view }
                )

                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .zIndex(2f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Connexion E.Leclerc",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = onDismiss, enabled = !capturingPdf) {
                                Text("Fermer")
                            }
                        }
                        Text(
                            when {
                                capturingPdf -> "Récupération du bon depuis la session navigateur…"
                                verifyingTarget -> "Ouverture du lien du bon dans la session authentifiée…"
                                else -> "Connecte-toi et valide le reCAPTCHA si nécessaire, puis touche « Vérifier le bon »."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .zIndex(2f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        message?.let {
                            Text(
                                it,
                                color = if (verifyingTarget || capturingPdf) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { webView?.goBack() },
                                enabled = webView?.canGoBack() == true && !capturingPdf
                            ) {
                                Text("Retour")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !capturingPdf,
                                onClick = {
                                    val view = webView
                                    if (view == null) {
                                        message = "Navigateur non prêt."
                                    } else {
                                        verifySessionOnTarget(view)
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        capturingPdf -> "Récupération…"
                                        verifyingTarget -> "Retester le bon"
                                        else -> "Vérifier le bon"
                                    }
                                )
                            }
                        }
                    }
                }

                if (loading) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .zIndex(3f),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                if (capturingPdf) "Récupération du PDF…" else "Chargement…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
