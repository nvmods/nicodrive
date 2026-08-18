#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_planner_tuning.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1) Quantités : un repas n'utilise pas systématiquement un paquet entier de
# chaque ingrédient. Ces ratios représentent une fraction de conditionnement
# habituel pour 2 personnes. La liste hebdomadaire cumule d'abord les fractions
# puis arrondit au nombre de paquets à acheter.
# ---------------------------------------------------------------------------
old_helper = '''private fun menuRecipes(): List<MenuRecipe> {
    fun n(label: String, family: DriveFoodFamily, vararg keywords: String, units: Double = 1.0) =
        MenuNeed(label, family, keywords.toList(), units)
'''
new_helper = '''private fun menuRecipes(): List<MenuRecipe> {
    fun defaultUnitsForTwo(family: DriveFoodFamily): Double = when (family) {
        DriveFoodFamily.POULTRY,
        DriveFoodFamily.BEEF,
        DriveFoodFamily.PORK,
        DriveFoodFamily.FISH -> 0.65
        DriveFoodFamily.EGGS -> 0.50
        DriveFoodFamily.POTATOES -> 0.45
        DriveFoodFamily.STARCHES -> 0.30
        DriveFoodFamily.VEGETABLES -> 0.45
        DriveFoodFamily.PIZZA_QUICHE,
        DriveFoodFamily.READY_MEALS,
        DriveFoodFamily.SANDWICH_SALAD -> 1.00
        DriveFoodFamily.BREAD -> 0.50
        DriveFoodFamily.DAIRY_CHEESE -> 0.25
        DriveFoodFamily.CONDIMENTS -> 0.10
        DriveFoodFamily.OTHER_MEAL -> 0.30
        DriveFoodFamily.OTHER_CORE -> 0.50
        else -> 0.50
    }

    fun n(label: String, family: DriveFoodFamily, vararg keywords: String, units: Double = -1.0) =
        MenuNeed(
            label,
            family,
            keywords.toList(),
            if (units > 0.0) units else defaultUnitsForTwo(family)
        )
'''
if old_helper in text:
    text = text.replace(old_helper, new_helper, 1)
elif "defaultUnitsForTwo" not in text:
    raise SystemExit("Helper menuRecipes introuvable")

# Coût affiché par repas = part estimée consommée, sans arrondir chaque ingrédient
# à un paquet entier. Le vrai nombre de paquets est calculé dans la liste semaine.
old_cost = '''        val q = ceil(need.unitsForTwo * servings.coerceAtLeast(1) / 2.0).toInt().coerceAtLeast(1)
        known += product.recentUnitPrice * q
'''
new_cost = '''        val q = need.unitsForTwo * servings.coerceAtLeast(1) / 2.0
        known += product.recentUnitPrice * q
'''
if old_cost in text:
    text = text.replace(old_cost, new_cost, 1)
elif "val q = need.unitsForTwo * servings.coerceAtLeast(1) / 2.0" not in text:
    raise SystemExit("Calcul coût repas introuvable")

