package com.nicobudget.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.Normalizer
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

private const val MENU_PREF_V03 = "drive_menu_planner_v2"
private const val MENU_SLOT_COUNT_V03 = 14
private val menuEuroV03 = NumberFormat.getCurrencyInstance(Locale.FRANCE)
private fun Double.menuEurV03(): String = menuEuroV03.format(this)

private enum class MenuModeV03(val label: String) {
    HABITS("Habitudes"), VARIED("Varié"), ECONOMICAL("Économique"), QUICK("Rapide")
}

private data class MenuNeedV03(
    val id: String,
    val label: String,
    val keywords: List<String>,
    val unitsForTwo: Double,
    val ruleId: String? = null
)

private data class MenuRecipeV03(
    val id: String,
    val name: String,
    val primary: String,
    val quick: Boolean = false,
    val needs: List<MenuNeedV03>
)

private data class MenuProductV03(
    val key: String,
    val label: String,
    val section: String,
    val orders: Int,
    val quantity: Double,
    val lastDate: String,
    val recentUnitPrice: Double?
)

private data class MenuPersonV03(
    val id: String,
    val name: String,
    val excluded: Set<String>
)

private data class ResolvedNeedV03(
    val need: MenuNeedV03,
    val people: Int,
    val note: String? = null
)

private data class ResolvedMenuV03(
    val recipe: MenuRecipeV03,
    val needs: List<ResolvedNeedV03>,
    val notes: List<String>
)

private data class ShoppingItemV03(
    val key: String,
    val label: String,
    val section: String,
    val quantity: Int,
    val unitPrice: Double?,
    val generic: Boolean
) {
    val total: Double? get() = unitPrice?.times(quantity)
}

private data class MenuRuleV03(
    val id: String,
    val label: String,
    val alternatives: List<MenuNeedV03>
)

