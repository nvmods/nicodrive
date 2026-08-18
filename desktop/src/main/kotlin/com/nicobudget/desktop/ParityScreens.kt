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
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

private val parityEuro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
private fun Double.peur(): String = parityEuro.format(this)
private fun DbRow.parityDescription(): String? =
    string("description", "label", "name", "note", "title")?.takeIf { it.isNotBlank() }
private fun DbRow.paritySearchable(): String = values.values.joinToString(" ") { it?.toString().orEmpty() }

@Composable
internal fun ExpensesParityScreen(model: AppModel) {
    val source = remember(model.revision) {
        if (DesktopStore.tableExists("expenses")) {
            DesktopStore.rows("expenses").sortedByDescending { it.string("date") }
        } else emptyList()
    }
    var search by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<DbRow?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DbRow?>(null) }
    val visible = remember(source, search) {
        source.filter { search.isBlank() || it.paritySearchable().contains(search, ignoreCase = true) }
    }

    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dépenses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Création, modification et suppression depuis Windows.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = { createOpen = true },
                enabled = DesktopStore.tableExists("expenses")
            ) { Text("+ Nouvelle dépense") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Rechercher") },
                singleLine = true,
                modifier = Modifier.width(360.dp)
            )
            Text(
                "${visible.size} opération(s) · ${visible.sumOf { it.double("amount") ?: 0.0 }.peur()}",
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            row.string("date") ?: "—",
                            modifier = Modifier.width(115.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(Modifier.weight(1f)) {
                            Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                            row.parityDescription()?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { editTarget = row }) { Text("Modifier") }
                        TextButton(onClick = { deleteTarget = row }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    if (createOpen) {
        ExpenseEditDialog(
            row = null,
            onDismiss = { createOpen = false },
            onSave = { date, category, amount, description ->
                runCatching {
                    DesktopEditor.insertLike("expenses", expenseOverrides(date, category, amount, description))
                    DesktopEditor.recomputeCurrentBudget()
                }.onSuccess {
                    createOpen = false
                    model.refresh("Dépense ajoutée sur le PC.")
                }.onFailure { model.fail("Ajout impossible : ${it.message}") }
            }
        )
    }

    editTarget?.let { row ->
        ExpenseEditDialog(
            row = row,
            onDismiss = { editTarget = null },
            onSave = { date, category, amount, description ->
                runCatching {
                    DesktopEditor.updateRow(
                        "expenses",
                        row.desktopId,
                        expenseOverrides(date, category, amount, description)
                    )
                    DesktopEditor.recomputeCurrentBudget()
                }.onSuccess {
                    editTarget = null
                    model.refresh("Dépense modifiée sur le PC.")
                }.onFailure { model.fail("Modification impossible : ${it.message}") }
            }
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cette dépense ?") },
            text = {
                Text("${row.string("date")} · ${row.string("category")} · ${(row.double("amount") ?: 0.0).peur()}")
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        DesktopEditor.deleteRow("expenses", row.desktopId)
                        DesktopEditor.recomputeCurrentBudget()
                    }.onSuccess {
                        deleteTarget = null
                        model.refresh("Dépense supprimée.")
                    }.onFailure { model.fail("Suppression impossible : ${it.message}") }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }
}

private fun expenseOverrides(
    date: String,
    category: String,
    amount: Double,
    description: String
): Map<String, Any?> = mapOf(
    "date" to date,
    "category" to category,
    "amount" to amount,
    "description" to description,
    "label" to description,
    "note" to description,
    "title" to description
)

@Composable
private fun ExpenseEditDialog(
    row: DbRow?,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, String) -> Unit
) {
    val categories = remember { DesktopEditor.categoryNames() }
    var date by remember(row?.desktopId) { mutableStateOf(row?.string("date") ?: LocalDate.now().toString()) }
    var category by remember(row?.desktopId) {
        mutableStateOf(row?.string("category") ?: categories.firstOrNull().orEmpty())
    }
    var amountText by remember(row?.desktopId) { mutableStateOf(row?.double("amount")?.toString().orEmpty()) }
    var description by remember(row?.desktopId) { mutableStateOf(row?.parityDescription().orEmpty()) }
    var categoryOpen by remember { mutableStateOf(false) }
    val amount = amountText.replace(',', '.').toDoubleOrNull()
    val validDate = runCatching { LocalDate.parse(date) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (row == null) "Nouvelle dépense" else "Modifier la dépense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (AAAA-MM-JJ)") },
                    singleLine = true
                )
                Box {
                    OutlinedButton(onClick = { categoryOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(category.ifBlank { "Choisir une catégorie" })
                    }
                    DropdownMenu(expanded = categoryOpen, onDismissRequest = { categoryOpen = false }) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    categoryOpen = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Montant") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount != null && amount > 0.0 && category.isNotBlank() && validDate,
                onClick = { onSave(date, category, amount!!, description) }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun ArchivesParityScreen(model: AppModel) {
    val source = remember(model.revision) {
        if (DesktopStore.tableExists("expense_archive")) {
            DesktopStore.rows("expense_archive").sortedByDescending { it.string("date") }
        } else emptyList()
    }
    var search by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<DbRow?>(null) }
    var deleteTarget by remember { mutableStateOf<DbRow?>(null) }
    val visible = remember(source, search) {
        source.filter { search.isBlank() || it.paritySearchable().contains(search, ignoreCase = true) }
    }

    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Archives", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Modification ou suppression ciblée d'une dépense archivée.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Rechercher") },
            singleLine = true,
            modifier = Modifier.width(360.dp)
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(row.string("date"), row.parityDescription()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { editTarget = row }) { Text("Modifier") }
                        TextButton(onClick = { deleteTarget = row }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    editTarget?.let { row ->
        ExpenseEditDialog(
            row = row,
            onDismiss = { editTarget = null },
            onSave = { date, category, amount, description ->
                runCatching {
                    DesktopEditor.updateRow(
                        "expense_archive",
                        row.desktopId,
                        expenseOverrides(date, category, amount, description)
                    )
                }.onSuccess {
                    editTarget = null
                    model.refresh("Dépense archivée modifiée.")
                }.onFailure { model.fail("Modification impossible : ${it.message}") }
            }
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cette archive ?") },
            text = { Text("La suppression sera répercutée dans le prochain backup puis, plus tard, dans la synchronisation.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopEditor.deleteRow("expense_archive", row.desktopId) }
                        .onSuccess {
                            deleteTarget = null
                            model.refresh("Dépense archivée supprimée.")
                        }
                        .onFailure { model.fail("Suppression impossible : ${it.message}") }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }
}

@Composable
internal fun BudgetManagementScreen(model: AppModel) {
    val budget = remember(model.revision) {
        if (DesktopStore.tableExists("monthly_budget")) DesktopStore.rows("monthly_budget").firstOrNull() else null
    }
    val charges = remember(model.revision) {
        if (DesktopStore.tableExists("fixed_charges")) DesktopStore.rows("fixed_charges") else emptyList()
    }
    val incomes = remember(model.revision) {
        if (DesktopStore.tableExists("fixed_incomes")) DesktopStore.rows("fixed_incomes") else emptyList()
    }
    val categories = remember(model.revision) { DesktopEditor.categoryNames() }

    var incomeText by remember(model.revision) { mutableStateOf(budget?.double("monthlyIncome")?.toString().orEmpty()) }
    var startText by remember(model.revision) { mutableStateOf(budget?.string("startDate").orEmpty()) }
    var endText by remember(model.revision) { mutableStateOf(budget?.string("endDate").orEmpty()) }
    var addChargeOpen by remember { mutableStateOf(false) }
    var addIncomeOpen by remember { mutableStateOf(false) }
    var addCategoryOpen by remember { mutableStateOf(false) }
    var renameCategoryTarget by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Budget & récurrents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Budget courant, charges fixes, revenus fixes et catégories.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cycle courant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        incomeText,
                        { incomeText = it },
                        label = { Text("Revenu mensuel") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        startText,
                        { startText = it },
                        label = { Text("Début (AAAA-MM-JJ)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        endText,
                        { endText = it },
                        label = { Text("Fin (AAAA-MM-JJ)") },
                        modifier = Modifier.weight(1f)
                    )
                }
                val parsedIncome = incomeText.replace(',', '.').toDoubleOrNull()
                Button(
                    enabled = budget != null && parsedIncome != null &&
                        runCatching { LocalDate.parse(startText) }.isSuccess &&
                        runCatching { LocalDate.parse(endText) }.isSuccess,
                    onClick = {
                        runCatching {
                            DesktopEditor.updateRow(
                                "monthly_budget",
                                budget!!.desktopId,
                                mapOf(
                                    "monthlyIncome" to parsedIncome!!,
                                    "startDate" to startText,
                                    "endDate" to endText
                                )
                            )
                            DesktopEditor.recomputeCurrentBudget()
                        }.onSuccess { model.refresh("Budget courant mis à jour.") }
                            .onFailure { model.fail("Modification du budget impossible : ${it.message}") }
                    }
                ) { Text("Enregistrer le cycle") }
            }
        }

        RecurringSection(
            title = "Charges fixes",
            table = "fixed_charges",
            rows = charges,
            onAdd = { addChargeOpen = true },
            model = model,
            recomputeBudget = true
        )
        RecurringSection(
            title = "Revenus fixes",
            table = "fixed_incomes",
            rows = incomes,
            onAdd = { addIncomeOpen = true },
            model = model,
            recomputeBudget = false
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Catégories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = { addCategoryOpen = true }) { Text("+ Ajouter") }
                }
                categories.forEach { category ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(category, modifier = Modifier.weight(1f))
                        TextButton(onClick = { renameCategoryTarget = category }) { Text("Renommer") }
                        TextButton(onClick = {
                            runCatching { DesktopEditor.deleteCategory(category) }
                                .onSuccess { model.refresh("Catégorie supprimée.") }
                                .onFailure { model.fail(it.message ?: "Suppression impossible") }
                        }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    if (addChargeOpen) {
        RecurringEditDialog("Nouvelle charge fixe", null, { addChargeOpen = false }) { name, amount ->
            runCatching {
                DesktopEditor.insertLike("fixed_charges", recurringOverrides(name, amount))
                DesktopEditor.recomputeCurrentBudget()
            }.onSuccess {
                addChargeOpen = false
                model.refresh("Charge fixe ajoutée.")
            }.onFailure { model.fail("Ajout impossible : ${it.message}") }
        }
    }
    if (addIncomeOpen) {
        RecurringEditDialog("Nouveau revenu fixe", null, { addIncomeOpen = false }) { name, amount ->
            runCatching { DesktopEditor.insertLike("fixed_incomes", recurringOverrides(name, amount)) }
                .onSuccess {
                    addIncomeOpen = false
                    model.refresh("Revenu fixe ajouté.")
                }
                .onFailure { model.fail("Ajout impossible : ${it.message}") }
        }
    }
    if (addCategoryOpen) {
        NameDialog("Nouvelle catégorie", "", { addCategoryOpen = false }) { name ->
            runCatching { DesktopEditor.addCategory(name) }
                .onSuccess {
                    addCategoryOpen = false
                    model.refresh("Catégorie ajoutée.")
                }
                .onFailure { model.fail("Ajout impossible : ${it.message}") }
        }
    }
    renameCategoryTarget?.let { oldName ->
        NameDialog("Renommer la catégorie", oldName, { renameCategoryTarget = null }) { newName ->
            runCatching { DesktopEditor.renameCategory(oldName, newName) }
                .onSuccess {
                    renameCategoryTarget = null
                    model.refresh("Catégorie renommée.")
                }
                .onFailure { model.fail("Renommage impossible : ${it.message}") }
        }
    }
}

private fun recurringOverrides(name: String, amount: Double): Map<String, Any?> = mapOf(
    "name" to name,
    "label" to name,
    "title" to name,
    "amount" to amount
)

@Composable
private fun RecurringSection(
    title: String,
    table: String,
    rows: List<DbRow>,
    onAdd: () -> Unit,
    model: AppModel,
    recomputeBudget: Boolean
) {
    var editTarget by remember { mutableStateOf<DbRow?>(null) }
    var deleteTarget by remember { mutableStateOf<DbRow?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = onAdd, enabled = DesktopStore.tableExists(table)) { Text("+ Ajouter") }
            }
            rows.sortedByDescending { it.double("amount") ?: 0.0 }.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.string("name", "label", "title") ?: "Élément", modifier = Modifier.weight(1f))
                    Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editTarget = row }) { Text("Modifier") }
                    TextButton(onClick = { deleteTarget = row }) { Text("Supprimer") }
                }
            }
        }
    }

    editTarget?.let { row ->
        RecurringEditDialog("Modifier", row, { editTarget = null }) { name, amount ->
            runCatching {
                DesktopEditor.updateRow(table, row.desktopId, recurringOverrides(name, amount))
                if (recomputeBudget) DesktopEditor.recomputeCurrentBudget()
            }.onSuccess {
                editTarget = null
                model.refresh("Élément modifié.")
            }.onFailure { model.fail("Modification impossible : ${it.message}") }
        }
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cet élément ?") },
            text = { Text(row.string("name", "label", "title") ?: "Élément") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        DesktopEditor.deleteRow(table, row.desktopId)
                        if (recomputeBudget) DesktopEditor.recomputeCurrentBudget()
                    }.onSuccess {
                        deleteTarget = null
                        model.refresh("Élément supprimé.")
                    }.onFailure { model.fail("Suppression impossible : ${it.message}") }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun RecurringEditDialog(
    title: String,
    row: DbRow?,
    onDismiss: () -> Unit,
    onSave: (String, Double) -> Unit
) {
    var name by remember(row?.desktopId) { mutableStateOf(row?.string("name", "label", "title").orEmpty()) }
    var amountText by remember(row?.desktopId) { mutableStateOf(row?.double("amount")?.toString().orEmpty()) }
    val amount = amountText.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") })
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Montant") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && amount != null,
                onClick = { onSave(name.trim(), amount!!) }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Nom") }) },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private data class PersonProfile(
    val id: String,
    val name: String,
    val excluded: Set<String>
)

private val foodRules = listOf(
    "courgettes" to "Courgettes",
    "brocoli" to "Brocoli",
    "haricots_verts" to "Haricots verts",
    "carottes" to "Carottes",
    "salade" to "Salade",
    "tomate" to "Tomate / sauce tomate",
    "aubergines" to "Aubergines",
    "epinards" to "Épinards",
    "poireaux" to "Poireaux",
    "petits_pois" to "Petits pois",
    "champignons" to "Champignons",
    "oignons" to "Oignons",
    "concombre" to "Concombre",
    "poivrons" to "Poivrons",
    "chou_fleur" to "Chou-fleur",
    "chou" to "Chou",
    "mais" to "Maïs",
    "lentilles" to "Lentilles",
    "frites" to "Frites",
    "pommes_de_terre" to "Pommes de terre",
    "riz" to "Riz",
    "pates" to "Pâtes",
    "semoule" to "Semoule / couscous",
    "boeuf" to "Bœuf",
    "porc" to "Porc / jambon / saucisses",
    "poulet" to "Poulet / dinde",
    "poisson" to "Poisson / fruits de mer",
    "oeufs" to "Œufs",
    "fromage" to "Fromage"
)

private enum class DesktopMenuMode(val label: String) {
    HABITS("Habitudes"), VARIED("Varié"), ECONOMICAL("Économique"), QUICK("Rapide")
}

@Composable
internal fun MenusParityScreen(model: AppModel) {
    val prefName = "drive_menu_planner_v2"
    val recipeNames = remember { menuNamesParity() }
    var plan by remember(model.revision) {
        mutableStateOf(loadList14(DesktopStore.preferenceString(prefName, "plan_v3"), '|') { "" })
    }
    var servings by remember(model.revision) {
        mutableStateOf(loadIntList14(DesktopStore.preferenceString(prefName, "servings_v3")))
    }
    var locked by remember(model.revision) {
        mutableStateOf(parseIndexSet(DesktopStore.preferenceString(prefName, "locked_v3")))
    }
    var excluded by remember(model.revision) {
        mutableStateOf(DesktopStore.preferenceStringSet(prefName, "excluded_v3"))
    }
    var profiles by remember(model.revision) {
        mutableStateOf(parseProfiles(DesktopStore.preferenceString(prefName, "profiles_v4").orEmpty()))
    }
    var slotProfiles by remember(model.revision) {
        mutableStateOf(parseSlotProfiles(DesktopStore.preferenceString(prefName, "slot_profiles_v4").orEmpty()))
    }
    var mode by remember(model.revision) {
        mutableStateOf(
            DesktopStore.preferenceString(prefName, "mode_v3")
                ?.let { runCatching { DesktopMenuMode.valueOf(it) }.getOrNull() }
                ?: DesktopMenuMode.VARIED
        )
    }
    var recipeSlot by remember { mutableStateOf<Int?>(null) }
    var participantsSlot by remember { mutableStateOf<Int?>(null) }
    var showExcluded by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE) }

    fun persist(message: String = "Planning enregistré côté PC.") {
        DesktopEditor.setPreferenceString(prefName, "plan_v3", plan.joinToString("|"))
        DesktopEditor.setPreferenceString(prefName, "servings_v3", servings.joinToString(","))
        DesktopEditor.setPreferenceString(prefName, "locked_v3", locked.sorted().joinToString(","))
        DesktopEditor.setPreferenceString(prefName, "mode_v3", mode.name)
        DesktopEditor.setPreferenceStringSet(prefName, "excluded_v3", excluded)
        DesktopEditor.setPreferenceString(prefName, "profiles_v4", encodeProfiles(profiles))
        DesktopEditor.setPreferenceString(prefName, "slot_profiles_v4", encodeSlotProfiles(slotProfiles))
        model.refresh(message)
    }

    fun generateWeek() {
        val available = recipeNames.keys.shuffled(Random.Default).toMutableList()
        val next = plan.toMutableList()
        var cursor = 0
        for (index in 0 until 14) {
            if (servings[index] <= 0 || index in locked) continue
            if (cursor >= available.size) {
                available.shuffle(Random.Default)
                cursor = 0
            }
            next[index] = available[cursor++]
        }
        plan = next
        persist("Nouvelle semaine générée côté PC.")
    }

    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Menus & courses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Planning midi/soir, convives, verrouillage, profils et incompatibilités.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { showExcluded = true }) { Text("Incompatibilités (${excluded.size})") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showProfiles = true }) { Text("Profils (${profiles.size})") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { persist() }) { Text("Enregistrer") }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DesktopMenuMode.entries) { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(item.label) }
                )
            }
            item {
                Button(onClick = { generateWeek() }) { Text("Générer la semaine") }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(7) { day ->
                val date = LocalDate.now().plusDays(day.toLong())
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            date.format(formatter).replaceFirstChar { it.titlecase(Locale.FRANCE) },
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MenuSlotEditor(
                                index = day * 2,
                                label = "Midi",
                                plan = plan,
                                servings = servings,
                                locked = locked,
                                recipeNames = recipeNames,
                                profiles = profiles,
                                selectedProfiles = slotProfiles[day * 2].orEmpty(),
                                modifier = Modifier.weight(1f),
                                onServingsChange = { value ->
                                    servings = servings.toMutableList().also { it[day * 2] = value.coerceIn(0, 8) }
                                },
                                onLockChange = {
                                    val idx = day * 2
                                    locked = if (idx in locked) locked - idx else locked + idx
                                },
                                onChooseRecipe = { recipeSlot = day * 2 },
                                onChooseProfiles = { participantsSlot = day * 2 }
                            )
                            MenuSlotEditor(
                                index = day * 2 + 1,
                                label = "Soir",
                                plan = plan,
                                servings = servings,
                                locked = locked,
                                recipeNames = recipeNames,
                                profiles = profiles,
                                selectedProfiles = slotProfiles[day * 2 + 1].orEmpty(),
                                modifier = Modifier.weight(1f),
                                onServingsChange = { value ->
                                    servings = servings.toMutableList().also { it[day * 2 + 1] = value.coerceIn(0, 8) }
                                },
                                onLockChange = {
                                    val idx = day * 2 + 1
                                    locked = if (idx in locked) locked - idx else locked + idx
                                },
                                onChooseRecipe = { recipeSlot = day * 2 + 1 },
                                onChooseProfiles = { participantsSlot = day * 2 + 1 }
                            )
                        }
                    }
                }
            }
        }
    }

    recipeSlot?.let { slot ->
        RecipePickerDialog(
            recipeNames = recipeNames,
            onDismiss = { recipeSlot = null },
            onPick = { id ->
                plan = plan.toMutableList().also { it[slot] = id }
                recipeSlot = null
            }
        )
    }

    participantsSlot?.let { slot ->
        ParticipantPickerDialog(
            profiles = profiles,
            initial = slotProfiles[slot].orEmpty(),
            onDismiss = { participantsSlot = null },
            onSave = { selected ->
                slotProfiles = slotProfiles.toMutableMap().also { map ->
                    if (selected.isEmpty()) map.remove(slot) else map[slot] = selected
                }
                participantsSlot = null
            }
        )
    }

    if (showExcluded) {
        FoodRulesDialog(
            title = "Incompatibilités du foyer",
            initial = excluded,
            onDismiss = { showExcluded = false },
            onSave = {
                excluded = it
                showExcluded = false
            }
        )
    }

    if (showProfiles) {
        ProfileManagerDialog(
            initial = profiles,
            onDismiss = { showProfiles = false },
            onSave = { newProfiles ->
                profiles = newProfiles
                val validIds = newProfiles.map { it.id }.toSet()
                slotProfiles = slotProfiles.mapValues { (_, ids) -> ids.intersect(validIds) }
                    .filterValues { it.isNotEmpty() }
                showProfiles = false
            }
        )
    }
}

