#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_history_layout.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/LeclercHistoryDialog.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

if "import androidx.compose.ui.zIndex\n" not in text:
    marker = "import androidx.compose.ui.Modifier\n"
    if marker not in text:
        raise SystemExit("Import Modifier introuvable")
    text = text.replace(marker, marker + "import androidx.compose.ui.zIndex\n", 1)

marker = '''    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {'''
pos = text.find(marker)
if pos < 0:
    raise SystemExit("Bloc Dialog historique introuvable")

new_tail = r'''    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
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
            ) {
                // Le WebView reste derrière les deux barres fixes. Les actions ne
                // participent plus au calcul de hauteur du WebView : sur certains
                // appareils la barre de navigation Android réduisait la fenêtre et
                // poussait le bouton "Scanner et importer" hors écran.
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 62.dp, bottom = 150.dp)
                        .navigationBarsPadding(),
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
                    update = { webView = it }
                )

                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .zIndex(2f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Historique E.Leclerc", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onDismiss, enabled = !running) {
                            Text("Fermer")
                        }
                    }
                }

                Surface(
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 8.dp)
                        .zIndex(3f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(8.dp))
                        if (running || progress > 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { webView?.goBack() },
                                enabled = webView?.canGoBack() == true && !running
                            ) {
                                Text("Retour")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !running && !loading,
                                onClick = {
                                    val view = webView
                                    if (view == null) {
                                        status = "Navigateur non prêt."
                                    } else {
                                        startBatch(view)
                                    }
                                }
                            ) {
                                Text(if (running) "Synchronisation…" else "Scanner et importer")
                            }
                        }
                    }
                }

                if (loading && !running) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .zIndex(4f),
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
'''

text = text[:pos] + new_tail
target.write_text(text, encoding="utf-8")
print(f"Layout historique corrigé : {target}")
