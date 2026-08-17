package com.example.nicobudget.drive

import android.content.Context
import android.net.Uri
import com.example.nicobudget.data.db.AppDatabase
import com.example.nicobudget.data.model.DriveOrderEntity
import com.example.nicobudget.data.model.DriveOrderLineEntity
import com.example.nicobudget.data.model.ExpenseCategoryEntity
import com.example.nicobudget.data.model.ExpenseEntity
import com.example.nicobudget.data.model.MonthlyBudgetEntity
import java.time.LocalDate

/**
 * Import des bons Leclerc Drive.
 *
 * La commande et ses lignes sont toujours conservées pour l'historique/statistiques.
 * En revanche une dépense du budget courant n'est créée que si la date du bon
 * appartient au cycle budgétaire actuellement actif. Ainsi un import historique
 * de plusieurs années ne vient plus diminuer le budget disponible aujourd'hui.
 */
object DriveImporter {

    const val DRIVE_CATEGORY = "Courses Drive"

    sealed class Result(val message: String) {
        class Imported(message: String, val orderId: String) : Result(message)
        class Duplicate(val orderId: String) :
            Result("Commande n°$orderId déjà importée.")
        class Failed(message: String) : Result(message)
    }

    data class ReconcileResult(
        val removedHistoricalExpenses: Int,
        val restoredCurrentExpenses: Int,
        val clearedStaleLinks: Int
    )

    private fun belongsToActiveBudgetCycle(
        orderDate: String,
        budget: MonthlyBudgetEntity?
    ): Boolean {
        if (budget == null) return false
        return try {
            val date = LocalDate.parse(orderDate)
            val start = LocalDate.parse(budget.startDate)
            val end = LocalDate.parse(budget.endDate)
            val today = LocalDate.now()

            // On ne rattache une commande au budget que si ce budget est bien
            // le cycle actif aujourd'hui et si la commande se situe dans ce cycle.
            val budgetIsActive = !today.isBefore(start) && today.isBefore(end)
            budgetIsActive && !date.isBefore(start) && date.isBefore(end)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureDriveCategory(context: Context) {
        val db = AppDatabase.getDatabase(context)
        if (db.expenseCategoryDao().findByName(DRIVE_CATEGORY) == null) {
            db.expenseCategoryDao().insertCategory(
                ExpenseCategoryEntity(name = DRIVE_CATEGORY)
            )
        }
    }

    /**
     * Répare les données créées par les anciennes versions : auparavant chaque
     * bon historique créait une ExpenseEntity et updateDisposableLeftover()
     * soustrayait la totalité de la table expenses du budget actuel.
     *
     * Cette méthode :
     * - supprime du budget courant les dépenses Drive hors cycle ;
     * - nettoie les liens expenseId devenus obsolètes ;
     * - recrée si nécessaire la dépense d'une commande appartenant vraiment au
     *   cycle actif ;
     * - recalcule ensuite le restant disponible.
     */
    suspend fun reconcileCurrentBudget(context: Context): ReconcileResult {
        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val budget = db.monthlyBudgetDao().getBudgetById()
        val orders = db.driveOrderDao().getAllOrdersNow()
        val expensesById = db.expenseDao().getAllExpensesNow()
            .associateBy { it.id.toLong() }
            .toMutableMap()

        var removed = 0
        var restored = 0
        var stale = 0
        var categoryEnsured = false

        for (order in orders) {
            val shouldCount = belongsToActiveBudgetCycle(order.date, budget)
            val linkedId = order.expenseId
            val linkedExpense = linkedId?.let { expensesById[it] }
            val validDriveExpense = linkedExpense?.category == DRIVE_CATEGORY

            if (!shouldCount) {
                if (linkedId != null) {
                    if (validDriveExpense && linkedExpense != null) {
                        db.expenseDao().deleteExpense(linkedExpense)
                        expensesById.remove(linkedId)
                        removed++
                    }
                    db.driveOrderDao().setExpenseId(order.id, null)
                    stale++
                }
                continue
            }

            if (validDriveExpense) continue

            // Un id peut devenir obsolète après une remise à zéro du budget.
            if (linkedId != null) {
                db.driveOrderDao().setExpenseId(order.id, null)
                stale++
            }

            if (!categoryEnsured) {
                ensureDriveCategory(appContext)
                categoryEnsured = true
            }
            val newExpenseId = db.expenseDao().insertExpense(
                ExpenseEntity(
                    date = order.date,
                    category = DRIVE_CATEGORY,
                    amount = order.total
                )
            )
            db.driveOrderDao().setExpenseId(order.id, newExpenseId)
            restored++
        }

        db.monthlyBudgetDao().updateDisposableLeftover()
        return ReconcileResult(removed, restored, stale)
    }

    suspend fun import(context: Context, uri: Uri): Result {
        return try {
            val appContext = context.applicationContext
            val parsed = LeclercPdfParser.parse(appContext, uri)
            val db = AppDatabase.getDatabase(appContext)

            if (db.driveOrderDao().findByOrderId(parsed.orderId) != null) {
                return Result.Duplicate(parsed.orderId)
            }

            val budget = db.monthlyBudgetDao().getBudgetById()
            val countsTowardCurrentBudget =
                belongsToActiveBudgetCycle(parsed.date, budget)

            val expenseId: Long? = if (countsTowardCurrentBudget) {
                ensureDriveCategory(appContext)
                db.expenseDao().insertExpense(
                    ExpenseEntity(
                        date = parsed.date,
                        category = DRIVE_CATEGORY,
                        amount = parsed.total
                    )
                )
            } else {
                null
            }

            val orderRowId = db.driveOrderDao().insertOrder(
                DriveOrderEntity(
                    orderId = parsed.orderId,
                    date = parsed.date,
                    time = parsed.time,
                    store = parsed.store,
                    productCount = parsed.productCount,
                    total = parsed.total,
                    savings = parsed.savings,
                    ticketLeclerc = parsed.ticketLeclerc,
                    expenseId = expenseId
                )
            )

            db.driveOrderDao().insertLines(
                parsed.lines.map {
                    DriveOrderLineEntity(
                        orderId = orderRowId.toInt(),
                        section = it.section,
                        label = it.label,
                        quantity = it.quantity,
                        unitPrice = it.unitPrice,
                        total = it.total
                    )
                }
            )

            if (countsTowardCurrentBudget) {
                db.monthlyBudgetDao().updateDisposableLeftover()
            }

            val budgetText = if (countsTowardCurrentBudget) {
                " Intégrée au budget courant."
            } else {
                " Conservée dans l'historique, hors budget courant."
            }

            Result.Imported(
                "Commande n°${parsed.orderId} du ${parsed.date} importée : " +
                    "%.2f € (%d produits).".format(parsed.total, parsed.productCount) +
                    budgetText,
                parsed.orderId
            )
        } catch (e: Exception) {
            Result.Failed("Erreur : ${e.message}")
        }
    }
}
