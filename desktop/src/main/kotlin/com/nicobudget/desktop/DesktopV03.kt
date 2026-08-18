package com.nicobudget.desktop

import androidx.compose.foundation.clickable
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
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.abs

private val v03Euro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
private fun Double.v03eur(): String = v03Euro.format(this)
private fun String?.v03date(): LocalDate? = runCatching { this?.let(LocalDate::parse) }.getOrNull()
private fun DesktopStore.v03rows(table: String): List<DbRow> = if (tableExists(table)) rows(table) else emptyList()
private fun DbRow.v03desc(): String = string("description", "label", "name", "note", "title").orEmpty()
private fun DbRow.v03search(): String = values.values.joinToString(" ") { it?.toString().orEmpty() }

private fun v03Normalize(value: String): String {
    val noAccent = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return noAccent.replace(Regex("[^a-z0-9]+"), " ").trim()
}

private data class V03Product(
    val key: String,
    val label: String,
    val section: String,
    val quantity: Double,
    val total: Double,
    val orders: Int,
    val lastDate: String,
    val unitPrices: List<Pair<String, Double>>
)

private enum class V03ProductSort(val label: String) {
    SPEND("Dépense"), QUANTITY("Quantité"), FREQUENCY("Fréquence"), AZ("A-Z")
}

