#!/usr/bin/env python3
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_project.py <project_root>")

root = Path(sys.argv[1])
sync_target = root / "app/src/main/java/com/example/nicobudget/drive/DriveMailSync.kt"
screen_target = root / "app/src/main/java/com/example/nicobudget/ui/DriveScreen.kt"

for target in (sync_target, screen_target):
    if not target.exists():
        raise SystemExit(f"Fichier introuvable: {target}")

# ---------------------------------------------------------------------------
# DriveMailSync : réutilisation de la session WebView + marquage du bon capturé
# ---------------------------------------------------------------------------
text = sync_target.read_text(encoding="utf-8")

alias_import = "import android.webkit.CookieManager as WebCookieManager\n"
if alias_import not in text:
    marker = "import android.content.Context\n"
    if marker not in text:
        raise SystemExit("Import Context introuvable dans DriveMailSync")
    text = text.replace(marker, marker + alias_import, 1)

helper_marker = "    private fun browserCookieFor(context: Context, url: String): String? {"
live_helper = '''    private fun liveWebViewCookieFor(url: String): String? = try {
        WebCookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

'''
if "private fun liveWebViewCookieFor(" not in text:
    if helper_marker not in text:
        raise SystemExit("Point insertion liveWebViewCookieFor introuvable")
    text = text.replace(helper_marker, live_helper + helper_marker, 1)

if "val liveWebCookies = liveWebViewCookieFor(current)" not in text:
    pattern = re.compile(
        r'mergedCookieHeader\(\s*browserCookieFor\(context,\s*current\),\s*requestCookies\s*\)\?\.let\s*\{\s*conn\.setRequestProperty\("Cookie",\s*it\)\s*\}'
    )
    replacement = '''val liveWebCookies = liveWebViewCookieFor(current)
        val persistedWebCookies = browserCookieFor(context, current)
        val webCookies = mergedCookieHeader(
            liveWebCookies,
            listOfNotNull(persistedWebCookies)
        )
        mergedCookieHeader(
            webCookies,
            requestCookies
        )?.let { conn.setRequestProperty("Cookie", it) }'''
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit("Bloc Cookie HTTP introuvable")

if "fun markBrowserLinkProcessed(" not in text:
    marker = "    private fun ensureMailcap() {"
    helper = '''    fun markBrowserLinkProcessed(context: Context, originalUrl: String) {
        val processedLinks = loadKeySet(context, PREF_PROCESSED_LINKS)
        markLinkProcessed(
            context,
            processedLinks,
            normalizeLinkForKey(originalUrl)
        )
    }

'''
    if marker not in text:
        raise SystemExit("Point insertion markBrowserLinkProcessed introuvable")
    text = text.replace(marker, helper + marker, 1)

sync_target.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# DriveScreen : flux de connexion Leclerc b65 + statistiques mensuelles fiables
# ---------------------------------------------------------------------------
text = screen_target.read_text(encoding="utf-8")

state_marker = "    var autoSync by remember { mutableStateOf(DriveMailSync.isAutoSync(context)) }\n"
if "var leclercAuthUrl by remember" not in text:
    if state_marker not in text:
        raise SystemExit("Point insertion état Leclerc introuvable")
    text = text.replace(
        state_marker,
        state_marker
        + "    var leclercAuthUrl by remember { mutableStateOf<String?>(null) }\n"
        + "    var leclercRetryUrl by remember { mutableStateOf<String?>(null) }\n",
        1,
    )
elif "var leclercRetryUrl by remember" not in text:
    text = text.replace(
        "    var leclercAuthUrl by remember { mutableStateOf<String?>(null) }\n",
        "    var leclercAuthUrl by remember { mutableStateOf<String?>(null) }\n"
        + "    var leclercRetryUrl by remember { mutableStateOf<String?>(null) }\n",
        1,
    )

old_call = "                    DriveMailSync.sync(context, config)"
new_call = "                    DriveMailSync.sync(context, config, includeHistorical = true)"
if old_call in text:
    text = text.replace(old_call, new_call, 1)
elif new_call not in text:
    raise SystemExit("Appel DriveMailSync.sync introuvable")

status_marker = '                status = "${report.mailsScanned} mails scannés, " +\n'
auth_assignment = '''                if (report.authRequiredUrl != null) {
                    leclercRetryUrl = report.authRequiredUrl
                    leclercAuthUrl = report.authRequiredUrl
                } else if (report.pdfs.isNotEmpty()) {
                    leclercRetryUrl = null
                }
'''
if "leclercAuthUrl = report.authRequiredUrl" not in text:
    if status_marker not in text:
        raise SystemExit("Point insertion rapport Leclerc introuvable")
    text = text.replace(status_marker, auth_assignment + status_marker, 1)

config_marker = "    if (showConfig) {\n"
dialog_block = '''    leclercAuthUrl?.let { authUrl ->
        LeclercSessionDialog(
            initialUrl = authUrl,
            onDismiss = { leclercAuthUrl = null },
            onPdfCaptured = { file ->
                DriveMailSync.markBrowserLinkProcessed(context, authUrl)
                leclercAuthUrl = null
                leclercRetryUrl = null
                viewModel.importDrivePdf(Uri.fromFile(file), context) { msg ->
                    status = msg + "\\nRecherche du bon suivant…"
                    syncFromMail()
                }
            }
        )
    }

'''
if "LeclercSessionDialog(" not in text:
    if config_marker not in text:
        raise SystemExit("Point insertion dialogue Leclerc introuvable")
    text = text.replace(config_marker, dialog_block + config_marker, 1)

if 'Text("Reconnecter E.Leclerc")' not in text:
    import_anchor = 'Text("Importer des PDF manuellement")'
    anchor_pos = text.find(import_anchor)
    if anchor_pos < 0:
        raise SystemExit("Bouton import PDF introuvable")
    next_surface_item = text.find("\n        item {\n            Surface(", anchor_pos)
    if next_surface_item < 0:
        raise SystemExit("Point insertion après import PDF introuvable")
    retry_block = '''

        if (leclercRetryUrl != null) {
            item {
                OutlinedButton(
                    onClick = { leclercRetryUrl?.let { leclercAuthUrl = it } },
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reconnecter E.Leclerc")
                }
            }
        }
'''
    text = text[:next_surface_item] + retry_block + text[next_surface_item:]

old_stats = '''                            if (m.savings > 0.0) {
                                Text(
                                    "dont ${m.savings.eur()} économisés",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
'''
new_stats = '''                            val advantages = m.savings + m.ticketLeclerc
                            val lineGap = m.total - m.lineTotal

                            if (m.savings > 0.0) {
                                Text(
                                    "Économies immédiates : ${m.savings.eur()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (m.ticketLeclerc > 0.0) {
                                Text(
                                    "Ticket E.Leclerc gagné : ${m.ticketLeclerc.eur()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (advantages > 0.0) {
                                Text(
                                    "Avantages totaux : ${advantages.eur()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (kotlin.math.abs(lineGap) >= 0.01) {
                                Text(
                                    "Lignes produits : ${m.lineTotal.eur()} · écart total/lignes : ${lineGap.eur()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
'''
if "Avantages totaux : ${advantages.eur()}" not in text:
    if old_stats not in text:
        raise SystemExit("Bloc statistiques mensuelles introuvable")
    text = text.replace(old_stats, new_stats, 1)

screen_target.write_text(text, encoding="utf-8")

print("Correctifs projet appliqués :")
print(f"- {sync_target}")
print(f"- {screen_target}")
