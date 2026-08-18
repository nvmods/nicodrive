#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_multidrive_year_reset.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/LeclercHistoryDialog.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

old_years = '''        val years = if (first.years.isNotEmpty()) {
            first.years.filter { it.isNotBlank() }
        } else {
            listOf(first.year).filter { it.isNotBlank() }
        }
'''
new_years = '''        val years = if (first.years.isNotEmpty()) {
            first.years
                .filter { it.isNotBlank() }
                .distinct()
                .sortedDescending()
        } else {
            listOf(first.year).filter { it.isNotBlank() }
        }
'''
if old_years not in text:
    raise SystemExit("Bloc liste des années introuvable")
text = text.replace(old_years, new_years, 1)

old_loop = '''        for ((yearIndex, year) in years.withIndex()) {
            if (yearIndex > 0) {
                status = "$driveLabel · ouverture de l'année $year…"
                val before = scrapePage(view)?.signature.orEmpty()
                if (!selectYear(view, year)) continue
                waitForChange(view, before, year)
                clickMoreIfPresent(view)
                first = scrapePage(view) ?: continue
            }

            var page = first
'''
new_loop = '''        for (year in years) {
            // Ne jamais supposer que la première option de la dropdown est celle
            // actuellement affichée. Leclerc mémorise parfois l'année précédente
            // quand on passe d'un Drive à l'autre (ex. 2025 au lieu de 2026).
            var selectedPage = scrapePage(view) ?: continue
            if (selectedPage.year != year) {
                status = "$driveLabel · ouverture de l'année $year…"
                val before = selectedPage.signature
                if (!selectYear(view, year)) continue
                waitForChange(view, before, year)
                clickMoreIfPresent(view)
                selectedPage = scrapePage(view) ?: continue
            }

            // Sécurité supplémentaire : si la page n'a pas réellement basculé,
            // ne surtout pas scanner l'année courante sous une mauvaise étiquette.
            if (selectedPage.year.isNotBlank() && selectedPage.year != year) {
                status = "$driveLabel · année $year non chargée, nouvelle tentative…"
                val forcedUrl = view.url.orEmpty()
                    .substringBefore('?') + "?AnneeSelectionnee=$year"
                view.loadUrl(forcedUrl)
                waitForHistoryRoot(view)
                selectedPage = scrapePage(view) ?: continue
                if (selectedPage.year.isNotBlank() && selectedPage.year != year) continue
            }

            first = selectedPage
            var page = first
'''
if old_loop not in text:
    raise SystemExit("Boucle de scan des années introuvable")
text = text.replace(old_loop, new_loop, 1)

target.write_text(text, encoding="utf-8")
print(f"Reset d'année multi-Drive appliqué dans {target}")

# Correctifs de fin de chaîne : ils doivent passer après la copie des sources de base.
for patch_name in (
    "patch_xlsx_export.py",
    "patch_mail_url_sanitize.py",
    "patch_mail_html_fallback.py",
    "patch_drive_current_cycle.py",
    "patch_history_budget_reconcile.py",
    "patch_budget_cycle_metadata.py",
    "patch_product_normalization.py",
):
    patch = Path(__file__).with_name(patch_name)
    if not patch.exists():
        raise SystemExit(f"Patch introuvable: {patch}")
    subprocess.run([sys.executable, str(patch), str(root)], check=True)
