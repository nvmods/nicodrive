package com.nicobudget.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.sql.Connection
import java.sql.DriverManager
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

@Composable
internal fun ClosuresV03Screen(model: AppModel) {
    val budget = remember(model.revision) { closureRows("monthly_budget").firstOrNull() }
    val expenses = remember(model.revision) { closureRows("expenses") }
    val fixedCharges = remember(model.revision) { closureRows("fixed_charges") }
    val fixedIncomes = remember(model.revision) { closureRows("fixed_incomes") }
    var confirmWeek by remember { mutableStateOf(false) }
    var confirmCycle by remember { mutableStateOf(false) }

    val currentWeek = budget?.long("currentWeekIndex")?.toInt() ?: 1
    val totalWeeks = budget?.long("totalWeeks")?.toInt() ?: 5
    val leftover = budget?.double("disposableLeftover") ?: 0.0
    val remainingWeeks = (totalWeeks - currentWeek + 1).coerceAtLeast(1)
    val indicativeWeekBudget = leftover / remainingWeeks
    val variableSpent = expenses.sumOf { it.double("amount") ?: 0.0 }
    val fixedTotal = fixedCharges.sumOf { it.double("amount") ?: 0.0 }
    val incomeTotal = fixedIncomes.sumOf { it.double("amount") ?: 0.0 }

    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Clôtures", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Avancement des semaines et passage au cycle budgétaire suivant.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (budget == null) {
            Card(Modifier.fillMaxWidth()) {
                Text("Aucun budget courant n'est présent dans la base Desktop.", Modifier.padding(16.dp))
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClosureStat("Cycle", "${budget.string("startDate") ?: "—"} → ${budget.string("endDate") ?: "—"}", budget.string("monthYear") ?: "—", Modifier.weight(1f))
            ClosureStat("Semaine", "$currentWeek / $totalWeeks", "$remainingWeeks semaine(s) à financer", Modifier.weight(1f))
            ClosureStat("Reste disponible", moneyClosure(leftover), "budget restant du cycle", Modifier.weight(1f))
            ClosureStat("Budget indicatif", moneyClosure(indicativeWeekBudget), "reste ÷ semaines restantes", Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Clôture hebdomadaire", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Les dépenses déjà saisies ont déjà diminué le reste disponible. La clôture avance simplement l'indice de semaine : le solde positif ou négatif est donc automatiquement réparti sur les semaines restantes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = currentWeek < totalWeeks,
                        onClick = { confirmWeek = true }
                    ) { Text("Clôturer la semaine $currentWeek") }
                    if (currentWeek >= totalWeeks) {
                        Text("Dernière semaine du cycle atteinte.", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("État avant clôture du cycle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ClosureKeyValue("Revenus fixes", moneyClosure(incomeTotal))
                ClosureKeyValue("Charges fixes", moneyClosure(fixedTotal))
                ClosureKeyValue("Dépenses variables à archiver", "${expenses.size} opération(s) · ${moneyClosure(variableSpent)}")
                ClosureKeyValue("Reste du cycle", moneyClosure(leftover))
                HorizontalDivider()
                Text(
                    "La clôture du cycle archive les dépenses variables, détache les anciennes commandes Drive du budget courant puis prépare le cycle suivant à partir de la date de fin actuelle.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { confirmCycle = true }) { Text("Clôturer le cycle et préparer le suivant") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sécurité", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Le passage de cycle est une action explicite. Un .nbbackup peut être exporté depuis « Données & échanges » avant la clôture.")
                Text("Les commandes Drive restent dans leur historique ; seule leur liaison avec la dépense du cycle clôturé est retirée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmWeek) {
        AlertDialog(
            onDismissRequest = { confirmWeek = false },
            title = { Text("Clôturer la semaine $currentWeek ?") },
            text = { Text("La semaine suivante deviendra active. Le reste disponible du cycle est conservé.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopClosureEngine.closeWeek() }
                        .onSuccess { confirmWeek = false; model.refresh("Semaine $currentWeek clôturée. Semaine ${currentWeek + 1} active.") }
                        .onFailure { model.fail("Clôture impossible : ${it.message}") }
                }) { Text("Clôturer") }
            },
            dismissButton = { TextButton(onClick = { confirmWeek = false }) { Text("Annuler") } }
        )
    }

    if (confirmCycle) {
        val nextStart = budget?.string("endDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        AlertDialog(
            onDismissRequest = { confirmCycle = false },
            title = { Text("Clôturer le cycle ?") },
            text = {
                Text(
                    "${expenses.size} dépense(s) seront déplacées dans les archives. " +
                        "Le nouveau cycle commencera le ${nextStart ?: "27 suivant"}. Cette opération sera incluse dans le prochain .nbbackup."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DesktopClosureEngine.closeCycle() }
                        .onSuccess { result ->
                            confirmCycle = false
                            model.refresh("Cycle clôturé : ${result.archived} dépense(s) archivées. Nouveau cycle ${result.start} → ${result.end}.")
                        }
                        .onFailure { model.fail("Clôture du cycle impossible : ${it.message}") }
                }) { Text("Clôturer le cycle") }
            },
            dismissButton = { TextButton(onClick = { confirmCycle = false }) { Text("Annuler") } }
        )
    }
}

private data class CycleClosureResult(val archived: Int, val start: LocalDate, val end: LocalDate)

private object DesktopClosureEngine {
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${DesktopStore.databaseFile.absolutePath}")
    private fun q(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    fun closeWeek() {
        val budget = closureRows("monthly_budget").firstOrNull() ?: error("Budget courant introuvable")
        val current = budget.long("currentWeekIndex")?.toInt() ?: 1
        val total = budget.long("totalWeeks")?.toInt() ?: 5
        require(current < total) { "La dernière semaine est déjà active" }
        DesktopEditor.updateRow("monthly_budget", budget.desktopId, mapOf("currentWeekIndex" to current + 1))
    }

    fun closeCycle(): CycleClosureResult {
        val budget = closureRows("monthly_budget").firstOrNull() ?: error("Budget courant introuvable")
        require(DesktopStore.tableExists("expense_archive")) { "Table d'archives absente" }
        require(DesktopStore.tableExists("expenses")) { "Table des dépenses absente" }

        val currentExpenses = closureRows("expenses")
        val expenseIds = currentExpenses.mapNotNull { it.long("id") }.toSet()
        val now = LocalDate.now().toString()
        val nextStart = budget.string("endDate")
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: currentCycleStart(LocalDate.now()).plusMonths(1)
        val nextEnd = nextStart.plusMonths(1)
        val cycleKey = nextStart.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.FRANCE))
        val totalWeeks = ceil((nextEnd.toEpochDay() - nextStart.toEpochDay()).toDouble() / 7.0).toInt().coerceAtLeast(1)
        val income = closureRows("fixed_incomes").sumOf { it.double("amount") ?: 0.0 }
            .takeIf { it != 0.0 } ?: (budget.double("monthlyIncome") ?: 0.0)
        val fixed = closureRows("fixed_charges").sumOf { it.double("amount") ?: 0.0 }

        connection().use { db ->
            db.autoCommit = false
            try {
                val archiveCols = DesktopStore.columns("expense_archive").map { it.name }
                val hasCategory = archiveCols.any { it.equals("category", true) }
                val hasAmount = archiveCols.any { it.equals("amount", true) }
                val hasDate = archiveCols.any { it.equals("date", true) }
                require(hasCategory && hasAmount && hasDate) { "Schéma d'archive incompatible" }

                val categoryCol = archiveCols.first { it.equals("category", true) }
                val amountCol = archiveCols.first { it.equals("amount", true) }
                val dateCol = archiveCols.first { it.equals("date", true) }
                val archivedAtCol = archiveCols.firstOrNull { it.equals("archivedAt", true) }
                val insertCols = listOfNotNull(categoryCol, amountCol, dateCol, archivedAtCol)
                val sql = "INSERT INTO ${q("expense_archive")} (${insertCols.joinToString(",") { q(it) }}) VALUES (${insertCols.joinToString(",") { "?" }})"
                db.prepareStatement(sql).use { ps ->
                    currentExpenses.forEach { row ->
                        var i = 1
                        ps.setString(i++, row.string("category").orEmpty())
                        ps.setDouble(i++, row.double("amount") ?: 0.0)
                        ps.setString(i++, row.string("date").orEmpty())
                        if (archivedAtCol != null) ps.setString(i++, now)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }

                if (expenseIds.isNotEmpty() && DesktopStore.tableExists("drive_orders")) {
                    val driveCols = DesktopStore.columns("drive_orders").map { it.name }
                    val expenseCol = driveCols.firstOrNull { it.equals("expenseId", true) || it.equals("expense_id", true) }
                    if (expenseCol != null) {
                        val placeholders = expenseIds.joinToString(",") { "?" }
                        db.prepareStatement("UPDATE ${q("drive_orders")} SET ${q(expenseCol)}=NULL WHERE ${q(expenseCol)} IN ($placeholders)").use { ps ->
                            expenseIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                            ps.executeUpdate()
                        }
                    }
                }

                db.createStatement().use { it.executeUpdate("DELETE FROM ${q("expenses")}") }

                val budgetCols = DesktopStore.columns("monthly_budget").map { it.name }
                val updates = linkedMapOf<String, Any?>(
                    "title" to "Budget $cycleKey",
                    "monthYear" to cycleKey,
                    "monthlyIncome" to income,
                    "disposableLeftover" to (income - fixed),
                    "currentWeekIndex" to 1,
                    "totalWeeks" to totalWeeks,
                    "startDate" to nextStart.toString(),
                    "endDate" to nextEnd.toString()
                ).mapNotNull { (wanted, value) -> budgetCols.firstOrNull { it.equals(wanted, true) }?.let { it to value } }
                val updateSql = "UPDATE ${q("monthly_budget")} SET " + updates.joinToString(",") { "${q(it.first)}=?" } + " WHERE ${q("__desktop_id") }=?"
                db.prepareStatement(updateSql).use { ps ->
                    updates.forEachIndexed { index, entry ->
                        val value = entry.second
                        when (value) {
                            is Int -> ps.setInt(index + 1, value)
                            is Long -> ps.setLong(index + 1, value)
                            is Number -> ps.setDouble(index + 1, value.toDouble())
                            else -> ps.setString(index + 1, value?.toString())
                        }
                    }
                    ps.setLong(updates.size + 1, budget.desktopId)
                    ps.executeUpdate()
                }

                db.commit()
            } catch (t: Throwable) {
                db.rollback()
                throw t
            } finally {
                db.autoCommit = true
            }
        }
        return CycleClosureResult(currentExpenses.size, nextStart, nextEnd)
    }

    private fun currentCycleStart(today: LocalDate): LocalDate =
        if (today.dayOfMonth >= 27) today.withDayOfMonth(27) else today.minusMonths(1).withDayOfMonth(27)
}

private fun closureRows(table: String): List<DbRow> = if (DesktopStore.tableExists(table)) DesktopStore.rows(table) else emptyList()

@Composable
private fun ClosureStat(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClosureKeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun moneyClosure(value: Double): String = NumberFormat.getCurrencyInstance(Locale.FRANCE).format(value)
