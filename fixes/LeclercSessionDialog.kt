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
import java.net.URI

/**
 * Connexion interactive E.Leclerc.
 *
 * Le reCAPTCHA reste entièrement manuel. NicoBudget ne lit jamais les
 * identifiants E.Leclerc : il réutilise uniquement la session/cookies créés
 * par le WebView après la connexion.
 *
 * Important : après "Session prête", on ne ferme plus immédiatement le
 * navigateur. On recharge d'abord le lien du bon dans LE MÊME WebView afin
 * que le SSO puisse déposer les cookies du domaine Drive. C'est seulement
 * quand le bon est réellement atteint (ou qu'un PDF est proposé) que la
 * session est validée et transmise à DriveMailSync.
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
    var verifyingTarget by remember { mutableStateOf(false) }
    var sessionSubmitted by remember { mutableStateOf(false) }

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

    fun finishSession(view: WebView, sessionUrl: String = currentUrl) {
        if (sessionSubmitted) return
        sessionSubmitted = true
        verifyingTarget = false
        loading = false
        if (persistSession(view, sessionUrl)) {
            onSessionSaved()
        } else {
            sessionSubmitted = false
            message = "Le bon est accessible, mais aucun cookie de session n'a été trouvé."
        }
    }

    fun verifySessionOnTarget(view: WebView) {
        if (sessionSubmitted) return

        // Sauvegarde d'abord la session du domaine de connexion/SSO courant.
        if (!persistSession(view)) {
            message = "Aucun cookie de session détecté. Termine d'abord la connexion E.Leclerc."
            loading = false
            return
        }

        verifyingTarget = true
        loading = true
        message = "Connexion détectée. Vérification du bon de commande…"

        // Étape essentielle : repasser par le lien d'origine dans le WebView
        // authentifié pour laisser E.Leclerc poser les cookies du domaine Drive.
        view.loadUrl(initialUrl)
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
                                    if (newProgress >= 100 && !verifyingTarget) {
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
                                    if (!verifyingTarget) message = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                    val currentView = view ?: return

                                    // Toujours mémoriser les cookies gagnés au fil des
                                    // redirections SSO, sans fermer prématurément le dialogue.
                                    persistSession(currentView, url ?: currentUrl)

                                    if (verifyingTarget && isTargetLike(url)) {
                                        finishSession(currentView, url ?: currentUrl)
                                    } else {
                                        loading = false
                                        if (verifyingTarget) {
                                            message = "Leclerc n'a pas encore rouvert le bon. " +
                                                "Si la page demande encore une connexion/reCAPTCHA, termine-la puis retouche « Vérifier le bon »."
                                        }
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
                                        sessionSubmitted = false
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
                                    sessionSubmitted = false
                                    message = "Erreur de chargement : ${description ?: "inconnue"}"
                                }
                            }

                            // Un PDF proposé au WebView prouve que le navigateur authentifié
                            // a réellement franchi la protection du bon de commande.
                            view.setDownloadListener { url, _, _, mimeType, _ ->
                                if (!url.isNullOrBlank()) currentUrl = url
                                loading = false
                                if (
                                    mimeType?.contains("pdf", ignoreCase = true) == true ||
                                    url?.contains("bon", ignoreCase = true) == true
                                ) {
                                    finishSession(view, url ?: currentUrl)
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
                            if (verifyingTarget) {
                                "Vérification du lien du bon dans la session authentifiée…"
                            } else {
                                "Connecte-toi et valide le reCAPTCHA, puis touche « Vérifier le bon »."
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
                                color = if (verifyingTarget) {
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
                                        verifySessionOnTarget(view)
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        sessionSubmitted -> "Validation…"
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
                            Text("Chargement…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
