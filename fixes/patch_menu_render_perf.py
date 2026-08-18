#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_render_perf.py <project_root>")

root = Path(sys.argv[1])
planner = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"
if not planner.exists():
    raise SystemExit(f"Fichier introuvable: {planner}")

text = planner.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1. Les agrégats Drive lourds ne doivent jamais être construits dans la
#    composition Compose. On les calcule sur Dispatchers.Default et on garde
#    le résultat en mémoire entre deux ouvertures du planner.
# ---------------------------------------------------------------------------
if "import kotlinx.coroutines.Dispatchers" not in text:
    anchor = "import kotlin.math.ceil\n"
    if anchor not in text:
        raise SystemExit("Import kotlin.math.ceil introuvable")
    text = text.replace(
        anchor,
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n" + anchor,
        1,
    )

profile_model = '''private data class MenuPersonProfileV4(
    val id: String,
    val name: String,
    val excluded: Set<String> = emptySet()
)
'''
prepared_model = profile_model + '''
private data class MenuPreparedDataV5(
    val catalog: List<MenuCatalogProductV3>,
    val affinity: Map<DriveFoodFamily, Double>
)

private object MenuPreparedCacheV5 {
    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedData: MenuPreparedDataV5? = null

    fun get(key: String): MenuPreparedDataV5? =
        if (cachedKey == key) cachedData else null

    @Synchronized
    fun put(key: String, data: MenuPreparedDataV5): MenuPreparedDataV5 {
        cachedKey = key
        cachedData = data
        return data
    }
}
'''
if "MenuPreparedDataV5" not in text:
    if profile_model not in text:
        raise SystemExit("MenuPersonProfileV4 introuvable")
    text = text.replace(profile_model, prepared_model, 1)

old_prepare = '''    val recipes = remember { menuRecipesV3() }
    val rules = remember { ingredientRulesV3() }
    val catalog = remember(lines) { buildMenuCatalogV3(lines, familyPrefs) }
    val affinity = remember(lines) { buildFamilyAffinityV3(lines, familyPrefs) }
'''
new_prepare = '''    val recipes = remember { menuRecipesV3() }
    val rules = remember { ingredientRulesV3() }

    // La classification/normalisation de plusieurs milliers de lignes était le
    // principal gel de l'interface. La clé change si l'historique OU un override
    // de famille produit change.
    val preparedKey = remember(lines, familyPrefs) {
        val last = lines.maxByOrNull { it.orderRowId }
        buildString {
            append(lines.size)
            append(':')
            append(last?.orderRowId ?: 0)
            append(':')
            append(last?.date.orEmpty())
            append(':')
            append(familyPrefs.all.hashCode())
        }
    }
    var preparedData by remember(preparedKey) {
        mutableStateOf(MenuPreparedCacheV5.get(preparedKey))
    }

    LaunchedEffect(preparedKey) {
        if (preparedData == null) {
            preparedData = withContext(Dispatchers.Default) {
                val built = MenuPreparedDataV5(
                    catalog = buildMenuCatalogV3(lines, familyPrefs),
                    affinity = buildFamilyAffinityV3(lines, familyPrefs)
                )
                MenuPreparedCacheV5.put(preparedKey, built)
            }
        }
    }

    val catalog = preparedData?.catalog.orEmpty()
    val affinity = preparedData?.affinity.orEmpty()
'''
if old_prepare in text:
    text = text.replace(old_prepare, new_prepare, 1)
elif "preparedKey" not in text:
    raise SystemExit("Bloc préparation catalogue planner introuvable")

# ---------------------------------------------------------------------------
# 2. La liste de courses est recalculée hors thread UI. Les +/- convives et les
#    changements de profils ne doivent plus bloquer l'affichage.
# ---------------------------------------------------------------------------
old_shopping = '''    val shopping = remember(resolvedSlots, servings, mode, catalog) {
        val meals = resolvedSlots.mapIndexedNotNull { index, recipe ->
            recipe?.let { it to servings.getOrElse(index) { 0 } }
        }
        buildShoppingListV3(meals, catalog, mode)
    }
    val knownTotal = shopping.mapNotNull { it.total }.sum()
    val unknownCount = shopping.count { it.unitPrice == null }
'''
new_shopping = '''    var shopping by remember { mutableStateOf<List<MenuShoppingItemV3>>(emptyList()) }
    var shoppingLoading by remember { mutableStateOf(false) }

    LaunchedEffect(resolvedSlots, servings, mode, catalog) {
        if (catalog.isEmpty()) {
            shopping = emptyList()
            shoppingLoading = false
        } else {
            shoppingLoading = true
            val meals = resolvedSlots.mapIndexedNotNull { index, recipe ->
                recipe?.let { it to servings.getOrElse(index) { 0 } }
            }
            shopping = withContext(Dispatchers.Default) {
                buildShoppingListV3(meals, catalog, mode)
            }
            shoppingLoading = false
        }
    }

    val slotEstimates = remember(resolvedSlots, servings, catalog, mode) {
        resolvedSlots.mapIndexed { index, recipe ->
            recipe?.let {
                estimateRecipeCostV3(
                    it,
                    servings.getOrElse(index) { 0 },
                    catalog,
                    mode
                )
            }
        }
    }
    val knownTotal = shopping.mapNotNull { it.total }.sum()
    val unknownCount = shopping.count { it.unitPrice == null }
'''
if old_shopping in text:
    text = text.replace(old_shopping, new_shopping, 1)
