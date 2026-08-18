#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: inspect_navigation.py <project_root>")

root = Path(sys.argv[1])
main = root / "app/src/main/java/com/example/nicobudget/MainActivity.kt"
if not main.exists():
    raise SystemExit(f"MainActivity introuvable: {main}")

print("===== NICOBUDGET_MAINACTIVITY_BEGIN =====")
print(main.read_text(encoding="utf-8"))
print("===== NICOBUDGET_MAINACTIVITY_END =====")
