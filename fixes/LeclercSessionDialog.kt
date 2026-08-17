package com.example.nicobudget.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nicobudget.drive.DriveMailSync

/**
 * Fenêtre de connexion interactive E.Leclerc.
 *
 * Le reCAPTCHA n'est jamais automatisé : l'utilisateur effectue lui-même la
 * connexion dans le WebView. Une fois connecté, on récupère uniquement les
 * cookies de session applicables aux URL E.Leclerc afin que DriveMailSync
 * puisse relire les anciens bons de commande.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeclercSessionDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSessionSaved: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Barre haute fixe : elle reste toujours visible, même si la page Web
                // tente de prendre toute la hauteur disponible.
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
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
                            TextButton(onClick = onDismiss) {
                                Text("Fermer")
                            }
                        }
                        Text(
                            "Connecte-toi normalement et valide le reCAPTCHA. " +
                                "Une fois connecté, appuie sur « Session prête ».",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Le WebView est strictement contenu entre les barres haute et basse.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
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

                                    // Leclerc fournit une page mobile responsive. Le mode
                                    // "wide viewport + overview" la rendait minuscule / mal
                                    // dimensionnée sur certains téléphones.
                                    useWideViewPort = false
                                    loadWithOverviewMode = false

                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                }

                                view.isVerticalScrollBarEnabled = true
                                view.isHorizontalScrollBarEnabled = true
                                view.webChromeClient = WebChromeClient()

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

                                view.loadUrl(initialUrl)
                            }
                        },
                        update = { view ->
                            webView = view
                        }
                    )

                    if (loading) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp),
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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

                // Barre basse fixe et protégée de la barre de navigation Android.
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
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
                                enabled = webView?.canGoBack() == true
                            ) {
                                Text("Retour")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val view = webView
                                    if (view == null) {
                                        message = "Navigateur non prêt."
                                        return@Button
                                    }

                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.flush()
                                    val originalCookies = cookieManager.getCookie(initialUrl)
                                    val currentCookies = cookieManager.getCookie(currentUrl)
                                    val saved = DriveMailSync.saveBrowserSession(
                                        context = context,
                                        originalUrl = initialUrl,
                                        currentUrl = currentUrl,
                                        originalCookies = originalCookies,
                                        currentCookies = currentCookies,
                                        userAgent = view.settings.userAgentString.orEmpty()
                                    )

                                    if (saved) {
                                        onSessionSaved()
                                    } else {
                                        message = "Aucun cookie de session détecté. " +
                                            "Termine d'abord la connexion E.Leclerc."
                                    }
                                }
                            ) {
                                Text("Session prête")
                            }
                        }
                    }
                }
            }
        }
    }
}
