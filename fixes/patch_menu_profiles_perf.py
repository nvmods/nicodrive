#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_menu_profiles_perf.py <project_root>")

root = Path(sys.argv[1])
planner = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"
food_ui = root / "app/src/main/java/com/example/nicobudget/ui/DriveFoodInsights.kt"
dao = root / "app/src/main/java/com/example/nicobudget/data/db/DriveOrderDao.kt"
vm = root / "app/src/main/java/com/example/nicobudget/data/model/BudgetViewModel.kt"
main = root / "app/src/main/java/com/example/nicobudget/MainActivity.kt"

for path in (planner, food_ui, dao, vm, main):
    if not path.exists():
        raise SystemExit(f"Fichier introuvable: {path}")

# ---------------------------------------------------------------------------
# 1. Stats : le générateur quitte la vue statistiques.
# ---------------------------------------------------------------------------
text = food_ui.read_text(encoding="utf-8")
text = text.replace(
    '''    var overrideRevision by remember { mutableIntStateOf(0) }
    var menuPlannerOpen by remember { mutableStateOf(false) }
''',
    '''    var overrideRevision by remember { mutableIntStateOf(0) }
''',
    1
)

old_planner_block = '''        Spacer(Modifier.height(10.dp))
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
new_planner_block = '''    }

    selectedFamily?.let { familyKey ->
'''
if old_planner_block in text:
    text = text.replace(old_planner_block, new_planner_block, 1)
elif "menuPlannerOpen" in text:
    raise SystemExit("Bloc ancien menu planner stats non reconnu")
food_ui.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# 2. Cache d'analyse alimentaire invalidé par COUNT(*).
# ---------------------------------------------------------------------------
text = dao.read_text(encoding="utf-8")
if "getFoodAnalysisLineCount" not in text:
    anchor = '''    suspend fun getFoodAnalysisLines(): List<DriveFoodAnalysisLine>
'''
    block = '''    suspend fun getFoodAnalysisLines(): List<DriveFoodAnalysisLine>

    @Query("SELECT COUNT(*) FROM drive_order_lines")
    suspend fun getFoodAnalysisLineCount(): Int
'''
    if anchor not in text:
        raise SystemExit("getFoodAnalysisLines introuvable pour ajout du cache")
    text = text.replace(anchor, block, 1)
    dao.write_text(text, encoding="utf-8")

text = vm.read_text(encoding="utf-8")
old_vm = '''    suspend fun getDriveFoodAnalysisLines(): List<DriveFoodAnalysisLine> =
        driveOrderDao.getFoodAnalysisLines()
'''
new_vm = '''    private var driveFoodAnalysisCache: List<DriveFoodAnalysisLine>? = null
    private var driveFoodAnalysisCacheCount: Int = -1

    suspend fun getDriveFoodAnalysisLines(): List<DriveFoodAnalysisLine> {
        val lineCount = driveOrderDao.getFoodAnalysisLineCount()
        val cached = driveFoodAnalysisCache
        if (cached != null && lineCount == driveFoodAnalysisCacheCount) {
            return cached
        }
        return driveOrderDao.getFoodAnalysisLines().also { fresh ->
            driveFoodAnalysisCache = fresh
            driveFoodAnalysisCacheCount = lineCount
        }
    }
'''
if old_vm in text:
    text = text.replace(old_vm, new_vm, 1)
elif "driveFoodAnalysisCache" not in text:
    raise SystemExit("Méthode getDriveFoodAnalysisLines non reconnue")
vm.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# 3. Navigation : entrée dédiée "Menus & courses".
# ---------------------------------------------------------------------------
text = main.read_text(encoding="utf-8")
if 'navController.navigate("menus")' not in text:
    drawer_anchor = '''                            DrawerItem(Icons.Default.BarChart, "Stats Drive") {
                                navController.navigate("drivestats") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
'''
    drawer_new = drawer_anchor + '''                            DrawerItem(Icons.Default.DateRange, "Menus & courses") {
                                navController.navigate("menus") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
'''
    if drawer_anchor not in text:
        raise SystemExit("Entrée Stats Drive du drawer introuvable")
    text = text.replace(drawer_anchor, drawer_new, 1)

if 'composable("menus")' not in text:
    nav_anchor = '''                                composable("drivestats") { DriveStatsScreen(viewModel) }
'''
    nav_new = nav_anchor + '''                                composable("menus") { DriveMenuPlannerScreen(viewModel) }
'''
    if nav_anchor not in text:
        raise SystemExit("Route drivestats introuvable")
    text = text.replace(nav_anchor, nav_new, 1)
main.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# 4. Planner : écran dédié + profils personnes.
# ---------------------------------------------------------------------------
text = planner.read_text(encoding="utf-8")

if "MenuPersonProfileV4" not in text:
    anchor = '''private data class IngredientRuleV3(
    val id: String,
    val label: String,
    val aliases: List<String>,
    val alternatives: List<MenuNeedV3>
)
'''
    insert = anchor + '''
private data class MenuPersonProfileV4(
    val id: String,
    val name: String,
    val excluded: Set<String> = emptySet()
)
'''
    if anchor not in text:
        raise SystemExit("IngredientRuleV3 introuvable")
    text = text.replace(anchor, insert, 1)

if "fun DriveMenuPlannerScreen(" not in text:
    anchor = '''@Composable
fun DriveMenuPlannerDialog(
'''
    screen = '''@Composable
fun DriveMenuPlannerScreen(viewModel: BudgetViewModel) {
    var lines by remember { mutableStateOf<List<DriveFoodAnalysisLine>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var plannerOpen by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            lines = viewModel.getDriveFoodAnalysisLines()
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Menus & courses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Planifie midi et soir, adapte les quantités aux convives et génère la liste de courses depuis ton historique Drive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Chargement de l'historique Drive…")
        } else if (lines.isEmpty()) {
            Text("Pas assez de données Drive pour générer des menus.")
        } else {
            Button(
                onClick = { plannerOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ouvrir le planning de la semaine")
            }
            Text(
                "${lines.size} lignes Drive disponibles. Les analyses sont mises en cache et ne sont rechargées que si l'historique change.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (plannerOpen && !loading && lines.isNotEmpty()) {
        DriveMenuPlannerDialog(
            lines = lines,
            onDismiss = { plannerOpen = false }
        )
    }
}

@Composable
fun DriveMenuPlannerDialog(
'''
    if anchor not in text:
        raise SystemExit("DriveMenuPlannerDialog introuvable")
    text = text.replace(anchor, screen, 1)

state_anchor = '''    var excluded by remember { mutableStateOf(loadExcludedV3(prefs)) }
    var showFoodPrefs by remember { mutableStateOf(false) }
    var draftExcluded by remember { mutableStateOf(excluded) }
    var confirmedMessage by remember { mutableStateOf<String?>(null) }
'''
state_new = '''    var excluded by remember { mutableStateOf(loadExcludedV3(prefs)) }
    var showFoodPrefs by remember { mutableStateOf(false) }
    var draftExcluded by remember { mutableStateOf(excluded) }

    var profiles by remember { mutableStateOf(loadProfilesV4(prefs)) }
    var draftProfiles by remember { mutableStateOf(profiles) }
    var showProfiles by remember { mutableStateOf(false) }
    var slotProfiles by remember { mutableStateOf(loadSlotProfilesV4(prefs)) }
    var participantSlot by remember { mutableStateOf<Int?>(null) }
    var draftParticipants by remember { mutableStateOf<Set<String>>(emptySet()) }

    var confirmedMessage by remember { mutableStateOf<String?>(null) }
'''
if "var profiles by remember" not in text:
    if state_anchor not in text:
        raise SystemExit("Etats planner V3 introuvables")
    text = text.replace(state_anchor, state_new, 1)

old_resolved = '''    val resolvedSlots = remember(planIds, servings, excluded, rules) {
        (0 until SLOT_COUNT).map { index ->
            if (servings.getOrElse(index) { 0 } <= 0) null
            else planIds.getOrNull(index)
                ?.let { id -> recipes.firstOrNull { it.id == id } }
                ?.let { recipe -> resolveRecipeV3(recipe, excluded, rules) }
        }
    }

    val shopping = remember(planIds, servings, mode, catalog, excluded) {
'''
new_resolved = '''    val resolvedSlots = remember(planIds, servings, excluded, rules, profiles, slotProfiles) {
        (0 until SLOT_COUNT).map { index ->
            val people = servings.getOrElse(index) { 0 }
            if (people <= 0) null
            else planIds.getOrNull(index)
                ?.let { id -> recipes.firstOrNull { it.id == id } }
                ?.let { recipe ->
                    val selectedIds = slotProfiles[index].orEmpty()
                    val selectedProfiles = profiles.filter { it.id in selectedIds }
                    resolveRecipeForProfilesV4(
                        recipe = recipe,
                        globalExcluded = excluded,
                        rules = rules,
                        selectedProfiles = selectedProfiles,
                        totalPeople = people
                    )
                }
        }
    }

    val shopping = remember(resolvedSlots, servings, mode, catalog) {
'''
if "resolveRecipeForProfilesV4(" not in text:
    if old_resolved not in text:
        raise SystemExit("Bloc resolvedSlots V3 introuvable")
    text = text.replace(old_resolved, new_resolved, 1)

profiles_anchor = '''                    if (excluded.isNotEmpty()) {
                        Text(
                            "Les ingrédients exclus sont remplacés automatiquement par un accompagnement compatible quand la recette le permet.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
'''
profiles_new = '''                    if (excluded.isNotEmpty()) {
                        Text(
                            "Les ingrédients exclus ici concernent tout le foyer. Pour un goût individuel, utilise plutôt les profils personnes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            draftProfiles = profiles
                            showProfiles = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (profiles.isEmpty()) "Profils personnes"
                            else "Profils personnes (${profiles.size})"
                        )
                    }
                    Text(
                        "Chaque profil peut avoir ses aliments refusés. Affecte ensuite les profils aux repas : l'alternative ne sera achetée que pour les personnes concernées.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
'''
if "Profils personnes (" not in text:
    if profiles_anchor not in text:
        raise SystemExit("Point insertion bouton profils introuvable")
    text = text.replace(profiles_anchor, profiles_new, 1)

slot_anchor = '''                                            TextButton(onClick = { setServings(index, people + 1) }) { Text("+") }
                                        }

                                        if (people == 0) {
'''
slot_new = '''                                            TextButton(onClick = { setServings(index, people + 1) }) { Text("+") }
                                        }

                                        if (people > 0 && profiles.isNotEmpty()) {
                                            val selectedIds = slotProfiles[index].orEmpty()
                                            val selectedNames = profiles
                                                .filter { it.id in selectedIds }
                                                .map { it.name }
                                            TextButton(
                                                onClick = {
                                                    participantSlot = index
                                                    draftParticipants = selectedIds
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    if (selectedNames.isEmpty()) "👥 Affecter les profils"
                                                    else "👥 ${selectedNames.joinToString(", ")}"
                                                )
                                            }
                                        }

                                        if (people == 0) {
'''
if "Affecter les profils" not in text:
    if slot_anchor not in text:
        raise SystemExit("Ligne convives introuvable")
    text = text.replace(slot_anchor, slot_new, 1)

end_dialog_anchor = '''    }
}

private fun defaultUnitsForTwoV3'''
dialogs = '''    }

    if (showProfiles) {
        AlertDialog(
            onDismissRequest = { showProfiles = false },
            title = { Text("Profils personnes") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Crée les personnes du foyer puis coche uniquement leurs aliments refusés. Ces règles sont individuelles.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))

                    draftProfiles.forEachIndexed { profileIndex, profile ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                OutlinedTextField(
                                    value = profile.name,
                                    onValueChange = { value ->
                                        draftProfiles = draftProfiles.toMutableList().also { list ->
                                            list[profileIndex] = profile.copy(name = value.take(24))
                                        }.toList()
                                    },
                                    label = { Text("Nom") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                rules.forEach { rule ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = rule.id in profile.excluded,
                                            onCheckedChange = { checkedValue ->
                                                val nextExcluded = if (checkedValue) {
                                                    profile.excluded + rule.id
                                                } else {
                                                    profile.excluded - rule.id
                                                }
                                                draftProfiles = draftProfiles.toMutableList().also { list ->
                                                    list[profileIndex] = profile.copy(excluded = nextExcluded)
                                                }.toList()
                                            }
                                        )
                                        Text(rule.label)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        draftProfiles = draftProfiles.filterNot { it.id == profile.id }
                                    }
                                ) { Text("Supprimer ce profil") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (draftProfiles.size < 8) {
                        OutlinedButton(
                            onClick = {
                                val id = "p" + System.currentTimeMillis().toString()
                                draftProfiles = draftProfiles + MenuPersonProfileV4(
                                    id = id,
                                    name = "Personne ${draftProfiles.size + 1}"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Ajouter une personne") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profiles = draftProfiles
                            .map { it.copy(name = it.name.trim().ifBlank { "Personne" }) }
                        val validIds = profiles.map { it.id }.toSet()
                        slotProfiles = slotProfiles
                            .mapValues { (_, ids) -> ids.intersect(validIds) }
                            .filterValues { it.isNotEmpty() }
                        prefs.edit()
                            .putString("profiles_v4", encodeProfilesV4(profiles))
                            .putString("slot_profiles_v4", encodeSlotProfilesV4(slotProfiles))
                            .apply()
                        showProfiles = false
                    }
                ) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { showProfiles = false }) { Text("Annuler") }
            }
        )
    }

    participantSlot?.let { slotIndex ->
        val people = servings.getOrElse(slotIndex) { 0 }
        AlertDialog(
            onDismissRequest = { participantSlot = null },
            title = { Text("Qui mange à ce repas ?") },
            text = {
                Column {
                    Text(
                        "Le repas est prévu pour $people personne(s). Sélectionne les profils connus ; les places restantes sont traitées sans restriction particulière.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = profile.id in draftParticipants,
                                onCheckedChange = { checkedValue ->
                                    draftParticipants = if (checkedValue) {
                                        if (draftParticipants.size < 8) draftParticipants + profile.id
                                        else draftParticipants
                                    } else {
                                        draftParticipants - profile.id
                                    }
                                }
                            )
                            Column {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                if (profile.excluded.isNotEmpty()) {
                                    val labels = rules
                                        .filter { it.id in profile.excluded }
                                        .joinToString { it.label }
                                    Text(
                                        "Évite : $labels",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (draftParticipants.size > people) {
                            setServings(slotIndex, draftParticipants.size)
                        }
                        slotProfiles = slotProfiles.toMutableMap().also { map ->
                            if (draftParticipants.isEmpty()) map.remove(slotIndex)
                            else map[slotIndex] = draftParticipants
                        }.toMap()
                        prefs.edit()
                            .putString("slot_profiles_v4", encodeSlotProfilesV4(slotProfiles))
                            .apply()
                        participantSlot = null
                    }
                ) { Text("Appliquer") }
            },
            dismissButton = {
                TextButton(onClick = { participantSlot = null }) { Text("Annuler") }
            }
        )
    }
}

private fun defaultUnitsForTwoV3'''
if "Qui mange à ce repas ?" not in text:
    if end_dialog_anchor not in text:
        raise SystemExit("Fin de DriveMenuPlannerDialog introuvable")
    text = text.replace(end_dialog_anchor, dialogs, 1)

helpers_anchor = '''private fun defaultUnitsForTwoV3'''
helpers = r'''private fun encodeProfilesV4(profiles: List<MenuPersonProfileV4>): String =
    profiles.joinToString("||") { profile ->
        val safeName = profile.name.replace("||", " ").replace("::", " ")
        "${profile.id}::$safeName::${profile.excluded.sorted().joinToString(",")}" 
    }

private fun loadProfilesV4(prefs: SharedPreferences): List<MenuPersonProfileV4> =
    prefs.getString("profiles_v4", "").orEmpty()
        .split("||")
        .mapNotNull { encoded ->
            if (encoded.isBlank()) return@mapNotNull null
            val parts = encoded.split("::", limit = 3)
            val id = parts.getOrNull(0).orEmpty()
            val name = parts.getOrNull(1).orEmpty()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val excluded = parts.getOrNull(2).orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()
            MenuPersonProfileV4(id, name, excluded)
        }

private fun encodeSlotProfilesV4(value: Map<Int, Set<String>>): String =
    value.entries
        .filter { it.value.isNotEmpty() }
        .sortedBy { it.key }
        .joinToString(";") { (slot, ids) ->
            "$slot=${ids.sorted().joinToString(",")}" 
        }

private fun loadSlotProfilesV4(prefs: SharedPreferences): Map<Int, Set<String>> =
    prefs.getString("slot_profiles_v4", "").orEmpty()
        .split(';')
        .mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            val slot = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val ids = parts.getOrNull(1).orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()
            if (slot !in 0 until SLOT_COUNT || ids.isEmpty()) null else slot to ids
        }.toMap()

private fun resolveRecipeForProfilesV4(
    recipe: MenuRecipeV3,
    globalExcluded: Set<String>,
    rules: List<IngredientRuleV3>,
    selectedProfiles: List<MenuPersonProfileV4>,
    totalPeople: Int
): ResolvedRecipeV3? {
    val base = resolveRecipeV3(recipe, globalExcluded, rules) ?: return null
    if (selectedProfiles.isEmpty() || totalPeople <= 0) return base

    var name = base.name
    val needs = mutableListOf<MenuNeedV3>()
    val substitutions = base.substitutions.toMutableList()

    base.needs.forEachIndexed { position, need ->
        val rule = rules.firstOrNull { candidate ->
            selectedProfiles.any { candidate.id in it.excluded } &&
                needMatchesRuleV3(need, candidate)
        }
        if (rule == null) {
            needs += need
            return@forEachIndexed
        }

        val affectedProfiles = selectedProfiles.filter { rule.id in it.excluded }
        val affectedCount = affectedProfiles.size.coerceAtMost(totalPeople)
        if (affectedCount <= 0) {
            needs += need
            return@forEachIndexed
        }

        val alternatives = rule.alternatives.filter { alt ->
            rules.none { other ->
                other.id in globalExcluded && needMatchesRuleV3(alt, other)
            } && affectedProfiles.none { profile ->
                rules.any { other ->
                    other.id in profile.excluded && needMatchesRuleV3(alt, other)
                }
            }
        }
        if (alternatives.isEmpty()) return null

        val alt = alternatives[
            stableIndexV3("${recipe.id}:${rule.id}:profiles:$position", alternatives.size)
        ]
        val affectedNames = affectedProfiles.joinToString(", ") { it.name }

        if (affectedCount >= totalPeople) {
            val replacement = alt.copy(unitsForTwo = need.unitsForTwo)
            needs += replacement
            name = name.replace(need.label, replacement.label, ignoreCase = true)
            substitutions += "${need.label} → ${replacement.label} ($affectedNames)"
        } else {
            val dislikedShare = affectedCount.toDouble() / totalPeople.toDouble()
            val likedShare = 1.0 - dislikedShare
            needs += need.copy(unitsForTwo = need.unitsForTwo * likedShare)
            needs += alt.copy(unitsForTwo = need.unitsForTwo * dislikedShare)
            substitutions += "${alt.label} pour $affectedNames"
        }
    }

    return ResolvedRecipeV3(
        source = base.source,
        name = name,
        needs = needs,
        substitutions = substitutions
    )
}

private fun defaultUnitsForTwoV3'''
if "private fun encodeProfilesV4" not in text:
    if helpers_anchor not in text:
        raise SystemExit("defaultUnitsForTwoV3 introuvable pour helpers profils")
    text = text.replace(helpers_anchor, helpers, 1)

planner.write_text(text, encoding="utf-8")

print("V4 Menus & courses appliquée :")
print("- destination dédiée dans le drawer")
print("- profils personnes avec incompatibilités individuelles")
print("- affectation des profils par créneau midi/soir")
print("- alternatives proportionnées seulement aux personnes concernées")
print("- cache des lignes Drive invalidé automatiquement quand le nombre de lignes change")
print("- générateur retiré des statistiques")
