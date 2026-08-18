#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_planner_v3.py <project_root>")

root = Path(sys.argv[1])
src = Path(__file__).with_name("DriveMenuPlannerV3.kt")
dst = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"

if not src.exists():
    raise SystemExit(f"Source V3 introuvable: {src}")
if not dst.exists():
    raise SystemExit(f"Cible menu planner introuvable: {dst}")

text = src.read_text(encoding="utf-8")
required = [
    "7 jours · midi + soir",
    "Incompatibilités / aliments à éviter",
    "servings_v3",
    "excluded_v3",
    "Adapté :",
    "buildShoppingListV3",
]
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("Source V3 incomplète: " + ", ".join(missing))

dst.write_text(text, encoding="utf-8")
print(f"Menu planner V3 installé : {dst}")
print("- 14 créneaux midi/soir")
print("- 0 à 8 convives par créneau")
print("- incompatibilités avec substitutions automatiques")
print("- liste de courses consolidée sur tous les repas actifs")
