#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_planner.py <project_root>")

root = Path(sys.argv[1])
models = root / "app/src/main/java/com/example/nicobudget/data/model/DriveFoodModels.kt"
dao = root / "app/src/main/java/com/example/nicobudget/data/db/DriveOrderDao.kt"
food_ui = root / "app/src/main/java/com/example/nicobudget/ui/DriveFoodInsights.kt"
planner_src = Path(__file__).with_name("DriveMenuPlanner.kt")
planner_dst = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"

for path in (models, dao, food_ui, planner_src):
    if not path.exists():
        raise SystemExit(f"Fichier introuvable: {path}")

planner_dst.write_text(planner_src.read_text(encoding="utf-8"), encoding="utf-8")

# ---------------------------------------------------------------------------
# Modèle : le générateur a besoin de la date exacte et du prix unitaire réellement
# observé pour privilégier les références habituelles et estimer le coût récent.
# ---------------------------------------------------------------------------
text = models.read_text(encoding="utf-8")
old_model = '''data class DriveFoodAnalysisLine(
    val orderRowId: Int,
    val month: String,
    val section: String,
    val label: String,
    val quantity: Double,
    val total: Double
)
'''
new_model = '''data class DriveFoodAnalysisLine(
    val orderRowId: Int,
    val date: String,
    val month: String,
    val section: String,
    val label: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
)
'''
if old_model in text:
    text = text.replace(old_model, new_model, 1)
elif "val unitPrice: Double" not in text or "val date: String" not in text:
    raise SystemExit("DriveFoodAnalysisLine introuvable")
models.write_text(text, encoding="utf-8")

# DAO : enrichit la requête créée par patch_food_habits.py.
text = dao.read_text(encoding="utf-8")
old_query = '''        SELECT l.orderId AS orderRowId,
               substr(o.date, 1, 7) AS month,
               COALESCE(l.section, 'Sans rayon') AS section,
               l.label AS label,
               l.quantity AS quantity,
               l.total AS total
'''
new_query = '''        SELECT l.orderId AS orderRowId,
               o.date AS date,
               substr(o.date, 1, 7) AS month,
               COALESCE(l.section, 'Sans rayon') AS section,
               l.label AS label,
               l.quantity AS quantity,
               l.unitPrice AS unitPrice,
               l.total AS total
'''
if old_query in text:
    text = text.replace(old_query, new_query, 1)
elif "o.date AS date" not in text or "l.unitPrice AS unitPrice" not in text:
    raise SystemExit("Requête getFoodAnalysisLines introuvable")
dao.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# UI : ajoute l'entrée du générateur à la vue Habitudes alimentaires. Le dialogue
# plein écran reçoit les lignes déjà chargées : aucune seconde lecture DB inutile.
# ---------------------------------------------------------------------------
text = food_ui.read_text(encoding="utf-8")
state_anchor = '''    var editingProduct by remember { mutableStateOf<DriveFoodProductSummary?>(null) }
    var overrideRevision by remember { mutableIntStateOf(0) }
'''
state_new = '''    var editingProduct by remember { mutableStateOf<DriveFoodProductSummary?>(null) }
    var overrideRevision by remember { mutableIntStateOf(0) }
    var menuPlannerOpen by remember { mutableStateOf(false) }
'''
if "var menuPlannerOpen" not in text:
    if state_anchor not in text:
        raise SystemExit("Point insertion état menu planner introuvable")
    text = text.replace(state_anchor, state_new, 1)

button_anchor = '''        Text(
            "Tape une famille pour voir ses produits. Une référence mal classée peut être corrigée manuellement ; la correction est mémorisée sur ce téléphone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    selectedFamily?.let { familyKey ->
'''
button_new = '''        Text(
            "Tape une famille pour voir ses produits. Une référence mal classée peut être corrigée manuellement ; la correction est mémorisée sur ce téléphone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { menuPlannerOpen = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = lines.isNotEmpty()
        ) {
            Text("Générer menus + liste de courses")
        }
        Text(
            "7 prochains dîners, références habituelles et coût estimé avec les prix réellement observés dans tes Drive.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (menuPlannerOpen) {
        DriveMenuPlannerDialog(
            lines = lines,
            onDismiss = { menuPlannerOpen = false }
        )
    }

    selectedFamily?.let { familyKey ->
'''
if "Générer menus + liste de courses" not in text:
    if button_anchor not in text:
        raise SystemExit("Point insertion bouton menu planner introuvable")
    text = text.replace(button_anchor, button_new, 1)

food_ui.write_text(text, encoding="utf-8")

print(f"Menu planner V2 intégré : {planner_dst}")
print("- historique prix réel (date + unitPrice)")
print("- génération 7 dîners + verrouillage/régénération")
print("- liste consolidée + estimation coût + copie presse-papiers")
