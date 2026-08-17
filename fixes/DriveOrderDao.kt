package com.example.nicobudget.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.nicobudget.data.model.*

@Dao
interface DriveOrderDao {

    @Insert
    suspend fun insertOrder(order: DriveOrderEntity): Long

    @Insert
    suspend fun insertLines(lines: List<DriveOrderLineEntity>)

    @Query("SELECT * FROM drive_orders WHERE orderId = :orderId LIMIT 1")
    suspend fun findByOrderId(orderId: String): DriveOrderEntity?

    @Query("SELECT * FROM drive_orders ORDER BY date DESC, id DESC")
    fun getAllOrders(): LiveData<List<DriveOrderEntity>>

    @Query("SELECT * FROM drive_order_lines WHERE orderId = :orderRowId ORDER BY id")
    suspend fun getLines(orderRowId: Int): List<DriveOrderLineEntity>

    @Delete
    suspend fun deleteOrder(order: DriveOrderEntity)

    /**
     * Synthèse mensuelle Drive.
     * total = montant final payé ; lineTotal = somme des lignes reconnues.
     */
    @Query(
        """
        SELECT substr(o.date, 1, 7) AS month,
               COUNT(*)             AS orderCount,
               SUM(o.total)         AS total,
               SUM(o.savings)       AS savings,
               SUM(o.ticketLeclerc) AS ticketLeclerc,
               COALESCE(
                   (
                       SELECT SUM(l.total)
                       FROM drive_order_lines l
                       JOIN drive_orders ol ON ol.id = l.orderId
                       WHERE substr(ol.date, 1, 7) = substr(o.date, 1, 7)
                   ),
                   0.0
               ) AS lineTotal
        FROM drive_orders o
        GROUP BY substr(o.date, 1, 7)
        ORDER BY month DESC
        """
    )
    suspend fun getMonthlyTotals(): List<DriveMonthlyTotal>

    /** Quantités et dépense par mois pour une recherche produit. */
    @Query(
        """
        SELECT substr(o.date, 1, 7) AS month,
               SUM(l.quantity)      AS quantity,
               SUM(l.total)         AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE l.label LIKE '%' || :search || '%'
        GROUP BY month
        ORDER BY month DESC
        """
    )
    suspend fun getProductStats(search: String): List<DriveProductStat>

    @Query("SELECT DISTINCT substr(date, 1, 7) AS month FROM drive_orders ORDER BY month DESC")
    suspend fun getAvailableMonths(): List<String>

    /** Classement produit sur un mois. */
    @Query(
        """
        SELECT l.label                   AS label,
               SUM(l.quantity)           AS quantity,
               SUM(l.total)              AS total,
               COUNT(DISTINCT l.orderId) AS orders
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE substr(o.date, 1, 7) = :month
        GROUP BY l.label
        ORDER BY total DESC
        LIMIT :limit
        """
    )
    suspend fun getTopProducts(month: String, limit: Int): List<DriveTopProduct>

    /** Classement produit sur une année complète. */
    @Query(
        """
        SELECT l.label                   AS label,
               SUM(l.quantity)           AS quantity,
               SUM(l.total)              AS total,
               COUNT(DISTINCT l.orderId) AS orders
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE substr(o.date, 1, 4) = :year
        GROUP BY l.label
        ORDER BY total DESC
        LIMIT :limit
        """
    )
    suspend fun getTopProductsForYear(year: String, limit: Int): List<DriveTopProduct>

    /** Classement produit sur tout l'historique importé. */
    @Query(
        """
        SELECT l.label                   AS label,
               SUM(l.quantity)           AS quantity,
               SUM(l.total)              AS total,
               COUNT(DISTINCT l.orderId) AS orders
        FROM drive_order_lines l
        GROUP BY l.label
        ORDER BY total DESC
        LIMIT :limit
        """
    )
    suspend fun getTopProductsAll(limit: Int): List<DriveTopProduct>

    /** Évolution mensuelle d'un produit précis. */
    @Query(
        """
        SELECT substr(o.date, 1, 7) AS month,
               SUM(l.quantity)      AS quantity,
               SUM(l.total)         AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE l.label = :label
        GROUP BY month
        ORDER BY month DESC
        """
    )
    suspend fun getProductEvolution(label: String): List<DriveProductStat>

    /** Répartition par rayon sur un mois. */
    @Query(
        """
        SELECT COALESCE(l.section, 'Sans rayon') AS category,
               SUM(l.total) AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE substr(o.date, 1, 7) = :month
        GROUP BY COALESCE(l.section, 'Sans rayon')
        ORDER BY total DESC
        """
    )
    suspend fun getSectionTotalsForMonth(month: String): List<CategoryExpenseTotal>

    /** Répartition par rayon sur une année. */
    @Query(
        """
        SELECT COALESCE(l.section, 'Sans rayon') AS category,
               SUM(l.total) AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        WHERE substr(o.date, 1, 4) = :year
        GROUP BY COALESCE(l.section, 'Sans rayon')
        ORDER BY total DESC
        """
    )
    suspend fun getSectionTotalsForYear(year: String): List<CategoryExpenseTotal>

    /** Répartition par rayon sur tout l'historique. */
    @Query(
        """
        SELECT COALESCE(l.section, 'Sans rayon') AS category,
               SUM(l.total) AS total
        FROM drive_order_lines l
        GROUP BY COALESCE(l.section, 'Sans rayon')
        ORDER BY total DESC
        """
    )
    suspend fun getSectionTotalsAll(): List<CategoryExpenseTotal>
}