@Composable
private fun MenuSlotEditor(
    index: Int,
    label: String,
    plan: List<String>,
    servings: List<Int>,
    locked: Set<Int>,
    recipeNames: Map<String, String>,
    profiles: List<PersonProfile>,
    selectedProfiles: Set<String>,
    modifier: Modifier,
    onServingsChange: (Int) -> Unit,
    onLockChange: () -> Unit,
    onChooseRecipe: () -> Unit,
    onChooseProfiles: () -> Unit
) {
    val people = servings.getOrElse(index) { 0 }
    val recipeId = plan.getOrElse(index) { "" }
    val participantNames = profiles.filter { it.id in selectedProfiles }.map { it.name }
    Surface(modifier = modifier, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onServingsChange(people - 1) }) { Text("−") }
                Text(if (people == 0) "aucun" else "$people pers")
                TextButton(onClick = { onServingsChange(people + 1) }) { Text("+") }
                TextButton(onClick = onLockChange) { Text(if (index in locked) "🔒" else "🔓") }
            }
            Text(if (people == 0) "Pas de repas" else recipeNames[recipeId] ?: "Menu non défini")
            if (people > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onChooseRecipe) { Text("Choisir le repas") }
                    if (profiles.isNotEmpty()) TextButton(onClick = onChooseProfiles) { Text("Profils") }
                }
                if (participantNames.isNotEmpty()) {
                    Text(
                        "Présents : ${participantNames.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipePickerDialog(
    recipeNames: Map<String, String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val visible = remember(recipeNames, search) {
        recipeNames.entries.filter { search.isBlank() || it.value.contains(search, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir le repas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 470.dp)) {
                    items(visible) { entry ->
                        TextButton(onClick = { onPick(entry.key) }, modifier = Modifier.fillMaxWidth()) {
                            Text(entry.value, modifier = Modifier.fillMaxWidth())
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
private fun ParticipantPickerDialog(
    profiles: List<PersonProfile>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profils présents") },
        text = {
            Column {
                profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = profile.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + profile.id else selected - profile.id
                            }
                        )
                        Text(profile.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun FoodRulesDialog(
    title: String,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                foodRules.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in draft,
                            onCheckedChange = { checked -> draft = if (checked) draft + id else draft - id }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun ProfileManagerDialog(
    initial: List<PersonProfile>,
    onDismiss: () -> Unit,
    onSave: (List<PersonProfile>) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profils personnes") },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                draft.forEachIndexed { index, profile ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text("${profile.excluded.size} incompatibilité(s)", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { editIndex = index }) { Text("Modifier") }
                            TextButton(onClick = { draft = draft.toMutableList().also { it.removeAt(index) } }) { Text("Supprimer") }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    draft = draft + PersonProfile(UUID.randomUUID().toString(), "Personne ${draft.size + 1}", emptySet())
                    editIndex = draft.lastIndex
                }) { Text("+ Ajouter une personne") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )

    editIndex?.let { index ->
        val profile = draft.getOrNull(index)
        if (profile != null) {
            ProfileEditDialog(
                profile = profile,
                onDismiss = { editIndex = null },
                onSave = { updated ->
                    draft = draft.toMutableList().also { it[index] = updated }
                    editIndex = null
                }
            )
        }
    }
}

@Composable
private fun ProfileEditDialog(
    profile: PersonProfile,
    onDismiss: () -> Unit,
    onSave: (PersonProfile) -> Unit
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var excluded by remember(profile.id) { mutableStateOf(profile.excluded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le profil") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                foodRules.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in excluded,
                            onCheckedChange = { checked -> excluded = if (checked) excluded + id else excluded - id }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(profile.copy(name = name.trim(), excluded = excluded)) }
            ) { Text("Appliquer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private fun parseProfiles(encoded: String): List<PersonProfile> =
    encoded.split("||").mapNotNull { value ->
        if (value.isBlank()) return@mapNotNull null
        val parts = value.split("::", limit = 3)
        val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Personne"
        val excluded = parts.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        PersonProfile(id, name, excluded)
    }

private fun encodeProfiles(profiles: List<PersonProfile>): String =
    profiles.joinToString("||") { profile ->
        val safeName = profile.name.replace("||", " ").replace("::", " ")
        "${profile.id}::$safeName::${profile.excluded.sorted().joinToString(",")}" 
    }

private fun parseSlotProfiles(encoded: String): Map<Int, Set<String>> =
    encoded.split(';').mapNotNull { entry ->
        if (entry.isBlank() || '=' !in entry) return@mapNotNull null
        val slot = entry.substringBefore('=').toIntOrNull() ?: return@mapNotNull null
        val ids = entry.substringAfter('=').split(',').filter { it.isNotBlank() }.toSet()
        slot.takeIf { it in 0 until 14 }?.let { it to ids }
    }.toMap()

private fun encodeSlotProfiles(value: Map<Int, Set<String>>): String =
    value.entries
        .filter { it.key in 0 until 14 && it.value.isNotEmpty() }
        .sortedBy { it.key }
        .joinToString(";") { (slot, ids) -> "$slot=${ids.sorted().joinToString(",")}" }

private fun parseIndexSet(value: String?): Set<Int> =
    value.orEmpty().split(',').mapNotNull { it.toIntOrNull() }.filter { it in 0 until 14 }.toSet()

private fun loadList14(value: String?, delimiter: Char, defaultValue: (Int) -> String): List<String> {
    val parsed = value.orEmpty().split(delimiter)
    return if (parsed.size == 14) parsed else List(14, defaultValue)
}

private fun loadIntList14(value: String?): List<Int> {
    val parsed = value.orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    return if (parsed.size == 14) parsed.map { it.coerceIn(0, 8) }
    else List(14) { index -> if (index % 2 == 0) 1 else 2 }
}

private fun menuNamesParity(): Map<String, String> = linkedMapOf(
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
