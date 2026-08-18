#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_budget_stats_and_food_rules.py <project_root>")

root = Path(sys.argv[1])
planner = root / "app/src/main/java/com/example/nicobudget/ui/DriveMenuPlanner.kt"
main = root / "app/src/main/java/com/example/nicobudget/MainActivity.kt"
stats_dst = root / "app/src/main/java/com/example/nicobudget/ui/BudgetStatsScreen.kt"

for path in (planner, main):
    if not path.exists():
        raise SystemExit(f"Fichier introuvable: {path}")

# ---------------------------------------------------------------------------
# 1) Incompatibilités alimentaires étendues.
# ---------------------------------------------------------------------------
text = planner.read_text(encoding="utf-8")
start = text.find("private fun ingredientRulesV3(): List<IngredientRuleV3> {")
end = text.find("\nprivate fun resolveRecipeV3(", start)
if start < 0 or end < 0:
    raise SystemExit("Bloc ingredientRulesV3 introuvable")

rules = r'''private fun ingredientRulesV3(): List<IngredientRuleV3> {
    fun a(label: String, family: DriveFoodFamily, vararg keywords: String) =
        MenuNeedV3(label, family, keywords.toList(), defaultUnitsForTwoV3(family))

    return listOf(
        IngredientRuleV3("courgettes", "Courgettes", listOf("courgette"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade")
        )),
        IngredientRuleV3("brocoli", "Brocoli", listOf("brocoli"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Chou-fleur", DriveFoodFamily.VEGETABLES, "chou fleur")
        )),
        IngredientRuleV3("haricots", "Haricots verts", listOf("haricot vert"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Petits pois", DriveFoodFamily.VEGETABLES, "petit pois")
        )),
        IngredientRuleV3("carottes", "Carottes", listOf("carotte"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Petits pois", DriveFoodFamily.VEGETABLES, "petit pois")
        )),
        IngredientRuleV3("salade", "Salade / laitue", listOf("salade", "laitue"), listOf(
            a("Concombre", DriveFoodFamily.VEGETABLES, "concombre"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert")
        )),
        IngredientRuleV3("tomate", "Tomate / sauce tomate", listOf("tomate"), listOf(
            a("Poivrons", DriveFoodFamily.VEGETABLES, "poivron"),
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte")
        )),
        IngredientRuleV3("aubergine", "Aubergines", listOf("aubergine"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Poivrons", DriveFoodFamily.VEGETABLES, "poivron"),
            a("Champignons", DriveFoodFamily.VEGETABLES, "champignon")
        )),
        IngredientRuleV3("epinards", "Épinards", listOf("epinard"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"),
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette")
        )),
        IngredientRuleV3("poireaux", "Poireaux", listOf("poireau"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Oignons", DriveFoodFamily.VEGETABLES, "oignon"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte")
        )),
        IngredientRuleV3("petits_pois", "Petits pois", listOf("petit pois"), listOf(
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Maïs", DriveFoodFamily.VEGETABLES, "mais")
        )),
        IngredientRuleV3("champignons", "Champignons", listOf("champignon"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Poivrons", DriveFoodFamily.VEGETABLES, "poivron"),
            a("Épinards", DriveFoodFamily.VEGETABLES, "epinard")
        )),
        IngredientRuleV3("oignons", "Oignons", listOf("oignon"), listOf(
            a("Poireaux", DriveFoodFamily.VEGETABLES, "poireau"),
            a("Poivrons", DriveFoodFamily.VEGETABLES, "poivron"),
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette")
        )),
        IngredientRuleV3("concombre", "Concombre", listOf("concombre"), listOf(
            a("Salade", DriveFoodFamily.VEGETABLES, "laitue", "salade"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Tomates", DriveFoodFamily.VEGETABLES, "tomate")
        )),
        IngredientRuleV3("poivrons", "Poivrons", listOf("poivron"), listOf(
            a("Courgettes", DriveFoodFamily.VEGETABLES, "courgette"),
            a("Tomates", DriveFoodFamily.VEGETABLES, "tomate"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte")
        )),
        IngredientRuleV3("chou_fleur", "Chou-fleur", listOf("chou fleur"), listOf(
            a("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte")
        )),
        IngredientRuleV3("chou", "Chou", listOf("chou "), listOf(
            a("Brocoli", DriveFoodFamily.VEGETABLES, "brocoli"),
            a("Poireaux", DriveFoodFamily.VEGETABLES, "poireau"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert")
        )),
        IngredientRuleV3("mais", "Maïs", listOf("mais"), listOf(
            a("Petits pois", DriveFoodFamily.VEGETABLES, "petit pois"),
            a("Carottes", DriveFoodFamily.VEGETABLES, "carotte"),
            a("Haricots verts", DriveFoodFamily.VEGETABLES, "haricot vert")
        )),
        IngredientRuleV3("lentilles", "Lentilles", listOf("lentille"), listOf(
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"),
            a("Semoule", DriveFoodFamily.STARCHES, "semoule", "couscous")
        )),
        IngredientRuleV3("frites", "Frites", listOf("frites"), listOf(
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"),
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni")
        )),
        IngredientRuleV3("pommes_terre", "Pommes de terre", listOf("pomme de terre", "pommes de terre"), listOf(
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni"),
            a("Semoule", DriveFoodFamily.STARCHES, "semoule", "couscous")
        )),
        IngredientRuleV3("riz", "Riz", listOf("riz"), listOf(
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"),
            a("Semoule", DriveFoodFamily.STARCHES, "semoule", "couscous")
        )),
        IngredientRuleV3("pates", "Pâtes", listOf("pates", "spaghetti", "macaroni"), listOf(
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre"),
            a("Semoule", DriveFoodFamily.STARCHES, "semoule", "couscous")
        )),
        IngredientRuleV3("semoule", "Semoule / couscous", listOf("semoule", "couscous"), listOf(
            a("Riz", DriveFoodFamily.STARCHES, "riz"),
            a("Pâtes", DriveFoodFamily.STARCHES, "pates", "spaghetti", "macaroni"),
            a("Pommes de terre", DriveFoodFamily.POTATOES, "pomme de terre", "pommes de terre")
        )),
        IngredientRuleV3("boeuf", "Bœuf / steak", listOf("boeuf", "steak"), listOf(
            a("Poulet / dinde", DriveFoodFamily.POULTRY, "poulet", "dinde"),
            a("Porc / jambon", DriveFoodFamily.PORK, "porc", "jambon"),
            a("Poisson", DriveFoodFamily.FISH, "poisson", "colin", "saumon")
        )),
        IngredientRuleV3("porc", "Porc / jambon / saucisses", listOf("porc", "jambon", "saucisse", "chipolata"), listOf(
            a("Poulet / dinde", DriveFoodFamily.POULTRY, "poulet", "dinde"),
            a("Bœuf", DriveFoodFamily.BEEF, "boeuf", "steak"),
            a("Poisson", DriveFoodFamily.FISH, "poisson", "colin", "saumon")
        )),
        IngredientRuleV3("volaille", "Poulet / dinde", listOf("poulet", "dinde", "volaille"), listOf(
            a("Bœuf", DriveFoodFamily.BEEF, "boeuf", "steak"),
            a("Porc / jambon", DriveFoodFamily.PORK, "porc", "jambon"),
            a("Poisson", DriveFoodFamily.FISH, "poisson", "colin", "saumon")
        )),
        IngredientRuleV3("poisson", "Poisson / produits de la mer", listOf("poisson", "colin", "saumon", "thon", "cabillaud", "crevette"), listOf(
            a("Poulet / dinde", DriveFoodFamily.POULTRY, "poulet", "dinde"),
            a("Bœuf", DriveFoodFamily.BEEF, "boeuf", "steak"),
            a("Œufs", DriveFoodFamily.EGGS, "oeuf")
        )),
        IngredientRuleV3("oeufs", "Œufs", listOf("oeuf", "oeufs"), listOf(
            a("Poulet / dinde", DriveFoodFamily.POULTRY, "poulet", "dinde"),
            a("Fromage", DriveFoodFamily.DAIRY_CHEESE, "fromage", "emmental"),
            a("Poisson", DriveFoodFamily.FISH, "poisson", "colin")
        )),
        IngredientRuleV3("fromage", "Fromage", listOf("fromage", "emmental", "mozzarella", "camembert"), listOf(
            a("Œufs", DriveFoodFamily.EGGS, "oeuf"),
            a("Poulet / dinde", DriveFoodFamily.POULTRY, "poulet", "dinde")
        ))
    )
}
'''
text = text[:start] + rules + text[end:]
planner.write_text(text, encoding="utf-8")