@Composable
internal fun MenusV03Screen(model: AppModel) {
    val orders = remember(model.revision) { menuRowsV03("drive_orders") }
    val lines = remember(model.revision) { menuRowsV03("drive_order_lines") }
    val recipes = remember { menuRecipesV03() }
    val rules = remember { menuRulesV03() }
    val catalog = remember(orders, lines) { buildMenuCatalogV03(orders, lines) }

    var plan by remember(model.revision) { mutableStateOf(loadPlanV03(recipes)) }
    var servings by remember(model.revision) { mutableStateOf(loadServingsV03()) }
    var locked by remember(model.revision) { mutableStateOf(loadIndexSetV03("locked_v3")) }
    var excluded by remember(model.revision) { mutableStateOf(DesktopStore.preferenceStringSet(MENU_PREF_V03, "excluded_v3")) }
    var profiles by remember(model.revision) { mutableStateOf(loadProfilesV03()) }
    var slotProfiles by remember(model.revision) { mutableStateOf(loadSlotProfilesV03()) }
    var mode by remember(model.revision) {
        mutableStateOf(
            DesktopStore.preferenceString(MENU_PREF_V03, "mode_v3")
                ?.let { runCatching { MenuModeV03.valueOf(it) }.getOrNull() }
                ?: MenuModeV03.VARIED
        )
    }
    var recipeSlot by remember { mutableStateOf<Int?>(null) }
    var participantsSlot by remember { mutableStateOf<Int?>(null) }
    var showExcluded by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    val recent = remember(model.revision) {
        DesktopStore.preferenceString(MENU_PREF_V03, "recent").orEmpty().split('|').filter(String::isNotBlank)
    }

    fun persist(message: String? = null) {
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "plan_v3", plan.joinToString("|"))
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "servings_v3", servings.joinToString(","))
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "locked_v3", locked.sorted().joinToString(","))
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "mode_v3", mode.name)
        DesktopEditor.setPreferenceStringSet(MENU_PREF_V03, "excluded_v3", excluded)
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "profiles_v4", encodeProfilesV03(profiles))
        DesktopEditor.setPreferenceString(MENU_PREF_V03, "slot_profiles_v4", encodeSlotProfilesV03(slotProfiles))
        if (message != null) model.refresh(message)
    }

    fun resolve(index: Int, recipe: MenuRecipeV03): ResolvedMenuV03 {
        val selected = profiles.filter { it.id in slotProfiles[index].orEmpty() }
        return resolveMenuV03(recipe, servings[index], excluded, selected, rules)
    }

    fun regenerateOne(index: Int, source: List<String> = plan): List<String> {
        if (servings[index] <= 0 || index in locked) return source
        val previous = (0 until index).mapNotNull { i -> source.getOrNull(i)?.let { id -> recipes.firstOrNull { it.id == id } } }
        val selected = profiles.filter { it.id in slotProfiles[index].orEmpty() }
        val candidate = chooseRecipeV03(
            recipes = recipes,
            catalog = catalog,
            mode = mode,
            people = servings[index],
            previous = previous,
            globalExcluded = excluded,
            selectedProfiles = selected,
            rules = rules,
            recent = recent
        )
        return source.toMutableList().also { it[index] = candidate.id }
    }

    fun regenerateAll() {
        var next = plan
        for (i in 0 until MENU_SLOT_COUNT_V03) next = regenerateOne(i, next)
        plan = next
        checked.clear()
        persist("Nouvelle semaine générée depuis l'historique Drive.")
    }

    fun setPeople(index: Int, value: Int) {
        servings = servings.toMutableList().also { it[index] = value.coerceIn(0, 8) }
        if (value <= 0) locked = locked - index
        checked.clear()
        persist()
    }

    val resolved = remember(plan, servings, excluded, profiles, slotProfiles) {
        (0 until MENU_SLOT_COUNT_V03).map { index ->
            if (servings[index] <= 0) null
            else recipes.firstOrNull { it.id == plan[index] }?.let { recipe ->
                val selected = profiles.filter { it.id in slotProfiles[index].orEmpty() }
                resolveMenuV03(recipe, servings[index], excluded, selected, rules)
            }
        }
    }
    val shopping = remember(resolved, catalog) { buildShoppingV03(resolved, catalog) }
    val knownShoppingTotal = shopping.mapNotNull { it.total }.sum()
    val formatter = remember { DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE) }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Menus & courses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "14 créneaux · historique Drive · prix observés · profils et substitutions individuelles.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { showExcluded = true }) { Text("Foyer (${excluded.size})") }
            Spacer(Modifier.width(7.dp))
            OutlinedButton(onClick = { showProfiles = true }) { Text("Profils (${profiles.size})") }
            Spacer(Modifier.width(7.dp))
            Button(onClick = { persist("Planning enregistré.") }) { Text("Enregistrer") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                items(MenuModeV03.entries) { item ->
                    FilterChip(selected = mode == item, onClick = { mode = item; persist() }, label = { Text(item.label) })
                }
            }
            Button(onClick = { regenerateAll() }, enabled = recipes.isNotEmpty()) { Text("Générer / régénérer") }
        }

        if (catalog.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Pas de catalogue Drive exploitable : les menus restent disponibles, mais les références et prix de la liste de courses seront génériques.",
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            LazyColumn(
                Modifier.weight(1.7f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(7) { day ->
                    val date = LocalDate.now().plusDays(day.toLong())
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(date.format(formatter).replaceFirstChar { it.titlecase(Locale.FRANCE) }, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                MenuSlotV03(
                                    index = day * 2,
                                    title = "Midi",
                                    people = servings[day * 2],
                                    locked = day * 2 in locked,
                                    resolved = resolved[day * 2],
                                    catalog = catalog,
                                    profiles = profiles,
                                    selectedProfileIds = slotProfiles[day * 2].orEmpty(),
                                    modifier = Modifier.weight(1f),
                                    onMinus = { setPeople(day * 2, servings[day * 2] - 1) },
                                    onPlus = { setPeople(day * 2, servings[day * 2] + 1) },
                                    onLock = { locked = if (day * 2 in locked) locked - day * 2 else locked + day * 2; persist() },
                                    onReroll = { plan = regenerateOne(day * 2); persist(); checked.clear() },
                                    onChoose = { recipeSlot = day * 2 },
                                    onProfiles = { participantsSlot = day * 2 }
                                )
                                MenuSlotV03(
                                    index = day * 2 + 1,
                                    title = "Soir",
                                    people = servings[day * 2 + 1],
                                    locked = day * 2 + 1 in locked,
                                    resolved = resolved[day * 2 + 1],
                                    catalog = catalog,
                                    profiles = profiles,
                                    selectedProfileIds = slotProfiles[day * 2 + 1].orEmpty(),
                                    modifier = Modifier.weight(1f),
                                    onMinus = { setPeople(day * 2 + 1, servings[day * 2 + 1] - 1) },
                                    onPlus = { setPeople(day * 2 + 1, servings[day * 2 + 1] + 1) },
                                    onLock = { locked = if (day * 2 + 1 in locked) locked - (day * 2 + 1) else locked + (day * 2 + 1); persist() },
                                    onReroll = { plan = regenerateOne(day * 2 + 1); persist(); checked.clear() },
                                    onChoose = { recipeSlot = day * 2 + 1 },
                                    onProfiles = { participantsSlot = day * 2 + 1 }
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            val active = plan.filterIndexed { index, _ -> servings[index] > 0 }
                            val old = DesktopStore.preferenceString(MENU_PREF_V03, "recent").orEmpty().split('|').filter(String::isNotBlank)
                            DesktopEditor.setPreferenceString(MENU_PREF_V03, "recent", (active + old).distinct().take(40).joinToString("|"))
                            model.refresh("Semaine validée : ces repas seront moins prioritaires lors de la prochaine génération.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Valider cette semaine") }
                }
            }

            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Liste de courses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${shopping.size} référence(s) · total connu ~${knownShoppingTotal.menuEurV03()}${if (shopping.any { it.unitPrice == null }) " + prix inconnus" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            val text = shopping.joinToString("\n") { item -> "☐ ${item.quantity} × ${item.label}${item.total?.let { " — ${it.menuEurV03()}" }.orEmpty()}" }
                            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
                                .onSuccess { model.refresh("Liste de courses copiée.") }
                                .onFailure { model.fail("Copie impossible : ${it.message}") }
                        }) { Text("Copier") }
                        TextButton(onClick = { checked.clear() }) { Text("Décocher tout") }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(shopping, key = { it.key }) { item ->
                            val isChecked = checked[item.key] == true
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(isChecked, { checked[item.key] = it })
                                Column(Modifier.weight(1f)) {
                                    Text("${item.quantity} × ${item.label}", fontWeight = if (isChecked) FontWeight.Normal else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (item.generic) "${item.section} · référence à choisir" else item.section,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(item.total?.menuEurV03() ?: "—", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    recipeSlot?.let { slot ->
        RecipePickerV03(recipes, { recipeSlot = null }) { recipe ->
            plan = plan.toMutableList().also { it[slot] = recipe.id }
            recipeSlot = null
            checked.clear()
            persist()
        }
    }

    participantsSlot?.let { slot ->
        ParticipantsV03(profiles, slotProfiles[slot].orEmpty(), { participantsSlot = null }) { selected ->
            slotProfiles = slotProfiles.toMutableMap().also { map -> if (selected.isEmpty()) map.remove(slot) else map[slot] = selected }
            participantsSlot = null
            checked.clear()
            persist()
        }
    }

    if (showExcluded) {
        RulesV03("Aliments à éviter pour tout le foyer", excluded, rules, { showExcluded = false }) {
            excluded = it; showExcluded = false; checked.clear(); persist()
        }
    }

    if (showProfiles) {
        ProfilesV03(profiles, rules, { showProfiles = false }) { updated ->
            profiles = updated
            val valid = profiles.map { it.id }.toSet()
            slotProfiles = slotProfiles.mapValues { it.value.intersect(valid) }.filterValues { it.isNotEmpty() }
            showProfiles = false
            checked.clear()
            persist()
        }
    }
}

@Composable
private fun MenuSlotV03(
    index: Int,
    title: String,
    people: Int,
    locked: Boolean,
    resolved: ResolvedMenuV03?,
    catalog: List<MenuProductV03>,
    profiles: List<MenuPersonV03>,
    selectedProfileIds: Set<String>,
    modifier: Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onLock: () -> Unit,
    onReroll: () -> Unit,
    onChoose: () -> Unit,
    onProfiles: () -> Unit
) {
    val estimate = resolved?.let { estimateMenuCostV03(it, catalog) }
    val names = profiles.filter { it.id in selectedProfileIds }.map { it.name }
    Surface(modifier, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onMinus) { Text("−") }
                Text(if (people <= 0) "aucun" else "$people pers", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onPlus) { Text("+") }
                TextButton(onClick = onLock) { Text(if (locked) "🔒" else "🔓") }
            }
            if (people <= 0) {
                Text("Pas de repas prévu", style = MaterialTheme.typography.bodySmall)
            } else if (resolved == null) {
                Text("Menu non défini")
                TextButton(onClick = onChoose) { Text("Choisir") }
            } else {
                Text(resolved.recipe.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${resolved.recipe.primary}${if (resolved.recipe.quick) " · rapide" else ""}${estimate?.let { " · part ~${it.menuEurV03()}" }.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                resolved.notes.forEach { Text("Adapté : $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                if (names.isNotEmpty()) Text("Présents : ${names.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    TextButton(onClick = onChoose) { Text("Choisir") }
                    TextButton(onClick = onReroll, enabled = !locked) { Text("↻") }
                    if (profiles.isNotEmpty()) TextButton(onClick = onProfiles) { Text("Profils") }
                }
            }
        }
    }
}

private fun menuRowsV03(table: String): List<DbRow> = if (DesktopStore.tableExists(table)) DesktopStore.rows(table) else emptyList()

private fun menuNormV03(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun lineDateV03(line: DbRow, ordersByPk: Map<Long, DbRow>, ordersByExt: Map<String, DbRow>): String {
    line.long("orderId", "order_id")?.let { ordersByPk[it]?.string("date")?.let { d -> return d } }
    line.string("orderId", "order_id")?.let { ordersByExt[it]?.string("date")?.let { d -> return d } }
    return ""
}

private fun buildMenuCatalogV03(orders: List<DbRow>, lines: List<DbRow>): List<MenuProductV03> {
    val byPk = orders.mapNotNull { row -> row.long("id")?.let { it to row } }.toMap()
    val byExt = orders.mapNotNull { row -> row.string("orderId")?.let { it to row } }.toMap()
    data class A(val label: String, val section: String, var qty: Double = 0.0, val refs: MutableSet<String> = linkedSetOf(), var last: String = "", val prices: MutableList<Pair<String, Double>> = mutableListOf())
    val agg = linkedMapOf<String, A>()
    lines.forEach { line ->
        val label = line.string("label", "productName", "product", "name")?.trim().orEmpty()
        if (label.isBlank()) return@forEach
        val key = menuNormV03(label)
        val a = agg.getOrPut(key) { A(label, line.string("section", "category", "rayon") ?: "Sans rayon") }
        a.qty += line.double("quantity", "qty") ?: 1.0
        val ref = line.string("orderId", "order_id").orEmpty()
        if (ref.isNotBlank()) a.refs += ref
        val date = lineDateV03(line, byPk, byExt)
        if (date > a.last) a.last = date
        val price = line.double("unitPrice", "unit_price", "price")
        if (price != null && price > 0 && date.isNotBlank()) a.prices += date to price
    }
    return agg.map { (key, a) ->
        val recent = a.prices.sortedByDescending { it.first }.take(3).map { it.second }.sorted()
        val median = when (recent.size) {
            0 -> null
            1 -> recent[0]
            2 -> (recent[0] + recent[1]) / 2.0
            else -> recent[1]
        }
        MenuProductV03(key, a.label, a.section, a.refs.size, a.qty, a.last, median)
    }
}

private fun productForNeedV03(need: MenuNeedV03, catalog: List<MenuProductV03>): MenuProductV03? {
    val keywords = need.keywords.map(::menuNormV03)
    return catalog.asSequence().map { product ->
        val n = menuNormV03(product.label)
        val match = keywords.maxOfOrNull { keyword ->
            when {
                n == keyword -> 100.0
                n.contains(keyword) -> 55.0 + keyword.length.coerceAtMost(25)
                keyword.contains(n) && n.length >= 5 -> 25.0
                else -> 0.0
            }
        } ?: 0.0
        product to (match + product.orders.coerceAtMost(80) * 0.7 + if (product.lastDate.isNotBlank()) 4.0 else 0.0)
    }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

private fun estimateRecipeV03(recipe: MenuRecipeV03, people: Int, catalog: List<MenuProductV03>): Double? {
    val prices = recipe.needs.map { need -> productForNeedV03(need, catalog)?.recentUnitPrice?.times(need.unitsForTwo * people / 2.0) }
    return if (prices.any { it == null }) null else prices.filterNotNull().sum()
}

private fun estimateMenuCostV03(menu: ResolvedMenuV03, catalog: List<MenuProductV03>): Double? {
    val prices = menu.needs.filter { it.people > 0 }.map { resolved ->
        productForNeedV03(resolved.need, catalog)?.recentUnitPrice?.times(resolved.need.unitsForTwo * resolved.people / 2.0)
    }
    return if (prices.any { it == null }) null else prices.filterNotNull().sum()
}

private fun chooseRecipeV03(
    recipes: List<MenuRecipeV03>,
    catalog: List<MenuProductV03>,
    mode: MenuModeV03,
    people: Int,
    previous: List<MenuRecipeV03>,
    globalExcluded: Set<String>,
    selectedProfiles: List<MenuPersonV03>,
    rules: List<MenuRuleV03>,
    recent: List<String>
): MenuRecipeV03 {
    val previousPrimary = previous.takeLast(4).map { it.primary }
    val previousIds = previous.takeLast(8).map { it.id }
    val scored = recipes.map { recipe ->
        val resolved = resolveMenuV03(recipe, people, globalExcluded, selectedProfiles, rules)
        val affinity = recipe.needs.sumOf { productForNeedV03(it, catalog)?.orders?.toDouble() ?: 0.0 }
        val cost = estimateMenuCostV03(resolved, catalog) ?: 12.0
        val repeat = previousIds.count { it == recipe.id } * 35.0 + previousPrimary.count { it == recipe.primary } * 9.0
        val recentPenalty = if (recipe.id in recent.take(20)) 12.0 else 0.0
        val modeScore = when (mode) {
            MenuModeV03.HABITS -> affinity * 1.5 - cost * 0.4
            MenuModeV03.VARIED -> affinity * 0.55 - cost * 0.25 - repeat * 1.25
            MenuModeV03.ECONOMICAL -> affinity * 0.2 - cost * 5.0 - repeat * 0.5
            MenuModeV03.QUICK -> affinity * 0.25 - cost * 0.5 + if (recipe.quick) 45.0 else 0.0 + if (people <= 2 && recipe.quick) 15.0 else 0.0
        }
        recipe to (modeScore - repeat - recentPenalty + Random.nextDouble(0.0, 6.0))
    }.sortedByDescending { it.second }
    return scored.take(3).randomOrNull()?.first ?: recipes.first()
}

private fun resolveMenuV03(
    recipe: MenuRecipeV03,
    people: Int,
    globalExcluded: Set<String>,
    selectedProfiles: List<MenuPersonV03>,
    rules: List<MenuRuleV03>
): ResolvedMenuV03 {
    val result = mutableListOf<ResolvedNeedV03>()
    val notes = mutableListOf<String>()
    recipe.needs.forEach { need ->
        val ruleId = need.ruleId
        val rule = ruleId?.let { id -> rules.firstOrNull { it.id == id } }
        if (ruleId != null && ruleId in globalExcluded && rule != null && rule.alternatives.isNotEmpty()) {
            val alt = rule.alternatives.first { candidate -> candidate.ruleId == null || candidate.ruleId !in globalExcluded }
            result += ResolvedNeedV03(alt, people, "${need.label} → ${alt.label} (foyer)")
            notes += "${need.label} → ${alt.label} pour tout le foyer"
            return@forEach
        }
        val affected = if (ruleId == null) emptyList() else selectedProfiles.filter { ruleId in it.excluded }
        if (affected.isEmpty() || rule == null || rule.alternatives.isEmpty()) {
            result += ResolvedNeedV03(need, people)
        } else {
            val basePeople = (people - affected.size).coerceAtLeast(0)
            if (basePeople > 0) result += ResolvedNeedV03(need, basePeople)
            val allExcluded = globalExcluded + affected.flatMap { it.excluded }
            val alt = rule.alternatives.firstOrNull { it.ruleId == null || it.ruleId !in allExcluded } ?: rule.alternatives.first()
            result += ResolvedNeedV03(alt, affected.size, "${need.label} → ${alt.label}")
            notes += "${need.label} → ${alt.label} pour ${affected.joinToString(", ") { it.name }}"
        }
    }
    return ResolvedMenuV03(recipe, result, notes.distinct())
}

private fun buildShoppingV03(resolved: List<ResolvedMenuV03?>, catalog: List<MenuProductV03>): List<ShoppingItemV03> {
    data class Acc(val product: MenuProductV03?, val need: MenuNeedV03, var raw: Double = 0.0)
    val acc = linkedMapOf<String, Acc>()
    resolved.filterNotNull().flatMap { it.needs }.filter { it.people > 0 }.forEach { rn ->
        val product = productForNeedV03(rn.need, catalog)
        val key = product?.key ?: "generic:${rn.need.id}"
        val item = acc.getOrPut(key) { Acc(product, rn.need) }
        item.raw += rn.need.unitsForTwo * rn.people / 2.0
    }
    return acc.values.map { a ->
        val quantity = ceil(a.raw.coerceAtLeast(0.01)).toInt()
        ShoppingItemV03(
            key = a.product?.key ?: "generic:${a.need.id}",
            label = a.product?.label ?: a.need.label,
            section = a.product?.section ?: "À choisir",
            quantity = quantity,
            unitPrice = a.product?.recentUnitPrice,
            generic = a.product == null
        )
    }.sortedWith(compareBy<ShoppingItemV03> { it.section }.thenBy { it.label })
}

@Composable
private fun RecipePickerV03(recipes: List<MenuRecipeV03>, onDismiss: () -> Unit, onPick: (MenuRecipeV03) -> Unit) {
    var search by remember { mutableStateOf("") }
    val visible = recipes.filter { search.isBlank() || it.name.contains(search, true) || it.primary.contains(search, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir un repas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(visible) { recipe ->
                        TextButton(onClick = { onPick(recipe) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(recipe.name); Text(recipe.primary, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun ParticipantsV03(profiles: List<MenuPersonV03>, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profils présents à ce repas") },
        text = { Column { profiles.forEach { p -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(p.id in selected, { checked -> selected = if (checked) selected + p.id else selected - p.id }); Text(p.name) } } } },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun RulesV03(title: String, initial: Set<String>, rules: List<MenuRuleV03>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) { rules.forEach { r -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(r.id in selected, { c -> selected = if (c) selected + r.id else selected - r.id }); Text(r.label) } } } },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun ProfilesV03(initial: List<MenuPersonV03>, rules: List<MenuRuleV03>, onDismiss: () -> Unit, onSave: (List<MenuPersonV03>) -> Unit) {
    var profiles by remember(initial) { mutableStateOf(initial) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profils personnes") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.forEachIndexed { index, p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.excluded.size} aliment(s) évité(s)", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { editIndex = index }) { Text("Modifier") }
                            TextButton(onClick = { profiles = profiles.toMutableList().also { it.removeAt(index) } }) { Text("Supprimer") }
                        }
                    }
                }
                OutlinedButton(onClick = { profiles = profiles + MenuPersonV03(UUID.randomUUID().toString(), "Personne ${profiles.size + 1}", emptySet()); editIndex = profiles.lastIndex }) { Text("+ Ajouter") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(profiles) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
    editIndex?.let { index -> profiles.getOrNull(index)?.let { p -> ProfileEditV03(p, rules, { editIndex = null }) { updated -> profiles = profiles.toMutableList().also { it[index] = updated }; editIndex = null } } }
}

@Composable
private fun ProfileEditV03(profile: MenuPersonV03, rules: List<MenuRuleV03>, onDismiss: () -> Unit, onSave: (MenuPersonV03) -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var excluded by remember(profile.id) { mutableStateOf(profile.excluded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le profil") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                rules.forEach { r -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(r.id in excluded, { c -> excluded = if (c) excluded + r.id else excluded - r.id }); Text(r.label) } }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(profile.copy(name = name.trim(), excluded = excluded)) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private fun loadPlanV03(recipes: List<MenuRecipeV03>): List<String> {
    val raw = DesktopStore.preferenceString(MENU_PREF_V03, "plan_v3").orEmpty().split('|')
    return if (raw.size == MENU_SLOT_COUNT_V03) raw else List(MENU_SLOT_COUNT_V03) { recipes[it % recipes.size].id }
}

private fun loadServingsV03(): List<Int> {
    val raw = DesktopStore.preferenceString(MENU_PREF_V03, "servings_v3").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    return if (raw.size == MENU_SLOT_COUNT_V03) raw.map { it.coerceIn(0, 8) } else List(MENU_SLOT_COUNT_V03) { if (it % 2 == 0) 1 else 2 }
}

private fun loadIndexSetV03(key: String): Set<Int> = DesktopStore.preferenceString(MENU_PREF_V03, key).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.filter { it in 0 until MENU_SLOT_COUNT_V03 }.toSet()

private fun loadProfilesV03(): List<MenuPersonV03> = DesktopStore.preferenceString(MENU_PREF_V03, "profiles_v4").orEmpty().split("||").mapNotNull { raw ->
    if (raw.isBlank()) return@mapNotNull null
    val p = raw.split("::", limit = 3)
    val id = p.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    MenuPersonV03(id, p.getOrNull(1)?.takeIf(String::isNotBlank) ?: "Personne", p.getOrNull(2).orEmpty().split(',').filter(String::isNotBlank).toSet())
}

private fun encodeProfilesV03(value: List<MenuPersonV03>): String = value.joinToString("||") { p -> "${p.id}::${p.name.replace("::", " ").replace("||", " ")}::${p.excluded.sorted().joinToString(",")}" }

private fun loadSlotProfilesV03(): Map<Int, Set<String>> = DesktopStore.preferenceString(MENU_PREF_V03, "slot_profiles_v4").orEmpty().split(';').mapNotNull { raw ->
    if ('=' !in raw) return@mapNotNull null
    val index = raw.substringBefore('=').toIntOrNull()?.takeIf { it in 0 until MENU_SLOT_COUNT_V03 } ?: return@mapNotNull null
    index to raw.substringAfter('=').split(',').filter(String::isNotBlank).toSet()
}.toMap()

private fun encodeSlotProfilesV03(value: Map<Int, Set<String>>): String = value.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.sorted().joinToString(",")}" }

private fun n(id: String, label: String, units: Double, rule: String? = null, vararg keywords: String) = MenuNeedV03(id, label, keywords.toList(), units, rule)

private fun menuRulesV03(): List<MenuRuleV03> = listOf(
    MenuRuleV03("courgettes", "Courgettes", listOf(n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"), n("carottes", "Carottes", 0.7, "carottes", "carotte"))),
    MenuRuleV03("brocoli", "Brocoli", listOf(n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"), n("carottes", "Carottes", 0.7, "carottes", "carotte"))),
    MenuRuleV03("haricots_verts", "Haricots verts", listOf(n("courgettes", "Courgettes", 0.7, "courgettes", "courgette"), n("carottes", "Carottes", 0.7, "carottes", "carotte"))),
    MenuRuleV03("carottes", "Carottes", listOf(n("courgettes", "Courgettes", 0.7, "courgettes", "courgette"), n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"))),
    MenuRuleV03("salade", "Salade", listOf(n("haricots", "Haricots verts", 0.55, "haricots_verts", "haricot vert"), n("carottes", "Carottes", 0.55, "carottes", "carotte"))),
    MenuRuleV03("tomate", "Tomate / sauce tomate", listOf(n("courgettes", "Courgettes", 0.55, "courgettes", "courgette"), n("carottes", "Carottes", 0.55, "carottes", "carotte"))),
    MenuRuleV03("aubergines", "Aubergines", listOf(n("courgettes", "Courgettes", 0.7, "courgettes", "courgette"))),
    MenuRuleV03("epinards", "Épinards", listOf(n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"))),
    MenuRuleV03("poireaux", "Poireaux", listOf(n("courgettes", "Courgettes", 0.7, "courgettes", "courgette"))),
    MenuRuleV03("petits_pois", "Petits pois", listOf(n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"))),
    MenuRuleV03("champignons", "Champignons", listOf(n("courgettes", "Courgettes", 0.6, "courgettes", "courgette"))),
    MenuRuleV03("oignons", "Oignons", listOf(n("carottes", "Carottes", 0.3, "carottes", "carotte"))),
    MenuRuleV03("concombre", "Concombre", listOf(n("salade", "Salade", 0.5, "salade", "laitue", "iceberg"))),
    MenuRuleV03("poivrons", "Poivrons", listOf(n("courgettes", "Courgettes", 0.6, "courgettes", "courgette"))),
    MenuRuleV03("chou_fleur", "Chou-fleur", listOf(n("brocoli", "Brocoli", 0.7, "brocoli", "broccoli"))),
    MenuRuleV03("chou", "Chou", listOf(n("haricots", "Haricots verts", 0.7, "haricots_verts", "haricot vert"))),
    MenuRuleV03("mais", "Maïs", listOf(n("carottes", "Carottes", 0.5, "carottes", "carotte"))),
    MenuRuleV03("lentilles", "Lentilles", listOf(n("riz", "Riz", 0.6, "riz", "riz long", "basmati"), n("pdt", "Pommes de terre", 0.8, "pommes_de_terre", "pomme de terre"))),
    MenuRuleV03("frites", "Frites", listOf(n("pdt", "Pommes de terre", 0.8, "pommes_de_terre", "pomme de terre"), n("riz", "Riz", 0.6, "riz", "riz long"))),
    MenuRuleV03("pommes_de_terre", "Pommes de terre", listOf(n("riz", "Riz", 0.6, "riz", "riz long"), n("pates", "Pâtes", 0.6, "pates", "spaghetti", "coquillette", "macaroni"))),
    MenuRuleV03("riz", "Riz", listOf(n("pates", "Pâtes", 0.6, "pates", "spaghetti", "coquillette"), n("pdt", "Pommes de terre", 0.8, "pommes_de_terre", "pomme de terre"))),
    MenuRuleV03("pates", "Pâtes", listOf(n("riz", "Riz", 0.6, "riz", "riz long"), n("pdt", "Pommes de terre", 0.8, "pommes_de_terre", "pomme de terre"))),
    MenuRuleV03("semoule", "Semoule / couscous", listOf(n("riz", "Riz", 0.6, "riz", "riz long"))),
    MenuRuleV03("boeuf", "Bœuf", listOf(n("poulet", "Poulet / dinde", 0.7, "poulet", "poulet", "dinde"), n("poisson", "Poisson", 0.7, "poisson", "colin", "saumon"))),
    MenuRuleV03("porc", "Porc / jambon / saucisses", listOf(n("poulet", "Poulet / dinde", 0.7, "poulet", "poulet", "dinde"), n("oeufs", "Œufs", 2.0, "oeufs", "oeuf"))),
    MenuRuleV03("poulet", "Poulet / dinde", listOf(n("boeuf", "Bœuf", 0.7, "boeuf", "steak", "boeuf"), n("poisson", "Poisson", 0.7, "poisson", "colin", "saumon"))),
    MenuRuleV03("poisson", "Poisson / fruits de mer", listOf(n("poulet", "Poulet / dinde", 0.7, "poulet", "poulet", "dinde"), n("oeufs", "Œufs", 2.0, "oeufs", "oeuf"))),
    MenuRuleV03("oeufs", "Œufs", listOf(n("poulet", "Poulet / dinde", 0.7, "poulet", "poulet", "dinde"))),
    MenuRuleV03("fromage", "Fromage", listOf(n("oeufs", "Œufs", 1.0, "oeufs", "oeuf")))
)

private fun menuRecipesV03(): List<MenuRecipeV03> = listOf(
    MenuRecipeV03("poulet_haricots_pdt", "Poulet, haricots verts & pommes de terre", "Poulet", needs = listOf(n("poulet", "Poulet", .7, "poulet", "poulet", "escalope poulet", "filet poulet"), n("haricots", "Haricots verts", .7, "haricots_verts", "haricot vert"), n("pdt", "Pommes de terre", .8, "pommes_de_terre", "pomme de terre"))),
    MenuRecipeV03("poulet_riz_courgettes", "Poulet, riz & courgettes", "Poulet", needs = listOf(n("poulet", "Poulet", .7, "poulet", "poulet"), n("riz", "Riz", .55, "riz", "riz", "basmati"), n("courgettes", "Courgettes", .7, "courgettes", "courgette"))),
    MenuRecipeV03("cordon_puree_carottes", "Cordon bleu, purée & carottes", "Poulet", quick = true, needs = listOf(n("cordon", "Cordon bleu", 1.0, "poulet", "cordon bleu"), n("puree", "Purée", .7, "pommes_de_terre", "puree", "pomme de terre"), n("carottes", "Carottes", .6, "carottes", "carotte"))),
    MenuRecipeV03("nuggets_frites_haricots", "Nuggets, frites & haricots verts", "Poulet", quick = true, needs = listOf(n("nuggets", "Nuggets", .8, "poulet", "nugget"), n("frites", "Frites", .8, "frites", "frite"), n("haricots", "Haricots verts", .6, "haricots_verts", "haricot vert"))),
    MenuRecipeV03("poulet_pates_brocoli", "Poulet, pâtes & brocoli", "Poulet", needs = listOf(n("poulet", "Poulet", .7, "poulet", "poulet"), n("pates", "Pâtes", .6, "pates", "pates", "spaghetti", "macaroni"), n("brocoli", "Brocoli", .6, "brocoli", "brocoli"))),
    MenuRecipeV03("steak_haricots_pdt", "Steak haché, haricots verts & pommes de terre", "Bœuf", needs = listOf(n("steak", "Steak haché", .7, "boeuf", "steak hache"), n("haricots", "Haricots verts", .6, "haricots_verts", "haricot vert"), n("pdt", "Pommes de terre", .8, "pommes_de_terre", "pomme de terre"))),
    MenuRecipeV03("burger_frites_salade", "Burgers maison, frites & salade", "Bœuf", needs = listOf(n("steak", "Steak haché", .7, "boeuf", "steak hache"), n("painburger", "Pains burger", 1.0, null, "pain burger", "bun"), n("frites", "Frites", .8, "frites", "frite"), n("salade", "Salade", .4, "salade", "salade", "laitue", "iceberg"))),
    MenuRecipeV03("boulettes_spaghetti", "Boulettes de bœuf & spaghetti", "Bœuf", needs = listOf(n("boulettes", "Boulettes de bœuf", .8, "boeuf", "boulette", "boeuf"), n("spaghetti", "Spaghetti", .6, "pates", "spaghetti"), n("tomate", "Sauce tomate", .5, "tomate", "sauce tomate", "tomate"))),
    MenuRecipeV03("bolognaise", "Spaghetti bolognaise", "Bœuf", needs = listOf(n("boeuf", "Bœuf haché", .7, "boeuf", "boeuf hache", "viande hachee"), n("spaghetti", "Spaghetti", .6, "pates", "spaghetti"), n("tomate", "Sauce tomate", .5, "tomate", "sauce tomate", "tomate"))),
    MenuRecipeV03("boeuf_riz_carottes", "Bœuf, riz & carottes", "Bœuf", needs = listOf(n("boeuf", "Bœuf", .7, "boeuf", "boeuf", "steak"), n("riz", "Riz", .55, "riz", "riz"), n("carottes", "Carottes", .6, "carottes", "carotte"))),
    MenuRecipeV03("saucisses_lentilles", "Saucisses & lentilles", "Porc", needs = listOf(n("saucisses", "Saucisses", .8, "porc", "saucisse"), n("lentilles", "Lentilles", .65, "lentilles", "lentille"))),
    MenuRecipeV03("chipolatas_frites_courgettes", "Chipolatas, frites & courgettes", "Porc", needs = listOf(n("chipolatas", "Chipolatas", .8, "porc", "chipolata"), n("frites", "Frites", .8, "frites", "frite"), n("courgettes", "Courgettes", .6, "courgettes", "courgette"))),
    MenuRecipeV03("jambon_coquillettes", "Jambon, coquillettes & fromage", "Porc", quick = true, needs = listOf(n("jambon", "Jambon", .7, "porc", "jambon"), n("coquillettes", "Coquillettes", .6, "pates", "coquillette"), n("fromage", "Fromage", .35, "fromage", "emmental", "fromage"))),
    MenuRecipeV03("porc_pdt_carottes", "Porc, pommes de terre & carottes", "Porc", needs = listOf(n("porc", "Porc", .7, "porc", "porc", "cote de porc"), n("pdt", "Pommes de terre", .8, "pommes_de_terre", "pomme de terre"), n("carottes", "Carottes", .6, "carottes", "carotte"))),
    MenuRecipeV03("croque_salade", "Croque-monsieur & salade", "Porc", quick = true, needs = listOf(n("jambon", "Jambon", .55, "porc", "jambon"), n("painmie", "Pain de mie", .6, null, "pain de mie"), n("fromage", "Fromage", .35, "fromage", "emmental", "fromage"), n("salade", "Salade", .4, "salade", "salade", "laitue", "iceberg"))),
    MenuRecipeV03("saumon_riz_brocoli", "Saumon, riz & brocoli", "Poisson", needs = listOf(n("saumon", "Saumon", .7, "poisson", "saumon"), n("riz", "Riz", .55, "riz", "riz"), n("brocoli", "Brocoli", .6, "brocoli", "brocoli"))),
    MenuRecipeV03("colin_puree_haricots", "Colin, purée & haricots verts", "Poisson", quick = true, needs = listOf(n("colin", "Colin", .7, "poisson", "colin"), n("puree", "Purée", .7, "pommes_de_terre", "puree", "pomme de terre"), n("haricots", "Haricots verts", .6, "haricots_verts", "haricot vert"))),
    MenuRecipeV03("thon_pates_tomate", "Pâtes au thon & tomate", "Poisson", quick = true, needs = listOf(n("thon", "Thon", .6, "poisson", "thon"), n("pates", "Pâtes", .6, "pates", "pates", "macaroni"), n("tomate", "Tomate", .5, "tomate", "tomate", "sauce tomate"))),
    MenuRecipeV03("poisson_frites_legumes", "Poisson, frites & légumes", "Poisson", quick = true, needs = listOf(n("poisson", "Poisson", .7, "poisson", "poisson", "colin"), n("frites", "Frites", .8, "frites", "frite"), n("haricots", "Légumes", .6, "haricots_verts", "haricot vert", "legume"))),
    MenuRecipeV03("surimi_riz_salade", "Salade de riz au surimi", "Poisson", quick = true, needs = listOf(n("surimi", "Surimi", .7, "poisson", "surimi"), n("riz", "Riz", .55, "riz", "riz"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("omelette_jambon_salade", "Omelette jambon-fromage & salade", "Œufs", quick = true, needs = listOf(n("oeufs", "Œufs", 2.0, "oeufs", "oeuf"), n("jambon", "Jambon", .45, "porc", "jambon"), n("fromage", "Fromage", .3, "fromage", "emmental", "fromage"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("oeufs_pdt_haricots", "Œufs, pommes de terre & haricots verts", "Œufs", quick = true, needs = listOf(n("oeufs", "Œufs", 2.0, "oeufs", "oeuf"), n("pdt", "Pommes de terre", .8, "pommes_de_terre", "pomme de terre"), n("haricots", "Haricots verts", .6, "haricots_verts", "haricot vert"))),
    MenuRecipeV03("omelette_legumes", "Omelette aux légumes", "Œufs", quick = true, needs = listOf(n("oeufs", "Œufs", 2.0, "oeufs", "oeuf"), n("courgettes", "Légumes", .7, "courgettes", "courgette", "legume"))),
    MenuRecipeV03("pizza_salade", "Pizza & salade", "Prêt", quick = true, needs = listOf(n("pizza", "Pizza", 1.0, null, "pizza"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("quiche_salade", "Quiche & salade", "Prêt", quick = true, needs = listOf(n("quiche", "Quiche", 1.0, null, "quiche"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("tarte_poireaux", "Tarte aux poireaux & salade", "Prêt", needs = listOf(n("tarte", "Tarte aux poireaux", 1.0, "poireaux", "tarte poireau", "poireau"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("lasagnes_salade", "Lasagnes & salade", "Prêt", quick = true, needs = listOf(n("lasagnes", "Lasagnes", 1.0, null, "lasagne"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("ravioli_legumes", "Ravioli & légumes", "Prêt", quick = true, needs = listOf(n("ravioli", "Ravioli", 1.0, null, "ravioli"), n("haricots", "Légumes", .6, "haricots_verts", "haricot vert", "legume"))),
    MenuRecipeV03("gratin_salade", "Gratin & salade", "Prêt", needs = listOf(n("gratin", "Gratin", 1.0, null, "gratin"), n("salade", "Salade", .4, "salade", "salade", "laitue"))),
    MenuRecipeV03("paella_salade", "Paella & salade", "Prêt", quick = true, needs = listOf(n("paella", "Paella", 1.0, null, "paella"), n("salade", "Salade", .35, "salade", "salade", "laitue"))),
    MenuRecipeV03("couscous_legumes", "Couscous & légumes", "Prêt", needs = listOf(n("couscous", "Couscous", 1.0, "semoule", "couscous", "semoule"), n("legumes", "Légumes", .5, null, "legume"))),
    MenuRecipeV03("gnocchi_tomate_fromage", "Gnocchi tomate-fromage", "Pâtes", quick = true, needs = listOf(n("gnocchi", "Gnocchi", .8, "pates", "gnocchi"), n("tomate", "Sauce tomate", .5, "tomate", "sauce tomate", "tomate"), n("fromage", "Fromage", .3, "fromage", "fromage", "emmental"))),
    MenuRecipeV03("pates_fromage_legumes", "Pâtes, fromage & légumes", "Pâtes", quick = true, needs = listOf(n("pates", "Pâtes", .6, "pates", "pates", "macaroni", "coquillette"), n("fromage", "Fromage", .3, "fromage", "fromage", "emmental"), n("haricots", "Légumes", .6, "haricots_verts", "haricot vert", "legume")))
)
