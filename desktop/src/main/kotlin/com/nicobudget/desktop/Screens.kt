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
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val euroFormatter = NumberFormat.getCurrencyInstance(Locale.FRANCE)
private val shortDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun Double.eur(): String = euroFormatter.format(this)
private fun String?.localDateOrNull(): LocalDate? = runCatching { this?.let(LocalDate::parse) }.getOrNull()

@Composable
internal fun DashboardScreen(revision: Int) {
    val budget = remember(revision) { DesktopStore.rowsIfExists("monthly_budget").firstOrNull() }
    val expenses = remember(revision) { DesktopStore.rowsIfExists("expenses") }
    val fixed = remember(revision) { DesktopStore.rowsIfExists("fixed_charges") }
    val drive = remember(revision) { DesktopStore.rowsIfExists("drive_orders") }

    val start = budget?.string("startDate")?.localDateOrNull()
    val end = budget?.string("endDate")?.localDateOrNull()
    val currentExpenses = remember(expenses, start, end) {
        expenses.filter { row ->
            val date = row.string("date").localDateOrNull() ?: return@filter false
            (start == null || !date.isBefore(start)) && (end == null || date.isBefore(end))
        }
    }
    val spent = currentExpenses.sumOf { it.double("amount") ?: 0.0 }
    val fixedTotal = fixed.sumOf { it.double("amount") ?: 0.0 }
    val income = budget?.double("monthlyIncome") ?: 0.0
    val leftover = budget?.double("disposableLeftover") ?: (income - fixedTotal - spent)
    val currentDrive = drive.filter { row ->
        val date = row.string("date").localDateOrNull() ?: return@filter false
        (start == null || !date.isBefore(start)) && (end == null || date.isBefore(end))
    }
    val categories = currentExpenses.groupBy { it.string("category") ?: "Sans catégorie" }
        .mapValues { (_, rows) -> rows.sumOf { it.double("amount") ?: 0.0 } }
        .entries.sortedByDescending { it.value }.take(6)

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle("Tableau de bord", "Vue synthétique de la base NicoBudget importée sur ce PC.")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Reste du cycle", leftover.eur(), if (start != null && end != null) "$start → $end" else "Cycle courant", Modifier.weight(1f))
            StatCard("Dépenses variables", spent.eur(), "${currentExpenses.size} opération(s)", Modifier.weight(1f))
            StatCard("Charges fixes", fixedTotal.eur(), "${fixed.size} charge(s)", Modifier.weight(1f))
            StatCard("Drive", currentDrive.sumOf { it.double("total") ?: 0.0 }.eur(), "${currentDrive.size} commande(s)", Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Catégories principales du cycle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (categories.isEmpty()) Text("Aucune dépense sur le cycle courant.")
                categories.forEach { (category, amount) ->
                    val percent = if (spent > 0) amount * 100.0 / spent else 0.0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category, modifier = Modifier.weight(1f))
                        Text("${amount.eur()} · %.1f %%".format(percent), fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { if (spent > 0) (amount / spent).toFloat().coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Base Desktop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                KeyValue("Tables importées", DesktopStore.tableNames().size.toString())
                KeyValue("Backup source", DesktopStore.meta("backup_created_at") ?: "—")
                KeyValue("Importé sur ce PC", DesktopStore.meta("imported_at") ?: "—")
                KeyValue("Fichier local", DesktopStore.databaseFile.absolutePath)
            }
        }
    }
}

@Composable
internal fun ExpensesScreen(revision: Int) {
    val source = remember(revision) { DesktopStore.rowsIfExists("expenses").sortedByDescending { it.string("date") } }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val categories = remember(source) { source.mapNotNull { it.string("category") }.distinct().sorted() }
    val visible = remember(source, search, category) {
        source.filter { row ->
            val textMatch = search.isBlank() || row.searchableText().contains(search, ignoreCase = true)
            val catMatch = category == null || row.string("category") == category
            textMatch && catMatch
        }
    }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Dépenses", "Dépenses variables actuellement présentes dans le cycle actif.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Rechercher") },
                singleLine = true,
                modifier = Modifier.width(340.dp)
            )
            Text("${visible.size} opération(s) · ${visible.sumOf { it.double("amount") ?: 0.0 }.eur()}", fontWeight = FontWeight.SemiBold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Toutes") }) }
            items(categories) { cat -> FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat) }) }
        }
        ExpenseTable(visible, Modifier.weight(1f))
    }
}

