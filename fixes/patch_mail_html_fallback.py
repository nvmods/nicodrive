#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_mail_html_fallback.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/drive/DriveMailSync.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

old = '''                            } else {
                                failures.add(firstFailure)
                            }
                        }
'''
new = '''                            } else if (firstFailureCode != null && firstFailureCode in 200..299 && includeHistorical) {
                                // Certains nouveaux liens Leclerc répondent HTTP 200 mais
                                // renvoient une page HTML/JS au lieu du PDF. Ce n'est pas
                                // une réussite exploitable par HttpURLConnection : on passe
                                // le lien au WebView, qui sait terminer la navigation et
                                // capturer le PDF comme pour le flux authentifié historique.
                                authRequiredUrl = bdcLinks.firstOrNull()
                                failures.add(
                                    "Leclerc a répondu HTTP $firstFailureCode avec une page web au lieu du PDF. " +
                                        "Ouverture dans le navigateur E.Leclerc."
                                )
                                break@messageLoop
                            } else {
                                failures.add(firstFailure)
                            }
                        }
'''

# Le bloc visé est celui de la gestion finale d'un mail sans PDF. On vérifie aussi
# la présence de la branche 401/403 juste avant afin d'éviter un remplacement ailleurs.
marker = '''                        if (!found && firstFailure != null) {
                            if (firstFailureCode == 401 || firstFailureCode == 403) {
'''
if "page web au lieu du PDF" not in text:
    if marker not in text:
        raise SystemExit("Bloc échec mail Leclerc introuvable")
    pos = text.find(marker)
    tail = text[pos:]
    if old not in tail:
        raise SystemExit("Fin bloc échec mail introuvable")
    tail = tail.replace(old, new, 1)
    text = text[:pos] + tail

target.write_text(text, encoding="utf-8")
print(f"Fallback WebView pour HTTP 200 HTML appliqué dans {target}")