@Composable
internal fun DashboardV03Screen(model: AppModel) {
    val budget = remember(model.revision) { DesktopStore.v03rows("monthly_budget").firstOrNull() }
    val expenses = remember(model.revision) { DesktopStore.v03rows("expenses") }
    val archives = remember(model.revision) { DesktopStore.v03rows("expense_archive") }
    val fixed = remember(model.revision) { DesktopStore.v03rows("fixed_charges") }
    val drive = remember(model.revision) { DesktopStore.v03rows("drive_orders") }
    val start = budget?.string("startDate").v03date()
    val end = budget?.string("endDate").v03date()
    val currentExpenses = expenses.filter { row ->
        val d = row.string("date").v03date() ?: return@filter false
        (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
    }
    val currentDrive = drive.filter { row ->
        val d = row.string("date").v03date() ?: return@filter false
        (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
    }
    val spent = currentExpenses.sumOf { it.double("amount") ?: 0.0 }
    val fixedTotal = fixed.sumOf { it.double("amount") ?: 0.0 }
    val income = budget?.double("monthlyIncome") ?: 0.0
    val left = budget?.double("disposableLeftover") ?: (income - fixedTotal - spent)
    val allExpenses = (expenses + archives).sortedByDescending { it.string("date") }
    val monthTotals = allExpenses.mapNotNull { row ->
        val d = row.string("date").v03date() ?: return@mapNotNull null
        YearMonth.from(d) to (row.double("amount") ?: 0.0)
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedBy { it.first }.takeLast(6)
    val maxMonth = monthTotals.maxOfOrNull { it.second } ?: 0.0

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        V03Title("Tableau de bord", "Pilotage rapide du cycle courant et accès direct aux actions.")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V03Stat("Reste du cycle", left.v03eur(), if (start != null && end != null) "$start → $end" else "cycle courant", Modifier.weight(1f))
            V03Stat("Dépenses", spent.v03eur(), "${currentExpenses.size} opération(s)", Modifier.weight(1f))
            V03Stat("Charges fixes", fixedTotal.v03eur(), if (income > 0) "%.1f %% du revenu".format(fixedTotal * 100 / income) else "—", Modifier.weight(1f))
            V03Stat("Drive", currentDrive.sumOf { it.double("total") ?: 0.0 }.v03eur(), "${currentDrive.size} commande(s)", Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Actions rapides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.section = Section.EXPENSES }) { Text("Ajouter / corriger une dépense") }
                    OutlinedButton(onClick = { model.section = Section.BUDGET }) { Text("Budget & récurrents") }
                    OutlinedButton(onClick = { model.section = Section.STATS }) { Text("Ouvrir les analyses") }
                    OutlinedButton(onClick = { model.section = Section.DRIVE }) { Text("Commandes Drive") }
                    OutlinedButton(onClick = { model.section = Section.MENUS }) { Text("Menus & courses") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Évolution des dépenses — 6 derniers mois", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (monthTotals.isEmpty()) Text("Pas encore assez d'historique.")
                monthTotals.forEach { (month, total) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(month.toString())
                        Text(total.v03eur(), fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { if (maxMonth > 0) (total / maxMonth).toFloat().coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dernières dépenses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    allExpenses.take(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(row.string("date"), row.v03desc().takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            }
                            Text((row.double("amount") ?: 0.0).v03eur())
                        }
                    }
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dernières commandes Drive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    drive.sortedByDescending { it.string("date") }.take(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Commande ${row.string("orderId") ?: "—"}", fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(row.string("date"), row.string("store")).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            }
                            Text((row.double("total") ?: 0.0).v03eur())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnalyticsV03Screen(model: AppModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V03Title("Statistiques & analyses", "Budget, Drive et produits dans un même tableau analytique.")
        TabRow(selectedTabIndex = tab) {
            listOf("Budget", "Leclerc Drive", "Produits").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> BudgetAnalyticsV03(model.revision, Modifier.weight(1f))
            1 -> DriveAnalyticsV03(model.revision, productsOnly = false, Modifier.weight(1f))
            else -> DriveAnalyticsV03(model.revision, productsOnly = true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PeriodPickerV03(
    scope: String,
    months: List<String>,
    onScope: (String) -> Unit
) {
    val years = months.map { it.take(4) }.filter { it.length == 4 }.distinct().sortedDescending()
    val activeYear = when {
        scope.length >= 4 && scope.take(4).all(Char::isDigit) -> scope.take(4)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item { FilterChip(scope == "CYCLE", { onScope("CYCLE") }, label = { Text("Cycle") }) }
            item { FilterChip(scope == "12M", { onScope("12M") }, label = { Text("12 mois") }) }
            item { FilterChip(scope == "ALL", { onScope("ALL") }, label = { Text("Tout") }) }
            items(years) { y -> FilterChip(scope == y || scope.startsWith("$y-"), { onScope(y) }, label = { Text(y) }) }
        }
        activeYear?.let { year ->
            val yearMonths = months.filter { it.startsWith("$year-") }.sortedDescending()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(scope == year, { onScope(year) }, label = { Text("Année entière") }) }
                items(yearMonths) { month ->
                    FilterChip(scope == month, { onScope(month) }, label = { Text(month.substringAfter('-')) })
                }
            }
        }
    }
}

private fun rowsForScopeV03(rows: List<DbRow>, scope: String, start: LocalDate?, end: LocalDate?, dateField: String = "date"): List<DbRow> {
    val today = LocalDate.now()
    return rows.filter { row ->
        val d = row.string(dateField).v03date() ?: return@filter false
        when {
            scope == "ALL" -> true
            scope == "12M" -> !d.isBefore(today.minusMonths(12))
            scope == "CYCLE" -> (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
            scope.length == 4 -> d.year.toString() == scope
            scope.length == 7 -> YearMonth.from(d).toString() == scope
            else -> true
        }
    }
}

@Composable
private fun BudgetAnalyticsV03(revision: Int, modifier: Modifier = Modifier) {
    val budget = remember(revision) { DesktopStore.v03rows("monthly_budget").firstOrNull() }
    val source = remember(revision) { DesktopStore.v03rows("expenses") + DesktopStore.v03rows("expense_archive") }
    val fixed = remember(revision) { DesktopStore.v03rows("fixed_charges") }
    val start = budget?.string("startDate").v03date()
    val end = budget?.string("endDate").v03date()
    val months = remember(source) { source.mapNotNull { it.string("date").v03date()?.let(YearMonth::from)?.toString() }.distinct().sortedDescending() }
    var scope by remember { mutableStateOf("CYCLE") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val visible = remember(source, scope, start, end) { rowsForScopeV03(source, scope, start, end) }
    val total = visible.sumOf { it.double("amount") ?: 0.0 }
    val categories = visible.groupBy { it.string("category") ?: "Sans catégorie" }
        .mapValues { (_, rows) -> rows.sumOf { it.double("amount") ?: 0.0 } }
        .toList().sortedByDescending { it.second }
    val monthTotals = visible.mapNotNull { row ->
        val ym = row.string("date").v03date()?.let(YearMonth::from) ?: return@mapNotNull null
        ym to (row.double("amount") ?: 0.0)
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedBy { it.first }
    val uniqueMonths = monthTotals.size.coerceAtLeast(1)
    val maxCategory = categories.maxOfOrNull { it.second } ?: 0.0
    val maxMonth = monthTotals.maxOfOrNull { it.second } ?: 0.0
    val biggest = visible.sortedByDescending { it.double("amount") ?: 0.0 }.take(10)
    val currentYear = LocalDate.now().year
    val currentYearTotal = source.filter { it.string("date").v03date()?.year == currentYear }.sumOf { it.double("amount") ?: 0.0 }
    val previousYearTotal = source.filter { it.string("date").v03date()?.year == currentYear - 1 }.sumOf { it.double("amount") ?: 0.0 }
    val yearDelta = if (previousYearTotal > 0) (currentYearTotal / previousYearTotal - 1.0) * 100.0 else null

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodPickerV03(scope, months) { scope = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V03Stat("Total", total.v03eur(), "${visible.size} opération(s)", Modifier.weight(1f))
            V03Stat("Moyenne / mois", (total / uniqueMonths).v03eur(), "$uniqueMonths mois avec dépenses", Modifier.weight(1f))
            V03Stat("Moyenne / opération", if (visible.isEmpty()) 0.0.v03eur() else (total / visible.size).v03eur(), "panier moyen budget", Modifier.weight(1f))
            V03Stat("Charges fixes", fixed.sumOf { it.double("amount") ?: 0.0 }.v03eur(), "hors filtres de période", Modifier.weight(1f))
        }
        if (yearDelta != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Comparaison $currentYear / ${currentYear - 1}", fontWeight = FontWeight.SemiBold)
                    Text("%+.1f %%".format(yearDelta), fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Répartition par catégorie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    categories.forEach { (category, amount) ->
                        Column(Modifier.fillMaxWidth().clickable { selectedCategory = category }.padding(vertical = 3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${amount.v03eur()} · ${if (total > 0) "%.1f".format(amount * 100 / total) else "0"} %", fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator({ if (maxCategory > 0) (amount / maxCategory).toFloat() else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Évolution mensuelle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    monthTotals.forEach { (month, amount) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(month.toString())
                            Text(amount.v03eur(), fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator({ if (maxMonth > 0) (amount / maxMonth).toFloat() else 0f }, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("10 plus grosses dépenses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                biggest.forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${row.string("date") ?: "—"} · ${row.string("category") ?: "Sans catégorie"}${row.v03desc().takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((row.double("amount") ?: 0.0).v03eur(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    selectedCategory?.let { category ->
        val categoryRows = visible.filter { (it.string("category") ?: "Sans catégorie") == category }.sortedByDescending { it.string("date") }
        AlertDialog(
            onDismissRequest = { selectedCategory = null },
            title = { Text(category) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categoryRows) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(row.string("date") ?: "—", fontWeight = FontWeight.SemiBold)
                                row.v03desc().takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Text((row.double("amount") ?: 0.0).v03eur(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedCategory = null }) { Text("Fermer") } }
        )
    }
}

private fun driveLineDateV03(
    line: DbRow,
    ordersByPk: Map<Long, DbRow>,
    ordersByExternal: Map<String, DbRow>
): String? {
    line.long("orderId", "order_id")?.let { ref -> ordersByPk[ref]?.string("date")?.let { return it } }
    line.string("orderId", "order_id")?.let { ref -> ordersByExternal[ref]?.string("date")?.let { return it } }
    return null
}

private fun buildProductsV03(orders: List<DbRow>, lines: List<DbRow>): List<V03Product> {
    val ordersByPk = orders.mapNotNull { row -> row.long("id")?.let { it to row } }.toMap()
    val ordersByExternal = orders.mapNotNull { row -> row.string("orderId")?.let { it to row } }.toMap()
    data class Temp(val label: String, val section: String, var qty: Double = 0.0, var total: Double = 0.0, val orderRefs: MutableSet<String> = linkedSetOf(), var last: String = "", val prices: MutableList<Pair<String, Double>> = mutableListOf())
    val temp = linkedMapOf<String, Temp>()
    lines.forEach { line ->
        val label = line.string("label", "productName", "product", "name")?.trim().orEmpty()
        if (label.isBlank()) return@forEach
        val key = v03Normalize(label)
        val section = line.string("section", "category", "rayon") ?: "Sans rayon"
        val qty = line.double("quantity", "qty") ?: 1.0
        val unit = line.double("unitPrice", "unit_price", "price")
        val total = line.double("total", "lineTotal", "line_total") ?: (unit?.times(qty) ?: 0.0)
        val date = driveLineDateV03(line, ordersByPk, ordersByExternal).orEmpty()
        val orderRef = line.string("orderId", "order_id").orEmpty()
        val item = temp.getOrPut(key) { Temp(label, section) }
        item.qty += qty
        item.total += total
        if (orderRef.isNotBlank()) item.orderRefs += orderRef
        if (date > item.last) item.last = date
        if (unit != null && unit > 0 && date.isNotBlank()) item.prices += date to unit
    }
    return temp.map { (key, value) -> V03Product(key, value.label, value.section, value.qty, value.total, value.orderRefs.size, value.last, value.prices.toList()) }
}

@Composable
private fun DriveAnalyticsV03(revision: Int, productsOnly: Boolean, modifier: Modifier = Modifier) {
    val allOrders = remember(revision) { DesktopStore.v03rows("drive_orders") }
    val allLines = remember(revision) { DesktopStore.v03rows("drive_order_lines") }
    val budget = remember(revision) { DesktopStore.v03rows("monthly_budget").firstOrNull() }
    val start = budget?.string("startDate").v03date()
    val end = budget?.string("endDate").v03date()
    val months = remember(allOrders) { allOrders.mapNotNull { it.string("date").v03date()?.let(YearMonth::from)?.toString() }.distinct().sortedDescending() }
    var scope by remember { mutableStateOf("ALL") }
    val orders = remember(allOrders, scope, start, end) { rowsForScopeV03(allOrders, scope, start, end) }
    val orderPk = remember(orders) { orders.mapNotNull { it.long("id") }.toSet() }
    val orderExt = remember(orders) { orders.mapNotNull { it.string("orderId") }.toSet() }
    val lines = remember(allLines, orderPk, orderExt) {
        allLines.filter { line ->
            val n = line.long("orderId", "order_id")
            val s = line.string("orderId", "order_id")
            (n != null && n in orderPk) || (s != null && s in orderExt)
        }
    }
    val products = remember(orders, lines) { buildProductsV03(orders, lines) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(V03ProductSort.SPEND) }
    var productTarget by remember { mutableStateOf<V03Product?>(null) }
    val visibleProducts = remember(products, search, sort) {
        val filtered = products.filter { search.isBlank() || it.label.contains(search, true) || it.section.contains(search, true) }
        when (sort) {
            V03ProductSort.SPEND -> filtered.sortedByDescending { it.total }
            V03ProductSort.QUANTITY -> filtered.sortedByDescending { it.quantity }
            V03ProductSort.FREQUENCY -> filtered.sortedByDescending { it.orders }
            V03ProductSort.AZ -> filtered.sortedBy { it.label.lowercase(Locale.FRANCE) }
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodPickerV03(scope, months) { scope = it }

        if (!productsOnly) {
            val total = orders.sumOf { it.double("total") ?: 0.0 }
            val savings = orders.sumOf { it.double("savings") ?: 0.0 }
            val ticket = orders.sumOf { it.double("ticketLeclerc") ?: 0.0 }
            val lineTotal = lines.sumOf { line ->
                line.double("total", "lineTotal", "line_total") ?: ((line.double("unitPrice", "unit_price", "price") ?: 0.0) * (line.double("quantity", "qty") ?: 1.0))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                V03Stat("Commandes", orders.size.toString(), if (orders.isEmpty()) "—" else "panier moyen ${(total / orders.size).v03eur()}", Modifier.weight(1f))
                V03Stat("Total payé", total.v03eur(), "${lines.size} lignes produit", Modifier.weight(1f))
                V03Stat("Avantages", (savings + ticket).v03eur(), "${savings.v03eur()} immédiat + ${ticket.v03eur()} ticket", Modifier.weight(1f))
                V03Stat("Écart lignes", (total - lineTotal).v03eur(), "frais / remises globales / lignes non reconnues", Modifier.weight(1f))
            }

            val monthly = orders.mapNotNull { row -> row.string("date").v03date()?.let { YearMonth.from(it) to (row.double("total") ?: 0.0) } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedBy { it.first }
            val maxMonthly = monthly.maxOfOrNull { it.second } ?: 0.0
            val stores = orders.groupBy { it.string("store") ?: "Magasin inconnu" }.mapValues { it.value.sumOf { r -> r.double("total") ?: 0.0 } }.toList().sortedByDescending { it.second }
            val maxStore = stores.maxOfOrNull { it.second } ?: 0.0
            val sections = lines.groupBy { it.string("section", "category", "rayon") ?: "Sans rayon" }
                .mapValues { (_, rows) -> rows.sumOf { r -> r.double("total", "lineTotal", "line_total") ?: ((r.double("unitPrice", "price") ?: 0.0) * (r.double("quantity", "qty") ?: 1.0)) } }
                .toList().sortedByDescending { it.second }
            val maxSection = sections.maxOfOrNull { it.second } ?: 0.0

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Card(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Dépenses par mois", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        monthly.forEach { (month, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(month.toString()); Text(amount.v03eur(), fontWeight = FontWeight.SemiBold) }
                            LinearProgressIndicator({ if (maxMonthly > 0) (amount / maxMonthly).toFloat() else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                }
                Card(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Par magasin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        stores.forEach { (store, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(store, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(amount.v03eur(), fontWeight = FontWeight.SemiBold) }
                            LinearProgressIndicator({ if (maxStore > 0) (amount / maxStore).toFloat() else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Répartition par rayon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    sections.forEach { (section, amount) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(section, modifier = Modifier.weight(1f))
                            Text("${amount.v03eur()} · ${if (lineTotal > 0) "%.1f".format(amount * 100 / lineTotal) else "0"} %", fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator({ if (maxSection > 0) (amount / maxSection).toFloat() else 0f }, Modifier.fillMaxWidth())
                    }
                }
            }
            Text("Produits les plus achetés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Rechercher un produit / rayon") }, singleLine = true, modifier = Modifier.width(390.dp))
            V03ProductSort.entries.forEach { item -> FilterChip(sort == item, { sort = item }, label = { Text(item.label) }) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                chooseCsvToSave("NicoBudget_produits_${scope}.csv")?.let { file ->
                    runCatching { DesktopV03Tools.exportProductsCsv(file, visibleProducts) }
                }
            }) { Text("Exporter CSV") }
        }

        val maxProduct = when (sort) {
            V03ProductSort.SPEND -> visibleProducts.maxOfOrNull { it.total } ?: 0.0
            V03ProductSort.QUANTITY -> visibleProducts.maxOfOrNull { it.quantity } ?: 0.0
            V03ProductSort.FREQUENCY -> visibleProducts.maxOfOrNull { it.orders.toDouble() } ?: 0.0
            V03ProductSort.AZ -> 0.0
        }
        val productLimit = if (productsOnly) visibleProducts else visibleProducts.take(20)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (productsOnly) "Tous les produits (${visibleProducts.size})" else "Top 20 produits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                productLimit.forEach { product ->
                    Column(Modifier.fillMaxWidth().clickable { productTarget = product }.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(product.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${product.section} · ${product.orders} commande(s) · x${"%.2f".format(product.quantity)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(product.total.v03eur(), fontWeight = FontWeight.Bold)
                        }
                        if (sort != V03ProductSort.AZ) {
                            val value = when (sort) {
                                V03ProductSort.SPEND -> product.total
                                V03ProductSort.QUANTITY -> product.quantity
                                V03ProductSort.FREQUENCY -> product.orders.toDouble()
                                else -> 0.0
                            }
                            LinearProgressIndicator({ if (maxProduct > 0) (value / maxProduct).toFloat() else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    productTarget?.let { product ->
        val monthlyPrices = product.unitPrices.groupBy { it.first.take(7) }.mapValues { (_, p) -> p.map { it.second }.average() }.toList().sortedBy { it.first }
        AlertDialog(
            onDismissRequest = { productTarget = null },
            title = { Text(product.label) },
            text = {
                Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${product.section} · ${product.orders} commande(s) · x${"%.2f".format(product.quantity)} · ${product.total.v03eur()}")
                    Text("Évolution du prix unitaire", fontWeight = FontWeight.Bold)
                    if (monthlyPrices.isEmpty()) Text("Aucun prix unitaire exploitable.")
                    monthlyPrices.forEach { (month, price) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(month); Text(price.v03eur(), fontWeight = FontWeight.SemiBold) }
                    }
                    if (monthlyPrices.size >= 2 && monthlyPrices.first().second > 0) {
                        val delta = (monthlyPrices.last().second / monthlyPrices.first().second - 1) * 100
                        HorizontalDivider()
                        Text("Variation première → dernière observation : %+.1f %%".format(delta), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { productTarget = null }) { Text("Fermer") } }
        )
    }
}

@Composable
internal fun DriveV03Screen(model: AppModel) {
    val orders = remember(model.revision) { DesktopStore.v03rows("drive_orders").sortedByDescending { it.string("date") } }
    val lines = remember(model.revision) { DesktopStore.v03rows("drive_order_lines") }
    var search by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<String?>(null) }
    var year by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DbRow?>(null) }
    var edit by remember { mutableStateOf<DbRow?>(null) }
    var delete by remember { mutableStateOf<DbRow?>(null) }
    var deleteExpense by remember { mutableStateOf(true) }
    val stores = remember(orders) { orders.mapNotNull { it.string("store") }.distinct().sorted() }
    val years = remember(orders) { orders.mapNotNull { it.string("date")?.take(4) }.distinct().sortedDescending() }
    val visible = remember(orders, search, store, year) {
        orders.filter { row ->
            (search.isBlank() || row.v03search().contains(search, true)) &&
                (store == null || row.string("store") == store) &&
                (year == null || row.string("date")?.startsWith(year!!) == true)
        }
    }
    val total = visible.sumOf { it.double("total") ?: 0.0 }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { V03Title("Leclerc Drive", "Historique exploitable : détails, correction, suppression et export.") }
            OutlinedButton(onClick = {
                chooseCsvToSave("NicoBudget_commandes_Drive.csv")?.let { file ->
                    runCatching { DesktopV03Tools.exportDriveOrdersCsv(file, visible) }
                        .onSuccess { model.refresh("Commandes Drive exportées dans ${file.name}.") }
                        .onFailure { model.fail("Export impossible : ${it.message}") }
                }
            }) { Text("Exporter CSV") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V03Stat("Commandes", visible.size.toString(), "sur ${orders.size}", Modifier.weight(1f))
            V03Stat("Total", total.v03eur(), if (visible.isEmpty()) "—" else "panier moyen ${(total / visible.size).v03eur()}", Modifier.weight(1f))
            V03Stat("Économies", visible.sumOf { it.double("savings") ?: 0.0 }.v03eur(), "immédiates", Modifier.weight(1f))
            V03Stat("Ticket", visible.sumOf { it.double("ticketLeclerc") ?: 0.0 }.v03eur(), "E.Leclerc", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Commande, magasin, date") }, singleLine = true, modifier = Modifier.width(330.dp))
            FilterChip(store == null, { store = null }, label = { Text("Tous magasins") })
            stores.forEach { s -> FilterChip(store == s, { store = s }, label = { Text(s) }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item { FilterChip(year == null, { year = null }, label = { Text("Toutes années") }) }
            items(years) { y -> FilterChip(year == y, { year = y }, label = { Text(y) }) }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth().clickable { detail = row }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Commande ${row.string("orderId") ?: "—"}", fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(row.string("date"), row.string("time"), row.string("store"), row.string("productCount")?.let { "$it produit(s)" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Text((row.double("total") ?: 0.0).v03eur(), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { detail = row }) { Text("Détails") }
                        TextButton(onClick = { edit = row }) { Text("Corriger") }
                        TextButton(onClick = { delete = row; deleteExpense = row.long("expenseId") != null }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    detail?.let { order ->
        val orderId = order.long("id")
        val ext = order.string("orderId")
        val orderLines = lines.filter { line ->
            (orderId != null && line.long("orderId", "order_id") == orderId) || (ext != null && line.string("orderId", "order_id") == ext)
        }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Commande ${ext ?: "—"}") },
            text = {
                Column(Modifier.heightIn(max = 620.dp)) {
                    Text(listOfNotNull(order.string("date"), order.string("time"), order.string("store")).joinToString(" · "))
                    Text("Total ${(order.double("total") ?: 0.0).v03eur()} · économies ${(order.double("savings") ?: 0.0).v03eur()} · ticket ${(order.double("ticketLeclerc") ?: 0.0).v03eur()}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(orderLines) { line ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.string("label", "productName", "name") ?: "Produit", fontWeight = FontWeight.SemiBold)
                                    Text("${line.string("section", "category") ?: "Sans rayon"} · x${line.double("quantity", "qty") ?: 1.0}${line.double("unitPrice", "price")?.let { " · ${it.v03eur()}/u" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                                }
                                Text((line.double("total", "lineTotal") ?: ((line.double("unitPrice", "price") ?: 0.0) * (line.double("quantity", "qty") ?: 1.0))).v03eur())
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Fermer") } }
        )
    }

    edit?.let { order ->
        DriveOrderEditV03(order, { edit = null }) { values ->
            runCatching {
                DesktopEditor.updateRow("drive_orders", order.desktopId, values)
                val expenseId = order.long("expenseId")
                if (expenseId != null) {
                    DesktopStore.v03rows("expenses").firstOrNull { it.long("id") == expenseId }?.let { expense ->
                        DesktopEditor.updateRow("expenses", expense.desktopId, mapOf("date" to values["date"], "amount" to values["total"]))
                    }
                }
                DesktopEditor.recomputeCurrentBudget()
            }.onSuccess { edit = null; model.refresh("Commande Drive corrigée.") }
                .onFailure { model.fail("Correction impossible : ${it.message}") }
        }
    }

    delete?.let { order ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Supprimer la commande ${order.string("orderId") ?: ""} ?") },
            text = {
                Column {
                    Text("La commande et ses lignes produit seront supprimées de la base PC.")
                    if (order.long("expenseId") != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deleteExpense, { deleteExpense = it })
                            Text("Supprimer aussi la dépense budget liée")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopV03Tools.deleteDriveOrder(order, deleteExpense) }
                        .onSuccess { delete = null; model.refresh("Commande Drive supprimée.") }
                        .onFailure { model.fail("Suppression impossible : ${it.message}") }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun DriveOrderEditV03(order: DbRow, onDismiss: () -> Unit, onSave: (Map<String, Any?>) -> Unit) {
    var date by remember { mutableStateOf(order.string("date").orEmpty()) }
    var store by remember { mutableStateOf(order.string("store").orEmpty()) }
    var total by remember { mutableStateOf((order.double("total") ?: 0.0).toString()) }
    var savings by remember { mutableStateOf((order.double("savings") ?: 0.0).toString()) }
    var ticket by remember { mutableStateOf((order.double("ticketLeclerc") ?: 0.0).toString()) }
    val t = total.replace(',', '.').toDoubleOrNull()
    val s = savings.replace(',', '.').toDoubleOrNull()
    val tk = ticket.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger la commande") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Date (AAAA-MM-JJ)") })
                OutlinedTextField(store, { store = it }, label = { Text("Magasin") })
                OutlinedTextField(total, { total = it }, label = { Text("Total") })
                OutlinedTextField(savings, { savings = it }, label = { Text("Économies") })
                OutlinedTextField(ticket, { ticket = it }, label = { Text("Ticket Leclerc") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = runCatching { LocalDate.parse(date) }.isSuccess && t != null && s != null && tk != null,
                onClick = { onSave(mapOf("date" to date, "store" to store, "total" to t!!, "savings" to s!!, "ticketLeclerc" to tk!!)) }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun DataV03Screen(model: AppModel) {
    val hasData = remember(model.revision) { DesktopStore.hasDataset() }
    val tables = remember(model.revision) { DesktopStore.tableNames().map { it to DesktopStore.rowCount(it) } }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        V03Title("Données & échanges", "Backup complet, CSV et maintenance de la base locale. La synchronisation viendra après.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Backup NicoBudget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Le .nbbackup reste l'échange complet Android ↔ Windows tant que le mode de synchro définitif n'est pas choisi.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        chooseBackupToOpen()?.let { file ->
                            runCatching { DesktopStore.importBackup(file) }
                                .onSuccess { model.refresh("Backup importé : ${it.rows} lignes.") }
                                .onFailure { model.fail("Import impossible : ${it.message}") }
                        }
                    }) { Text("Importer .nbbackup") }
                    OutlinedButton(enabled = hasData, onClick = {
                        chooseBackupToSave()?.let { file ->
                            runCatching { DesktopStore.exportBackup(file) }
                                .onSuccess { model.refresh("Backup créé : ${it.file.name}.") }
                                .onFailure { model.fail("Export impossible : ${it.message}") }
                        }
                    }) { Text("Exporter .nbbackup") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Dépenses CSV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Format : date ; catégorie ; montant ; description. Pratique pour corriger ou préparer des opérations sur PC.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = DesktopStore.tableExists("expenses"), onClick = {
                        chooseCsvToOpen()?.let { file ->
                            runCatching { DesktopV03Tools.importExpensesCsv(file) }
                                .onSuccess { count -> DesktopEditor.recomputeCurrentBudget(); model.refresh("$count dépense(s) importée(s) depuis le CSV.") }
                                .onFailure { model.fail("Import CSV impossible : ${it.message}") }
                        }
                    }) { Text("Importer dépenses CSV") }
                    OutlinedButton(enabled = hasData, onClick = {
                        chooseCsvToSave("NicoBudget_depenses.csv")?.let { file ->
                            runCatching { DesktopV03Tools.exportExpensesCsv(file) }
                                .onSuccess { model.refresh("Dépenses exportées dans ${file.name}.") }
                                .onFailure { model.fail("Export CSV impossible : ${it.message}") }
                        }
                    }) { Text("Exporter dépenses CSV") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Base locale Windows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(DesktopStore.databaseFile.absolutePath, style = MaterialTheme.typography.bodySmall)
                Text("${tables.size} table(s) · ${tables.sumOf { it.second }} ligne(s)", fontWeight = FontWeight.SemiBold)
                tables.forEach { (name, count) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(count.toString()) } }
            }
        }
        OutlinedButton(enabled = hasData, onClick = { confirmClear = true }) { Text("Effacer uniquement la base Desktop") }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer la base Desktop ?") },
            text = { Text("Le téléphone et les fichiers .nbbackup ne seront pas touchés.") },
            confirmButton = { TextButton(onClick = { runCatching { DesktopStore.clearDataset() }.onSuccess { confirmClear = false; model.refresh("Base Desktop effacée.") }.onFailure { model.fail(it.message ?: "Erreur") } }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } }
        )
    }
}

private object DesktopV03Tools {
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${DesktopStore.databaseFile.absolutePath}")
    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun actualColumn(table: String, vararg names: String): String? = DesktopStore.columns(table).map { it.name }.firstOrNull { col -> names.any { it.equals(col, true) } }

    fun deleteDriveOrder(order: DbRow, deleteLinkedExpense: Boolean) {
        val orderPk = order.long("id")
        if (DesktopStore.tableExists("drive_order_lines") && orderPk != null) {
            actualColumn("drive_order_lines", "orderId", "order_id")?.let { col ->
                connection().use { db -> db.prepareStatement("DELETE FROM ${q("drive_order_lines")} WHERE ${q(col)}=?").use { ps -> ps.setLong(1, orderPk); ps.executeUpdate() } }
            }
        }
        if (deleteLinkedExpense) {
            order.long("expenseId", "expense_id")?.let { expenseId ->
                DesktopStore.v03rows("expenses").firstOrNull { it.long("id") == expenseId }?.let { DesktopStore.deleteRow("expenses", it.desktopId) }
            }
        }
        DesktopStore.deleteRow("drive_orders", order.desktopId)
        DesktopEditor.recomputeCurrentBudget()
    }

    fun exportDriveOrdersCsv(file: File, orders: List<DbRow>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("numero;date;heure;magasin;produits;total;economies;ticket_leclerc")
            orders.forEach { r -> out.appendLine(listOf(r.string("orderId"), r.string("date"), r.string("time"), r.string("store"), r.string("productCount"), r.double("total"), r.double("savings"), r.double("ticketLeclerc")).joinToString(";") { csvCell(it?.toString().orEmpty()) }) }
        }
    }

    fun exportProductsCsv(file: File, products: List<V03Product>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("produit;rayon;quantite;depense;commandes;derniere_date")
            products.forEach { p -> out.appendLine(listOf(p.label, p.section, p.quantity.toString(), p.total.toString(), p.orders.toString(), p.lastDate).joinToString(";") { csvCell(it) }) }
        }
    }

    fun exportExpensesCsv(file: File) {
        val current = DesktopStore.v03rows("expenses").map { "courante" to it }
        val archived = DesktopStore.v03rows("expense_archive").map { "archive" to it }
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("type;date;categorie;montant;description")
            (current + archived).sortedByDescending { it.second.string("date") }.forEach { (type, r) ->
                out.appendLine(listOf(type, r.string("date").orEmpty(), r.string("category").orEmpty(), (r.double("amount") ?: 0.0).toString(), r.v03desc()).joinToString(";") { csvCell(it) })
            }
        }
    }

    fun importExpensesCsv(file: File): Int {
        require(file.exists()) { "Fichier introuvable" }
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        val header = parseCsv(lines.first()).map { v03Normalize(it).replace(" ", "_") }
        val dateIdx = header.indexOfFirst { it == "date" }
        val catIdx = header.indexOfFirst { it in setOf("categorie", "category") }
        val amountIdx = header.indexOfFirst { it in setOf("montant", "amount") }
        val descIdx = header.indexOfFirst { it in setOf("description", "libelle", "label") }
        require(dateIdx >= 0 && catIdx >= 0 && amountIdx >= 0) { "Colonnes attendues : date, categorie, montant, description" }
        var count = 0
        lines.drop(1).forEach { raw ->
            val cells = parseCsv(raw)
            val date = cells.getOrNull(dateIdx).orEmpty().trim()
            val category = cells.getOrNull(catIdx).orEmpty().trim()
            val amount = cells.getOrNull(amountIdx).orEmpty().replace(',', '.').trim().toDoubleOrNull()
            val desc = cells.getOrNull(descIdx).orEmpty().trim()
            if (runCatching { LocalDate.parse(date) }.isSuccess && category.isNotBlank() && amount != null && amount > 0) {
                DesktopEditor.insertLike("expenses", mapOf("date" to date, "category" to category, "amount" to amount, "description" to desc, "label" to desc, "note" to desc, "title" to desc))
                count++
            }
        }
        return count
    }

    private fun csvCell(value: String): String = if (value.contains(';') || value.contains('"') || value.contains('\n')) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun parseCsv(line: String): List<String> {
        val delimiter = if (line.count { it == ';' } >= line.count { it == ',' }) ';' else ','
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ } else quoted = !quoted
            } else if (c == delimiter && !quoted) {
                out += current.toString(); current.clear()
            } else current.append(c)
            i++
        }
        out += current.toString()
        return out
    }
}

private fun chooseCsvToOpen(): File? {
    val chooser = JFileChooser().apply { dialogTitle = "Importer un CSV NicoBudget"; fileFilter = FileNameExtensionFilter("CSV (*.csv)", "csv") }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun chooseCsvToSave(defaultName: String): File? {
    val chooser = JFileChooser().apply { dialogTitle = "Exporter en CSV"; fileFilter = FileNameExtensionFilter("CSV (*.csv)", "csv"); selectedFile = File(defaultName) }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    return if (selected.extension.equals("csv", true)) selected else File(selected.parentFile, selected.name + ".csv")
}

@Composable
private fun V03Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun V03Stat(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