@Composable
internal fun ArchivesScreen(model: AppModel) {
    val source = remember(model.revision) { DesktopStore.rowsIfExists("expense_archive").sortedByDescending { it.string("date") } }
    var search by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DbRow?>(null) }
    val visible = remember(source, search) { source.filter { search.isBlank() || it.searchableText().contains(search, ignoreCase = true) } }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Archives", "Historique budgétaire. Une dépense peut être supprimée individuellement sur le PC.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.width(340.dp))
            Text("${visible.size} archive(s) · ${visible.sumOf { it.double("amount") ?: 0.0 }.eur()}", fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(row.string("date"), row.description()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text((row.double("amount") ?: 0.0).eur(), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { deleteTarget = row }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cette dépense archivée ?") },
            text = { Text("${row.string("date")} · ${row.string("category")} · ${(row.double("amount") ?: 0.0).eur()}\n\nLa suppression sera incluse dans le prochain .nbbackup exporté depuis le PC.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopStore.deleteRow("expense_archive", row.desktopId) }
                        .onSuccess { model.refresh("Dépense archivée supprimée sur la base PC.") }
                        .onFailure { model.fail("Suppression impossible : ${it.message}") }
                    deleteTarget = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }
}

private enum class StatsScope(val label: String) { CYCLE("Cycle"), YEAR("Année"), MONTHS12("12 mois"), ALL("Tout") }

@Composable
internal fun BudgetStatsDesktopScreen(revision: Int) {
    val expenses = remember(revision) { DesktopStore.rowsIfExists("expenses") + DesktopStore.rowsIfExists("expense_archive") }
    val budget = remember(revision) { DesktopStore.rowsIfExists("monthly_budget").firstOrNull() }
    var scope by remember { mutableStateOf(StatsScope.CYCLE) }
    val today = LocalDate.now()
    val start = budget?.string("startDate").localDateOrNull()
    val end = budget?.string("endDate").localDateOrNull()
    val visible = remember(expenses, scope, today, start, end) {
        expenses.filter { row ->
            val date = row.string("date").localDateOrNull() ?: return@filter false
            when (scope) {
                StatsScope.CYCLE -> (start == null || !date.isBefore(start)) && (end == null || date.isBefore(end))
                StatsScope.YEAR -> date.year == today.year
                StatsScope.MONTHS12 -> !date.isBefore(today.minusMonths(12))
                StatsScope.ALL -> true
            }
        }
    }
    val byCategory = remember(visible) {
        visible.groupBy { it.string("category") ?: "Sans catégorie" }
            .mapValues { (_, rows) -> rows.sumOf { it.double("amount") ?: 0.0 } }
            .entries.sortedByDescending { it.value }
    }
    val total = visible.sumOf { it.double("amount") ?: 0.0 }
    val max = byCategory.maxOfOrNull { it.value } ?: 0.0

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Statistiques budget", "Répartition des dépenses de toutes les catégories, Drive compris.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StatsScope.entries) { item -> FilterChip(selected = scope == item, onClick = { scope = item }, label = { Text(if (item == StatsScope.YEAR) today.year.toString() else item.label) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Total", total.eur(), "${visible.size} opération(s)", Modifier.weight(1f))
            StatCard("Moyenne", if (visible.isNotEmpty()) (total / visible.size).eur() else 0.0.eur(), "par opération", Modifier.weight(1f))
            StatCard("Catégories", byCategory.size.toString(), "catégorie(s) utilisée(s)", Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(byCategory) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.key, fontWeight = FontWeight.SemiBold)
                            Text(item.value.eur(), fontWeight = FontWeight.Bold)
                        }
                        Text(if (total > 0) "%.1f %% du total".format(item.value * 100.0 / total) else "0 %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { if (max > 0) (item.value / max).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
internal fun DriveDesktopScreen(revision: Int) {
    val orders = remember(revision) { DesktopStore.rowsIfExists("drive_orders").sortedByDescending { it.string("date") } }
    var search by remember { mutableStateOf("") }
    val visible = remember(orders, search) { orders.filter { search.isBlank() || it.searchableText().contains(search, ignoreCase = true) } }
    val total = visible.sumOf { it.double("total") ?: 0.0 }
    val savings = visible.sumOf { it.double("savings") ?: 0.0 }
    val ticket = visible.sumOf { it.double("ticketLeclerc") ?: 0.0 }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Leclerc Drive", "Historique des commandes importées depuis le téléphone.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Commandes", visible.size.toString(), "historique affiché", Modifier.weight(1f))
            StatCard("Total", total.eur(), "achats Drive", Modifier.weight(1f))
            StatCard("Avantages", (savings + ticket).eur(), "réductions + ticket", Modifier.weight(1f))
        }
        OutlinedTextField(search, { search = it }, label = { Text("Rechercher n° commande, magasin, date") }, singleLine = true, modifier = Modifier.width(430.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Commande ${row.string("orderId") ?: "—"}", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(row.string("date"), row.string("store"), row.string("productCount")?.let { "$it produit(s)" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text((row.double("total") ?: 0.0).eur(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MenusDesktopScreen(revision: Int) {
    val prefName = "drive_menu_planner_v2"
    val plan = remember(revision) { DesktopStore.preferenceString(prefName, "plan_v3").orEmpty().split('|') }
    val servings = remember(revision) { DesktopStore.preferenceString(prefName, "servings_v3").orEmpty().split(',').mapNotNull { it.toIntOrNull() } }
    val excluded = remember(revision) { DesktopStore.preferenceStringSet(prefName, "excluded_v3") }
    val profiles = remember(revision) { DesktopStore.preferenceString(prefName, "profiles_v4").orEmpty() }
    val recipeNames = remember { menuRecipeNames() }
    val formatter = remember { DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE) }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Menus", "Planning synchronisé via le backup Android. Édition Desktop prévue dans l'étape suivante.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Créneaux actifs", servings.count { it > 0 }.toString(), "sur 14", Modifier.weight(1f))
            StatCard("Incompatibilités foyer", excluded.size.toString(), "règle(s)", Modifier.weight(1f))
            StatCard("Profils", parseProfileNames(profiles).size.toString(), parseProfileNames(profiles).joinToString().ifBlank { "aucun profil" }, Modifier.weight(1f))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(7) { day ->
                val date = LocalDate.now().plusDays(day.toLong())
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(date.format(formatter).replaceFirstChar { it.titlecase(Locale.FRANCE) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MealSlot("Midi", day * 2, plan, servings, recipeNames, Modifier.weight(1f))
                            MealSlot("Soir", day * 2 + 1, plan, servings, recipeNames, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealSlot(label: String, index: Int, plan: List<String>, servings: List<Int>, names: Map<String, String>, modifier: Modifier) {
    val people = servings.getOrNull(index) ?: 0
    val id = plan.getOrNull(index).orEmpty()
    Surface(modifier, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(if (people > 0) "$people pers." else "—", style = MaterialTheme.typography.labelMedium)
            }
            Text(if (people <= 0) "Pas de repas prévu" else names[id] ?: id.ifBlank { "Menu non défini" })
        }
    }
}

@Composable
internal fun DataDesktopScreen(model: AppModel) {
    var confirmClear by remember { mutableStateOf(false) }
    val hasData = remember(model.revision) { DesktopStore.hasDataset() }
    val tables = remember(model.revision) { DesktopStore.tableNames().map { it to DesktopStore.rowCount(it) } }

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Données & backup", "Le même format .nbbackup est utilisé sur Android et Windows.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Importer / exporter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("L'import remplace la base locale Desktop. L'export reconstruit un .nbbackup réinjectable sur Android.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        chooseBackupToOpen()?.let { file ->
                            runCatching { DesktopStore.importBackup(file) }
                                .onSuccess { model.refresh("Backup importé : ${it.tables} tables, ${it.rows} lignes.") }
                                .onFailure { model.fail("Import impossible : ${it.message}") }
                        }
                    }) { Text("Importer .nbbackup") }
                    OutlinedButton(enabled = hasData, onClick = {
                        chooseBackupToSave()?.let { file ->
                            runCatching { DesktopStore.exportBackup(file) }
                                .onSuccess { model.refresh("Backup PC créé : ${it.rows} lignes dans ${it.file.name}.") }
                                .onFailure { model.fail("Export impossible : ${it.message}") }
                        }
                    }) { Text("Exporter .nbbackup") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Base locale Windows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                KeyValue("Emplacement", DesktopStore.databaseFile.absolutePath)
                KeyValue("Tables", tables.size.toString())
                KeyValue("Lignes", tables.sumOf { it.second }.toString())
                tables.forEach { (name, count) -> KeyValue(name, count.toString()) }
            }
        }

        OutlinedButton(enabled = hasData, onClick = { confirmClear = true }) { Text("Effacer uniquement la base Desktop") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer la base Desktop ?") },
            text = { Text("Cela n'affecte ni le téléphone ni tes fichiers .nbbackup existants. Exporte un backup PC avant si tu as fait des modifications ici.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopStore.clearDataset() }
                        .onSuccess { model.refresh("Base Desktop effacée.") }
                        .onFailure { model.fail("Effacement impossible : ${it.message}") }
                    confirmClear = false
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } }
        )
    }
}

@Composable
internal fun SyncDesktopScreen() {
    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Synchronisation", "Le moteur LAN sera branché sur cette base locale après validation de la V1 Desktop.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("État", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Mode actuel : échange manuel par .nbbackup", fontWeight = FontWeight.SemiBold)
                Text("Prochaine étape : pairage téléphone ↔ PC, UUID stables, journal différentiel et suppressions propagées par tombstones.")
                HorizontalDivider()
                Text("La synchronisation restera distincte de la sauvegarde : un appareil synchronisé n'est pas, à lui seul, une sauvegarde.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Préparation déjà en place", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("✓ base SQLite locale PC")
                Text("✓ import/export commun .nbbackup")
                Text("✓ suppressions Desktop exportables")
                Text("✓ écran de synchronisation dédié")
                Text("○ pairage LAN chiffré")
                Text("○ synchro différentielle")
                Text("○ résolution de conflits")
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 240.dp))
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExpenseTable(rows: List<DbRow>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(rows, key = { it.desktopId }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(120.dp)) {
                        Text(row.string("date") ?: "—", fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                        row.description()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text((row.double("amount") ?: 0.0).eur(), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun DbRow.description(): String? = string("description", "label", "name", "note", "title")?.takeIf { it.isNotBlank() }
private fun DbRow.searchableText(): String = values.values.joinToString(" ") { it?.toString().orEmpty() }

private fun DesktopStore.rowsIfExists(table: String): List<DbRow> = if (tableExists(table)) rows(table) else emptyList()

private fun parseProfileNames(encoded: String): List<String> = encoded.split("||").mapNotNull { item ->
    val parts = item.split("::", limit = 3)
    parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun menuRecipeNames(): Map<String, String> = linkedMapOf(
    "poulet_haricots_pdt" to "Poulet, haricots verts & pommes de terre",
    "poulet_riz_courgettes" to "Poulet, riz & courgettes",
    "cordon_puree_carottes" to "Cordon bleu, purée & carottes",
    "nuggets_frites_haricots" to "Nuggets, frites & haricots verts",
    "poulet_pates_brocoli" to "Poulet, pâtes & brocoli",
    "steak_haricots_pdt" to "Steak haché, haricots verts & pommes de terre",
    "burger_frites_salade" to "Burgers maison, frites & salade",
    "boulettes_spaghetti" to "Boulettes de bœuf & spaghetti",
    "bolognaise" to "Spaghetti bolognaise",
    "boeuf_riz_carottes" to "Bœuf, riz & carottes",
    "saucisses_lentilles" to "Saucisses & lentilles",
    "chipolatas_frites_courgettes" to "Chipolatas, frites & courgettes",
    "jambon_coquillettes" to "Jambon, coquillettes & fromage",
    "porc_pdt_carottes" to "Porc, pommes de terre & carottes",
    "croque_salade" to "Croque-monsieur & salade",
    "saumon_riz_brocoli" to "Saumon, riz & brocoli",
    "colin_puree_haricots" to "Colin, purée & haricots verts",
    "thon_pates_tomate" to "Pâtes au thon & tomate",
    "poisson_frites_legumes" to "Poisson, frites & légumes",
    "surimi_riz_salade" to "Salade de riz au surimi",
    "omelette_jambon_salade" to "Omelette jambon-fromage & salade",
    "oeufs_pdt_haricots" to "Œufs, pommes de terre & haricots verts",
    "omelette_legumes" to "Omelette aux légumes",
    "pizza_salade" to "Pizza & salade",
    "quiche_salade" to "Quiche & salade",
    "tarte_poireaux" to "Tarte aux poireaux & salade",
    "lasagnes_salade" to "Lasagnes & salade",
    "ravioli_legumes" to "Ravioli & légumes",
    "gratin_salade" to "Gratin & salade",
    "paella_salade" to "Paella & salade",
    "couscous_legumes" to "Couscous & légumes",
    "gnocchi_tomate_fromage" to "Gnocchi tomate-fromage",
    "pates_fromage_legumes" to "Pâtes, fromage & légumes"
)
