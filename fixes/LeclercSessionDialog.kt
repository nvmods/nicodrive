package com.example.nicobudget.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    .padding(12.dp)
            ) {
                Text(
                    "Connexion E.Leclerc",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Connecte-toi normalement et valide le reCAPTCHA. " +
                        "Quand le bon de commande s'ouvre, ou une fois la connexion terminée, " +
                        "appuie sur « Session prête »."
                )
                Spacer(Modifier.height(8.dp))

                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(6.dp))
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).also { view ->
                            webView = view
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(view, true)

                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.databaseEnabled = true
                            view.settings.loadWithOverviewMode = true
                            view.settings.useWideViewPort = true

                            view.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
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
                    update = { view ->
                        webView = view
                    }
                )

                message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss
                    ) {
                        Text("Annuler")
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
                                message = "Aucun cookie de session détecté. Termine d'abord la connexion E.Leclerc."
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
