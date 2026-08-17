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

/**
 * Connexion interactive E.Leclerc.
 *
 * Le reCAPTCHA reste entièrement manuel. NicoBudget ne lit jamais les
 * identifiants E.Leclerc : il réutilise uniquement la session/cookies créés
 * par le WebView après la connexion.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeclercSessionDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSessionSaved: () -> Unit
) {
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var sessionSubmitted by remember { mutableStateOf(false) }

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

    fun submitSession(view: WebView, sessionUrl: String = currentUrl) {
        if (sessionSubmitted) return
        sessionSubmitted = true
        if (persistSession(view, sessionUrl)) {
            loading = false
            onSessionSaved()
        } else {
            sessionSubmitted = false
            loading = false
            message = "Aucun cookie de session détecté. Termine d'abord la connexion E.Leclerc."
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
                /*
                 * Le WebView occupe le centre de l'écran avec des réserves fixes
                 * pour les barres Compose. Les barres sont aussi placées au-dessus
                 * via zIndex : une navigation Web ne peut donc plus les faire
                 * disparaître visuellement.
                 */
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
                                    // Certains parcours SSO ne déclenchent pas toujours
                                    // onPageFinished. Le progrès à 100 % évite donc un
                                    // indicateur "Chargement…" bloqué indéfiniment.
                                    if (newProgress >= 100) loading = false
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
                                    message = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                    loading = false
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        message = "E.Leclerc a répondu HTTP ${errorResponse?.statusCode ?: "?"}."
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
                                    message = "Erreur de chargement : ${description ?: "inconnue"}"
                                }
                            }

                            /*
                             * Après authentification, E.Leclerc peut répondre directement
                             * par un PDF. Android WebView passe alors par DownloadListener
                             * au lieu de onPageFinished. C'est précisément un signe que la
                             * session est valide : on la sauvegarde et on laisse NicoBudget
                             * reprendre le téléchargement lui-même.
                             */
                            view.setDownloadListener { url, _, _, mimeType, _ ->
                                if (!url.isNullOrBlank()) currentUrl = url
                                loading = false
                                if (
                                    mimeType?.contains("pdf", ignoreCase = true) == true ||
                                    url?.contains("bon", ignoreCase = true) == true
                                ) {
                                    submitSession(view, url ?: currentUrl)
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
                            TextButton(onClick = onDismiss) { Text("Fermer") }
                        }
                        Text(
                            "Connecte-toi et valide le reCAPTCHA, puis touche « Session prête ».",
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
                                color = MaterialTheme.colorScheme.error,
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
                                enabled = webView?.canGoBack() == true && !sessionSubmitted
                            ) {
                                Text("Retour")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !sessionSubmitted,
                                onClick = {
                                    val view = webView
                                    if (view == null) {
                                        message = "Navigateur non prêt."
                                    } else {
                                        submitSession(view)
                                    }
                                }
                            ) {
                                Text(if (sessionSubmitted) "Validation…" else "Session prête")
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
                            Text("Chargement…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
