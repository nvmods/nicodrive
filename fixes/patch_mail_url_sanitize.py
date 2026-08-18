#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_mail_url_sanitize.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/drive/DriveMailSync.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

# Certains mails Leclerc contiennent, au milieu des jetons iIdC/dDtC, des espaces
# Unicode/NBSP ou caracteres zero-width. java.net.URI les refuse alors meme que le
# lien ouvert par un navigateur fonctionne. On retire uniquement ces caracteres de
# mise en page ; les caracteres significatifs du token (=, /, +, &, etc.) restent.
helper_anchor = '''    private fun hostOf(url: String): String? = try {
'''
helper = '''    private fun sanitizeMailUrl(raw: String): String {
        val htmlDecoded = raw
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&#38;", "&", ignoreCase = true)
            .replace("&#x26;", "&", ignoreCase = true)
            .replace("&nbsp;", "", ignoreCase = true)
            .replace("&#160;", "", ignoreCase = true)
            .replace("&#xA0;", "", ignoreCase = true)

        return buildString(htmlDecoded.length) {
            htmlDecoded.forEach { ch ->
                val invisible = ch.isWhitespace() ||
                    Character.isSpaceChar(ch) ||
                    ch == '\u200B' || ch == '\u200C' || ch == '\u200D' ||
                    ch == '\u2060' || ch == '\uFEFF' || ch == '\u00AD'
                if (!invisible) append(ch)
            }
        }.trimEnd('.', ',', ';')
    }

'''
if "private fun sanitizeMailUrl(" not in text:
    if helper_anchor not in text:
        raise SystemExit("Point insertion sanitizeMailUrl introuvable")
    text = text.replace(helper_anchor, helper + helper_anchor, 1)

# Toujours normaliser avant URI(), y compris pour la cle de deduplication.
text = text.replace(
    '''    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.takeIf { it.isNotBlank() }
''',
    '''    private fun hostOf(url: String): String? = try {
        URI(sanitizeMailUrl(url)).host?.lowercase()?.takeIf { it.isNotBlank() }
''',
    1,
)
text = text.replace(
    '''            val uri = URI(url)
            val query = uri.rawQuery.orEmpty()
''',
    '''            val cleanUrl = sanitizeMailUrl(url)
            val uri = URI(cleanUrl)
            val query = uri.rawQuery.orEmpty()
''',
    1,
)
text = text.replace(
    '''                url.lowercase()
            }
        } catch (_: Exception) {
            url.lowercase()
''',
    '''                cleanUrl.lowercase()
            }
        } catch (_: Exception) {
            sanitizeMailUrl(url).lowercase()
''',
    1,
)

# Nettoyage immediat des liens extraits du corps du mail.
text = text.replace(
    '''        val all = RE_URL.findAll(body)
            .map { it.value.trimEnd('.', ',', ';') }
''',
    '''        val all = RE_URL.findAll(body)
            .map { sanitizeMailUrl(it.value) }
''',
    1,
)

# Le fetch et les redirections passent eux aussi par la normalisation pour eviter
# qu'un Location HTTP ou un lien trouve dans une page reintroduise le probleme.
text = text.replace(
    '''        var current = url
        var currentReferer = referer
''',
    '''        var current = sanitizeMailUrl(url)
        var currentReferer = referer?.let(::sanitizeMailUrl)
''',
    1,
)
text = text.replace(
    '''                current = if (location.startsWith("http", ignoreCase = true)) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
''',
    '''                current = sanitizeMailUrl(
                    if (location.startsWith("http", ignoreCase = true)) {
                        location
                    } else {
                        URL(URL(current), location).toString()
                    }
                )
''',
    1,
)
text = text.replace(
    '''            val raw = match.value
            try {
                URL(URL(baseUrl), raw).toString()
''',
    '''            val raw = sanitizeMailUrl(match.value)
            try {
                sanitizeMailUrl(URL(URL(baseUrl), raw).toString())
''',
    1,
)

# On nettoie aussi l'URL au debut de downloadPdf, avant directVariant/fetch.
text = text.replace(
    '''    private fun downloadPdf(context: Context, url: String): PdfDownloadResult {
        return try {
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

            var res = fetch(context, url, cookies)
''',
    '''    private fun downloadPdf(context: Context, url: String): PdfDownloadResult {
        return try {
            val cleanUrl = sanitizeMailUrl(url)
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

            var res = fetch(context, cleanUrl, cookies)
''',
    1,
)
text = text.replace(
    '''                directVariant(url)?.let { add(it) }
''',
    '''                directVariant(cleanUrl)?.let { add(it) }
''',
    1,
)

required = [
    "private fun sanitizeMailUrl(",
    ".map { sanitizeMailUrl(it.value) }",
    "var current = sanitizeMailUrl(url)",
    "val cleanUrl = sanitizeMailUrl(url)",
]
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("Patch URL mail incomplet: " + ", ".join(missing))

target.write_text(text, encoding="utf-8")
print(f"Normalisation URL mails Leclerc appliquee dans {target}")
