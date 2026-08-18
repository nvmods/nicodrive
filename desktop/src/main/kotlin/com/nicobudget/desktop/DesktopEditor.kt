package com.nicobudget.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate

object DesktopEditor {
    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${DesktopStore.databaseFile.absolutePath}")

    private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun setValue(ps: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> ps.setObject(index, null)
            is Int -> ps.setLong(index, value.toLong())
            is Long -> ps.setLong(index, value)
            is Float -> ps.setDouble(index, value.toDouble())
            is Double -> ps.setDouble(index, value)
            is Number -> ps.setDouble(index, value.toDouble())
            is Boolean -> ps.setLong(index, if (value) 1 else 0)
            is ByteArray -> ps.setBytes(index, value)
            else -> ps.setString(index, value.toString())
        }
    }

    fun updateRow(table: String, desktopId: Long, overrides: Map<String, Any?>): Boolean {
        val columns = DesktopStore.columns(table).map { it.name }
        val selected = overrides.entries.mapNotNull { (requested, value) ->
            columns.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { it to value }
        }
        if (selected.isEmpty()) return false
        val sql = "UPDATE ${quoteIdent(table)} SET " +
            selected.joinToString(",") { "${quoteIdent(it.first)}=?" } +
            " WHERE \"__desktop_id\"=?"
        return connection().use { db ->
            db.prepareStatement(sql).use { ps ->
                selected.forEachIndexed { index, (_, value) -> setValue(ps, index + 1, value) }
                ps.setLong(selected.size + 1, desktopId)
                ps.executeUpdate() > 0
            }
        }
    }

    fun insertLike(table: String, overrides: Map<String, Any?>): Long {
        require(DesktopStore.tableExists(table)) { "Table inconnue : $table" }
        val columns = DesktopStore.columns(table).map { it.name }
        require(columns.isNotEmpty()) { "Aucune colonne dans $table" }
        val template = DesktopStore.rows(table).lastOrNull()
        val affinities = DesktopStore.columns(table).associate { it.name to it.affinity }
        val values = linkedMapOf<String, Any?>()

        columns.forEach { column ->
            val override = overrides.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }
            val templateValue = template?.values?.entries
                ?.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value
            values[column] = when {
                override != null -> override.value
                column.equals("id", ignoreCase = true) -> nextNumericValue(table, column)
                column.equals("expenseId", ignoreCase = true) -> null
                templateValue != null -> templateValue
                affinities[column].equals("INTEGER", ignoreCase = true) -> 0L
                affinities[column].equals("REAL", ignoreCase = true) -> 0.0
                else -> ""
            }
        }

        val sql = "INSERT INTO ${quoteIdent(table)} " +
            "(${columns.joinToString(",") { quoteIdent(it) }}) VALUES " +
            "(${columns.joinToString(",") { "?" }})"
        return connection().use { db ->
            db.prepareStatement(sql).use { ps ->
                columns.forEachIndexed { index, column -> setValue(ps, index + 1, values[column]) }
                ps.executeUpdate()
            }
            db.createStatement().use { st ->
                st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    private fun nextNumericValue(table: String, column: String): Long {
        if (!DesktopStore.columns(table).any { it.name.equals(column, ignoreCase = true) }) return 1L
        return connection().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(${quoteIdent(column)}),0)+1 FROM ${quoteIdent(table)}").use { rs ->
                    if (rs.next()) rs.getLong(1) else 1L
                }
            }
        }
    }

    fun deleteRow(table: String, desktopId: Long): Boolean = DesktopStore.deleteRow(table, desktopId)

    fun setPreferenceString(prefName: String, key: String, value: String) {
        putPreference(prefName, key, JSONObject().put("type", "string").put("value", value))
    }

    fun setPreferenceStringSet(prefName: String, key: String, value: Set<String>) {
        val array = JSONArray()
        value.sorted().forEach(array::put)
        putPreference(prefName, key, JSONObject().put("type", "stringSet").put("value", array))
    }

    private fun putPreference(prefName: String, key: String, encoded: JSONObject) {
        connection().use { db ->
            db.prepareStatement(
                "INSERT OR REPLACE INTO _nb_preferences(pref_name,pref_key,encoded_json) VALUES(?,?,?)"
            ).use { ps ->
                ps.setString(1, prefName)
                ps.setString(2, key)
                ps.setString(3, encoded.toString())
                ps.executeUpdate()
            }
        }
    }

    fun recomputeCurrentBudget() {
        if (!DesktopStore.tableExists("monthly_budget")) return
        val budget = DesktopStore.rows("monthly_budget").firstOrNull() ?: return
        val start = budget.string("startDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = budget.string("endDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val income = budget.double("monthlyIncome") ?: 0.0
        val fixed = if (DesktopStore.tableExists("fixed_charges")) {
            DesktopStore.rows("fixed_charges").sumOf { it.double("amount") ?: 0.0 }
        } else 0.0
        val spent = if (DesktopStore.tableExists("expenses")) {
            DesktopStore.rows("expenses").sumOf { row ->
                val date = row.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (date != null &&
                    (start == null || !date.isBefore(start)) &&
                    (end == null || date.isBefore(end))) {
                    row.double("amount") ?: 0.0
                } else 0.0
            }
        } else 0.0
        updateRow(
            "monthly_budget",
            budget.desktopId,
            mapOf("disposableLeftover" to (income - fixed - spent))
        )
    }

    fun renameCategory(oldName: String, newName: String) {
        val clean = newName.trim()
        require(clean.isNotBlank()) { "Nom de catégorie vide" }
        connection().use { db ->
            db.autoCommit = false
            try {
                listOf("expenses", "expense_archive").forEach { table ->
                    if (DesktopStore.tableExists(table) &&
                        DesktopStore.columns(table).any { it.name.equals("category", true) }) {
                        db.prepareStatement(
                            "UPDATE ${quoteIdent(table)} SET ${quoteIdent("category") }=? WHERE ${quoteIdent("category") }=?"
                        ).use { ps ->
                            ps.setString(1, clean)
                            ps.setString(2, oldName)
                            ps.executeUpdate()
                        }
                    }
                }
                if (DesktopStore.tableExists("expense_categories")) {
                    val nameColumn = categoryNameColumn("expense_categories")
                    if (nameColumn != null) {
                        db.prepareStatement(
                            "UPDATE ${quoteIdent("expense_categories")} SET ${quoteIdent(nameColumn)}=? WHERE ${quoteIdent(nameColumn)}=?"
                        ).use { ps ->
                            ps.setString(1, clean)
                            ps.setString(2, oldName)
                            ps.executeUpdate()
                        }
                    }
                }
                db.commit()
            } catch (t: Throwable) {
                db.rollback()
                throw t
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun addCategory(name: String) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Nom de catégorie vide" }
        if (!DesktopStore.tableExists("expense_categories")) return
        val col = categoryNameColumn("expense_categories")
            ?: error("Colonne nom de catégorie introuvable")
        insertLike("expense_categories", mapOf(col to clean))
    }

    fun deleteCategory(name: String) {
        val used = listOf("expenses", "expense_archive").any { table ->
            DesktopStore.tableExists(table) && DesktopStore.rows(table).any { it.string("category") == name }
        }
        require(!used) { "Cette catégorie est encore utilisée par des dépenses" }
        if (!DesktopStore.tableExists("expense_categories")) return
        val col = categoryNameColumn("expense_categories") ?: return
        val row = DesktopStore.rows("expense_categories").firstOrNull { it.string(col) == name } ?: return
        DesktopStore.deleteRow("expense_categories", row.desktopId)
    }

    fun categoryNameColumn(table: String = "expense_categories"): String? {
        if (!DesktopStore.tableExists(table)) return null
        val cols = DesktopStore.columns(table).map { it.name }
        return cols.firstOrNull {
            it.equals("name", true) || it.equals("category", true) ||
                it.equals("label", true) || it.equals("title", true)
        } ?: cols.firstOrNull { !it.equals("id", true) }
    }

    fun categoryNames(): List<String> {
        val result = linkedSetOf<String>()
        if (DesktopStore.tableExists("expense_categories")) {
            val col = categoryNameColumn()
            if (col != null) {
                DesktopStore.rows("expense_categories").mapNotNullTo(result) {
                    it.string(col)?.trim()?.takeIf(String::isNotBlank)
                }
            }
        }
        listOf("expenses", "expense_archive").forEach { table ->
            if (DesktopStore.tableExists(table)) {
                DesktopStore.rows(table).mapNotNullTo(result) {
                    it.string("category")?.trim()?.takeIf(String::isNotBlank)
                }
            }
        }
        return result.sorted()
    }
}
