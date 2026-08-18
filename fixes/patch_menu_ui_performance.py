#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_ui_performance.py <project_root>")

root = Path(sys.argv[1])
planner = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"
if not planner.exists():
    raise SystemExit(f"Fichier introuvable: {planner}")

text = planner.read_text(encoding="utf-8")

# 1) Les analyses de l'historique Drive sont assez lourdes (classification,
# groupBy, tris, médianes). Elles ne doivent pas être exécutées pendant la
# composition Compose sur le thread UI.
if "import kotlinx.coroutines.Dispatchers" not in text:
    anchor = "import kotlin.math.ceil\n"
    replacement = (
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.withContext\n"
        "import kotlin.math.ceil\n"
    )
    if anchor not in text:
        raise SystemExit("Point d'insertion imports coroutines introuvable")
    text = text.replace(anchor, replacement, 1)

old_derived = '''    val recipes = remember { menuRecipesV3() }
    val rules = remember { ingredientRulesV3() }
    val catalog = remember(lines) { buildMenuCatalogV3(lines, familyPrefs) }
    val affinity = remember(lines) { buildFamilyAffinityV3(lines, familyPrefs) }
'''
new_derived = '''    val recipes = remember { menuRecipesV3() }
    val rules = remember { ingredientRulesV3() }
    val recipesById = remember(recipes) { recipes.associateBy { it.id } }

    var derivedCatalog by remember(lines) {
        mutableStateOf<List<MenuCatalogProductV3>?>(null)
    }
    var derivedAffinity by remember(lines) {
        mutableStateOf<Map<DriveFoodFamily, Double>?>(null)
    }

    LaunchedEffect(lines) {
        derivedCatalog = null
        derivedAffinity = null
        val (catalogResult, affinityResult) = withContext(Dispatchers.Default) {
            buildMenuCatalogV3(lines, familyPrefs) to
                buildFamilyAffinityV3(lines, familyPrefs)
        }
        derivedCatalog = catalogResult
        derivedAffinity = affinityResult
    }

    val catalog = derivedCatalog.orEmpty()
    val affinity = derivedAffinity.orEmpty()
'''
if "var derivedCatalog by remember" not in text:
    if old_derived not in text:
        raise SystemExit("Bloc calcul catalogue/affinités introuvable")
    text = text.replace(old_derived, new_derived, 1)

# 2) Résolution des recettes : lookup O(1) au lieu d'un firstOrNull sur toute
# la liste à chacun des 14 créneaux et à chaque modification de profil.
old_lookup = '''            else planIds.getOrNull(index)
                ?.let { id -> recipes.firstOrNull { it.id == id } }
                ?.let { recipe ->
'''
new_lookup = '''            else planIds.getOrNull(index)
                ?.let { id -> recipesById[id] }
                ?.let { recipe ->
'''
if old_lookup in text:
    text = text.replace(old_lookup, new_lookup, 1)
elif "?.let { id -> recipesById[id] }" not in text:
    raise SystemExit("Lookup des recettes V4 introuvable")

# 3) Calculer une seule fois les 14 estimations tant que les repas/convives/
# prix/mode ne changent pas. Un clic sur verrou, case à cocher ou dialogue de
# profil ne doit plus recalculer les coûts de toute la semaine.
estimate_anchor = '''        buildShoppingListV3(meals, catalog, mode)
    }
    val knownTotal = shopping.mapNotNull { it.total }.sum()
'''
estimate_block = '''        buildShoppingListV3(meals, catalog, mode)
    }
    val slotEstimates = remember(resolvedSlots, servings, catalog, mode) {
        (0 until SLOT_COUNT).map { index ->
            val people = servings.getOrElse(index) { 0 }
            if (people <= 0) null
            else resolvedSlots.getOrNull(index)?.let { recipe ->
                estimateRecipeCostV3(recipe, people, catalog, mode)
            }
        }
    }
    val knownTotal = shopping.mapNotNull { it.total }.sum()
'''
if "val slotEstimates = remember" not in text:
    if estimate_anchor not in text:
        raise SystemExit("Point d'insertion cache estimations introuvable")
    text = text.replace(estimate_anchor, estimate_block, 1)

old_inline_estimate = '''                                val estimate = resolved?.let { estimateRecipeCostV3(it, people, catalog, mode) }
'''
new_inline_estimate = '''                                val estimate = slotEstimates.getOrNull(index)
'''
if old_inline_estimate in text:
    text = text.replace(old_inline_estimate, new_inline_estimate, 1)
elif "val estimate = slotEstimates.getOrNull(index)" not in text:
    raise SystemExit("Calcul inline des estimations introuvable")

# 4) Pendant le calcul initial, afficher explicitement un état de préparation
# plutôt que le faux message « pas assez d'historique ».
old_empty = '''                    if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (planIds.size == SLOT_COUNT) {
'''
new_empty = '''                    if (derivedCatalog == null || derivedAffinity == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Préparation des menus à partir de l'historique Drive…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (planIds.size == SLOT_COUNT) {
'''
if "Préparation des menus à partir de l'historique Drive" not in text:
    if old_empty not in text:
        raise SystemExit("Branche catalogue vide introuvable")
    text = text.replace(old_empty, new_empty, 1)

planner.write_text(text, encoding="utf-8")

required = [
    "withContext(Dispatchers.Default)",
    "recipesById[id]",
    "slotEstimates",
    "Préparation des menus à partir de l'historique Drive",
]
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("Patch performance incomplet: " + ", ".join(missing))

print(f"Optimisations UI du menu planner appliquées dans {planner}")
print("- calcul catalogue/affinités hors thread UI")
print("- lookup recettes indexé")
print("- estimations des 14 créneaux mémorisées")
print("- état de chargement explicite")