budget_stats = r'''package com.example.nicobudget.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.db.AppDatabase
import com.example.nicobudget.ui.components.SectionCard
import com.example.nicobudget.ui.components.eur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private enum class BudgetStatsScope(val label: String) { CYCLE("Cycle"), YEAR("Année"), MONTHS_12("12 mois"), ALL("Tout") }
private data class BudgetExpensePoint(val date: LocalDate, val category: String, val amount: Double)
private data class BudgetCategoryStat(val category: String, val total: Double, val count: Int, val previousComparable: Double = 0.0)
private data class BudgetStatsSnapshot(val expenses: List<BudgetExpensePoint>, val currentStart: LocalDate?, val currentEnd: LocalDate?, val monthlyIncome: Double, val disposableLeftover: Double, val fixedCharges: List<Pair<String, Double>>)

@Composable
fun BudgetStatsScreen() {
    val context = LocalContext.current.applicationContext
    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<BudgetStatsSnapshot?>(null) }
    var scope by remember { mutableStateOf(BudgetStatsScope.CYCLE) }
    LaunchedEffect(Unit) { try { data = loadBudgetStatsSnapshot(context) } finally { loading = false } }
    val today = remember { LocalDate.now() }
    val snapshot = data
    val visible = remember(snapshot, scope, today) { if (snapshot == null) emptyList() else filterBudgetExpenses(snapshot, scope, today) }
    val previousComparable = remember(snapshot, scope, today) { if (snapshot == null || scope != BudgetStatsScope.CYCLE) emptyList() else previousCycleComparable(snapshot, today) }
    val categories = remember(visible, previousComparable) {
        val previousByCategory = previousComparable.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
        visible.groupBy { it.category }.map { (category, rows) -> BudgetCategoryStat(category, rows.sumOf { it.amount }, rows.size, previousByCategory[category] ?: 0.0) }.sortedByDescending { it.total }
    }
    val total = remember(visible) { visible.sumOf { it.amount } }
    val count = visible.size
    val maxCategory = categories.maxOfOrNull { it.total } ?: 0.0
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Stats budget", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Toutes les dépenses de NicoBudget, y compris les catégories hors Drive.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(BudgetStatsScope.entries) { item -> FilterChip(selected = scope == item, onClick = { scope = item }, label = { Text(if (item == BudgetStatsScope.YEAR) YearMonth.now().year.toString() else item.label) }) } }
        if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        if (snapshot == null) { Text("Impossible de charger les statistiques."); return@Column }
        if (scope == BudgetStatsScope.CYCLE) CycleProjectionCard(snapshot, visible, previousComparable, today)
        SectionCard(Icons.Default.BarChart, "Dépenses variables") {
            StatBudgetLine("Total", total.eur(), true); StatBudgetLine("Opérations", count.toString()); StatBudgetLine("Dépense moyenne", if (count > 0) (total / count).eur() else 0.0.eur()); StatBudgetLine("Catégories utilisées", categories.size.toString())
        }
        SectionCard(Icons.Default.BarChart, "Par catégorie") {
            if (categories.isEmpty()) Text("Aucune dépense sur cette période.") else categories.forEach { item ->
                val percent = if (total > 0.0) item.total * 100.0 / total else 0.0
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item.category, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Text(item.total.eur(), fontWeight = FontWeight.Bold) }
                    Text("%.1f %% · %d opération(s)".format(percent, item.count) + if (scope == BudgetStatsScope.CYCLE && item.previousComparable > 0.0) { val delta = (item.total - item.previousComparable) / item.previousComparable * 100.0; " · %+.0f %% vs cycle précédent au même stade".format(delta) } else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(3.dp)); LinearProgressIndicator(progress = { if (maxCategory > 0.0) (item.total / maxCategory).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (scope == BudgetStatsScope.CYCLE && snapshot.fixedCharges.isNotEmpty()) {
            val fixedTotal = snapshot.fixedCharges.sumOf { it.second }
            SectionCard(Icons.Default.BarChart, "Charges fixes actuelles") { snapshot.fixedCharges.sortedByDescending { it.second }.forEach { (name, amount) -> StatBudgetLine(name, amount.eur()) }; HorizontalDivider(); StatBudgetLine("Total charges fixes", fixedTotal.eur(), true); Text("Les charges fixes sont affichées séparément : NicoBudget ne conserve pas encore leur historique par cycle.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun CycleProjectionCard(snapshot: BudgetStatsSnapshot, currentExpenses: List<BudgetExpensePoint>, previousComparable: List<BudgetExpensePoint>, today: LocalDate) {
    val start = snapshot.currentStart ?: return; val end = snapshot.currentEnd ?: return; val spent = currentExpenses.sumOf { it.amount }; val previousSpent = previousComparable.sumOf { it.amount }
    val effectiveToday = when { today < start -> start; today >= end -> end.minusDays(1); else -> today }
    val elapsed = (ChronoUnit.DAYS.between(start, effectiveToday) + 1).coerceAtLeast(1); val totalDays = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1); val projected = spent / elapsed.toDouble() * totalDays.toDouble(); val initialVariableBudget = snapshot.disposableLeftover + spent; val projectedLeft = initialVariableBudget - projected; val previousDelta = if (previousSpent > 0.0) (spent - previousSpent) / previousSpent * 100.0 else null
    SectionCard(Icons.Default.BarChart, "Projection du cycle") { Text("${start} → ${end} · jour $elapsed / $totalDays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); StatBudgetLine("Dépenses variables à date", spent.eur(), true); StatBudgetLine("Budget variable initial", initialVariableBudget.eur()); StatBudgetLine("Reste variable actuel", snapshot.disposableLeftover.eur()); StatBudgetLine("Projection au même rythme", projected.eur()); StatBudgetLine("Marge projetée fin de cycle", projectedLeft.eur(), true); previousDelta?.let { Text("%+.0f %% de dépenses variables vs cycle précédent au même stade.".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (projectedLeft < 0.0) Text("Au rythme actuel, les dépenses variables dépasseraient le budget disponible d'environ ${abs(projectedLeft).eur()}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun StatBudgetLine(label: String, value: String, strong: Boolean = false) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Text(value, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal) } }
private fun filterBudgetExpenses(snapshot: BudgetStatsSnapshot, scope: BudgetStatsScope, today: LocalDate): List<BudgetExpensePoint> { val from = when (scope) { BudgetStatsScope.CYCLE -> snapshot.currentStart; BudgetStatsScope.YEAR -> LocalDate.of(today.year, 1, 1); BudgetStatsScope.MONTHS_12 -> today.minusMonths(12); BudgetStatsScope.ALL -> null }; val until = if (scope == BudgetStatsScope.CYCLE) snapshot.currentEnd else null; return snapshot.expenses.filter { (from == null || !it.date.isBefore(from)) && (until == null || it.date.isBefore(until)) } }
private fun previousCycleComparable(snapshot: BudgetStatsSnapshot, today: LocalDate): List<BudgetExpensePoint> { val start = snapshot.currentStart ?: return emptyList(); val end = snapshot.currentEnd ?: return emptyList(); val effective = when { today < start -> start; today >= end -> end.minusDays(1); else -> today }; val elapsed = ChronoUnit.DAYS.between(start, effective) + 1; val previousStart = start.minusMonths(1); val previousEnd = previousStart.plusDays(elapsed); return snapshot.expenses.filter { !it.date.isBefore(previousStart) && it.date.isBefore(previousEnd) } }
private suspend fun loadBudgetStatsSnapshot(context: Context): BudgetStatsSnapshot = withContext(Dispatchers.IO) {
    val db = AppDatabase.getDatabase(context); val sql = db.openHelper.readableDatabase; val expenses = mutableListOf<BudgetExpensePoint>()
    fun readExpenses(query: String) { sql.query(query).use { cursor -> val di = cursor.getColumnIndex("date"); val ci = cursor.getColumnIndex("category"); val ai = cursor.getColumnIndex("amount"); while (cursor.moveToNext()) { val date = runCatching { LocalDate.parse(cursor.getString(di)) }.getOrNull() ?: continue; val category = cursor.getString(ci)?.trim()?.ifBlank { "Sans catégorie" } ?: "Sans catégorie"; val amount = cursor.getDouble(ai); if (amount > 0.0) expenses += BudgetExpensePoint(date, category, amount) } } }
    readExpenses("SELECT date, category, amount FROM expenses"); runCatching { readExpenses("SELECT date, category, amount FROM expense_archive") }
    var start: LocalDate? = null; var end: LocalDate? = null; var income = 0.0; var leftover = 0.0
    sql.query("SELECT startDate, endDate, monthlyIncome, disposableLeftover FROM monthly_budget LIMIT 1").use { c -> if (c.moveToFirst()) { start = runCatching { LocalDate.parse(c.getString(0)) }.getOrNull(); end = runCatching { LocalDate.parse(c.getString(1)) }.getOrNull(); income = c.getDouble(2); leftover = c.getDouble(3) } }
    val fixed = mutableListOf<Pair<String, Double>>(); sql.query("SELECT name, amount FROM fixed_charges ORDER BY amount DESC").use { c -> while (c.moveToNext()) { val name = c.getString(0)?.ifBlank { "Charge fixe" } ?: "Charge fixe"; val amount = c.getDouble(1); if (amount > 0.0) fixed += name to amount } }
    BudgetStatsSnapshot(expenses.sortedByDescending { it.date }, start, end, income, leftover, fixed)
}
'''
stats_dst.write_text(budget_stats, encoding="utf-8")

text = main.read_text(encoding="utf-8")
if 'navController.navigate("budgetstats")' not in text:
    drawer_anchor = '''                            DrawerItem(Icons.Default.BarChart, "Stats Drive") {
                                navController.navigate("drivestats") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
'''
    drawer_new = '''                            DrawerItem(Icons.Default.BarChart, "Stats budget") {
                                navController.navigate("budgetstats") { launchSingleTop = true }
                                scope.launch { drawerState.close() }
                            }
''' + drawer_anchor
    if drawer_anchor not in text: raise SystemExit("Entrée Stats Drive du drawer introuvable")
    text = text.replace(drawer_anchor, drawer_new, 1)
if 'composable("budgetstats")' not in text:
    nav_anchor = '''                                composable("drivestats") { DriveStatsScreen(viewModel) }
'''
    nav_new = '''                                composable("budgetstats") { BudgetStatsScreen() }
''' + nav_anchor
    if nav_anchor not in text: raise SystemExit("Route drivestats introuvable")
    text = text.replace(nav_anchor, nav_new, 1)
main.write_text(text, encoding="utf-8")
print("Stats budget + règles alimentaires étendues appliquées")
