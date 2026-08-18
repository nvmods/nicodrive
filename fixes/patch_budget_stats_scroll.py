#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_budget_stats_scroll.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/BudgetStatsScreen.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

if "import androidx.compose.foundation.rememberScrollState" not in text:
    anchor = "import androidx.compose.foundation.layout.*\n"
    if anchor not in text:
        raise SystemExit("Import layout introuvable dans BudgetStatsScreen")
    text = text.replace(
        anchor,
        anchor + "import androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n",
        1,
    )

old = "    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n"
new = '''    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
'''

if old in text:
    text = text.replace(old, new, 1)
elif ".verticalScroll(rememberScrollState())" not in text:
    raise SystemExit("Column principal BudgetStatsScreen non reconnu")

target.write_text(text, encoding="utf-8")
print(f"Scroll Stats budget corrigé : {target}")