# ---------------------------------------------------------------------------
# 2) Variété : l'ancien score ne pénalisait que la famille principale. Deux
# recettes différentes pouvaient donc enchaîner frites/salade/haricots toute la
# semaine. On pénalise maintenant les accompagnements exacts et la répétition de
# la famille de féculent.
# ---------------------------------------------------------------------------
old_score = '''    val repeatedFamily = used.count { it.primary == recipe.primary }
    val adjacentSame = used.lastOrNull()?.primary == recipe.primary
    val cost = estimateRecipeCost(recipe, servings, catalog, mode) ?: 12.0

    var score = when (mode) {
        MenuPlanMode.HABITS -> familyAffinity * 18.0 - repeatedFamily * 4.0
        MenuPlanMode.VARIED -> familyAffinity * 8.0 - repeatedFamily * 12.0
        MenuPlanMode.ECONOMICAL -> familyAffinity * 7.0 - cost * 0.65 - repeatedFamily * 8.0
        MenuPlanMode.QUICK -> familyAffinity * 6.0 + if (recipe.quick) 12.0 else -3.0 - repeatedFamily * 8.0
    }
    if (adjacentSame) score -= 10.0
'''
new_score = '''    val repeatedFamily = used.count { it.primary == recipe.primary }
    val adjacentSame = used.lastOrNull()?.primary == recipe.primary

    val usedNeedKeys = used.flatMap { r ->
        r.needs.map { n -> DriveProductNormalizer.key(n.label) }
    }
    val repeatedIngredients = recipe.needs.sumOf { need ->
        usedNeedKeys.count { it == DriveProductNormalizer.key(need.label) }
    }

    val starchFamilies = setOf(DriveFoodFamily.POTATOES, DriveFoodFamily.STARCHES)
    val usedStarchFamilies = used.flatMap { r ->
        r.needs.map { it.family }.filter { it in starchFamilies }
    }
    val repeatedStarchFamily = recipe.needs
        .filter { it.family in starchFamilies }
        .sumOf { need -> usedStarchFamilies.count { it == need.family } }

    val cost = estimateRecipeCost(recipe, servings, catalog, mode) ?: 12.0

    var score = when (mode) {
        MenuPlanMode.HABITS -> familyAffinity * 18.0 - repeatedFamily * 4.0 - repeatedIngredients * 1.5 - repeatedStarchFamily * 1.0
        MenuPlanMode.VARIED -> familyAffinity * 8.0 - repeatedFamily * 12.0 - repeatedIngredients * 5.0 - repeatedStarchFamily * 3.0
        MenuPlanMode.ECONOMICAL -> familyAffinity * 7.0 - cost * 0.65 - repeatedFamily * 8.0 - repeatedIngredients * 2.0 - repeatedStarchFamily * 1.5
        MenuPlanMode.QUICK -> familyAffinity * 6.0 + (if (recipe.quick) 12.0 else -3.0) - repeatedFamily * 8.0 - repeatedIngredients * 2.5 - repeatedStarchFamily * 1.5
    }
    if (adjacentSame) score -= 10.0
'''
if old_score in text:
    text = text.replace(old_score, new_score, 1)
elif "repeatedIngredients" not in text or "repeatedStarchFamily" not in text:
    raise SystemExit("Bloc score variété introuvable")

# ---------------------------------------------------------------------------
# 3) Liste de courses : cumuler les fractions sur les 7 repas puis seulement
# ensuite arrondir au conditionnement entier. Exemple : 3 x 0,3 paquet de pâtes
# -> 1 paquet, et non 3 paquets.
# ---------------------------------------------------------------------------
text = text.replace("        var quantity: Int,\n", "        var quantity: Double,\n", 1)

old_quantity = '''            val quantity = ceil(need.unitsForTwo * servings.coerceAtLeast(1) / 2.0)
                .toInt().coerceAtLeast(1)
            val product = chooseProduct(need, catalog, mode)
'''
new_quantity = '''            val quantity = need.unitsForTwo * servings.coerceAtLeast(1) / 2.0
            val product = chooseProduct(need, catalog, mode)
'''
if old_quantity in text:
    text = text.replace(old_quantity, new_quantity, 1)
elif "val quantity = need.unitsForTwo * servings.coerceAtLeast(1) / 2.0" not in text:
    raise SystemExit("Quantité liste de courses introuvable")

old_map = '''    return map.values.map {
        MenuShoppingItem(it.key, it.label, it.family, it.quantity, it.unitPrice, it.generic)
    }.sortedWith(compareBy<MenuShoppingItem> { it.family.label }.thenBy { it.label })
'''
new_map = '''    return map.values.map {
        val packs = ceil(it.quantity).toInt().coerceAtLeast(1)
        MenuShoppingItem(it.key, it.label, it.family, packs, it.unitPrice, it.generic)
    }.sortedWith(compareBy<MenuShoppingItem> { it.family.label }.thenBy { it.label })
'''
if old_map in text:
    text = text.replace(old_map, new_map, 1)
elif "val packs = ceil(it.quantity)" not in text:
    raise SystemExit("Finalisation liste de courses introuvable")

# Clarifier ce que représente le montant sur les cartes.
text = text.replace(
    'if (estimate != null) append(" · ~${estimate.eur()}")',
    'if (estimate != null) append(" · part estimée ~${estimate.eur()}")',
    1,
)

required = [
    "defaultUnitsForTwo",
    "repeatedIngredients",
    "repeatedStarchFamily",
    "val packs = ceil(it.quantity)",
    "part estimée",
]
missing = [m for m in required if m not in text]
if missing:
    raise SystemExit("Patch tuning menu incomplet: " + ", ".join(missing))

target.write_text(text, encoding="utf-8")
print(f"Menu planner ajusté : variété accompagnements + coût hebdomadaire consolidé dans {target}")