elif "shoppingLoading" not in text:
    raise SystemExit("Bloc liste de courses planner introuvable")

# Etat d'affichage de la liste détaillée : repliée au démarrage pour ne pas créer
# inutilement plusieurs dizaines de composables hors écran.
state_anchor = '''    var confirmedMessage by remember { mutableStateOf<String?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
'''
state_new = '''    var confirmedMessage by remember { mutableStateOf<String?>(null) }
    var showShoppingDetails by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
'''
if "showShoppingDetails" not in text:
    if state_anchor not in text:
        raise SystemExit("Etat confirmedMessage introuvable")
    text = text.replace(state_anchor, state_new, 1)

# Une estimation de carte était recalculée à chaque recomposition de chaque slot.
old_estimate = '''                                val resolved = resolvedSlots.getOrNull(index)
                                val estimate = resolved?.let { estimateRecipeCostV3(it, people, catalog, mode) }
'''
new_estimate = '''                                val resolved = resolvedSlots.getOrNull(index)
                                val estimate = slotEstimates.getOrNull(index)
'''
if old_estimate in text:
    text = text.replace(old_estimate, new_estimate, 1)
elif "slotEstimates.getOrNull(index)" not in text:
    raise SystemExit("Calcul estimation dans les cartes introuvable")

# Affiche un vrai état de préparation au lieu de dire qu'il n'y a pas assez
# d'historique pendant que le catalogue est encore en cours de calcul.
old_catalog_empty = '''                    if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (planIds.size == SLOT_COUNT) {
'''
new_catalog_empty = '''                    if (preparedData == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Préparation du catalogue de menus…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (catalog.isEmpty()) {
                        Text("Pas assez d'historique Drive pour générer les menus.")
                    } else if (planIds.size == SLOT_COUNT) {
'''
if old_catalog_empty in text:
    text = text.replace(old_catalog_empty, new_catalog_empty, 1)
elif "Préparation du catalogue de menus" not in text:
    raise SystemExit("Bloc catalog.isEmpty introuvable")

# ---------------------------------------------------------------------------
# 3. Liste de courses : détails repliés par défaut. Le coût reste visible et la
#    liste peut être dépliée à la demande.
# ---------------------------------------------------------------------------
shopping_marker = '''                        shopping.forEach { item ->
'''
if "Afficher la liste détaillée" not in text:
    pos = text.find(shopping_marker)
    if pos < 0:
        raise SystemExit("shopping.forEach introuvable")
    replacement = '''                        OutlinedButton(
                            onClick = { showShoppingDetails = !showShoppingDetails },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (showShoppingDetails) "Masquer la liste détaillée"
                                else "Afficher la liste détaillée (${shopping.size})"
                            )
                        }
                        if (shoppingLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        if (showShoppingDetails) {
                            shopping.forEach { item ->
'''
    text = text[:pos] + text[pos:].replace(shopping_marker, replacement, 1)

    # Ferme le if(showShoppingDetails) juste avant la carte de coût.
    cost_marker = '''
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp,
'''
    start = text.find(replacement)
    cost_pos = text.find(cost_marker, start)
    if cost_pos < 0:
        raise SystemExit("Carte coût après shopping introuvable")
    text = text[:cost_pos] + "\n                        }\n" + text[cost_pos:]

planner.write_text(text, encoding="utf-8")
print(f"Optimisations rendu planner appliquées dans {planner}")
print("- catalogue + affinités calculés hors thread UI et mis en cache")
print("- liste de courses calculée hors thread UI")
print("- estimations des 14 cartes mémoïsées")
print("- détails de courses repliés par défaut")
