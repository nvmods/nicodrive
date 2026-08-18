#!/usr/bin/env python3
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: inspect_budget_runtime.py <project_root>")

root = Path(sys.argv[1])
base = root / "app/src/main/java/com/example/nicobudget"
print("===== DIAGNOSTIC BUDGET NICO =====")

patterns = [
    "data class MonthlyBudgetEntity",
    "fun calculateCurrentWeekBudget",
    "updateDisposableLeftover",
    "currentWeek",
    "weekIndex",
    "startDate",
    "endDate",
]

for path in sorted(base.rglob("*.kt")):
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        continue
    if not any(p in text for p in patterns):
        continue
    print(f"--- FILE {path.relative_to(root)} ---")
    lines = text.splitlines()
    interesting = set()
    for i, line in enumerate(lines):
        if any(p in line for p in patterns):
            for j in range(max(0, i-12), min(len(lines), i+35)):
                interesting.add(j)
    last = None
    for j in sorted(interesting):
        if last is not None and j > last + 1:
            print("...")
        print(f"{j+1:04d}: {lines[j]}")
        last = j
print("===== FIN DIAGNOSTIC BUDGET =====")
