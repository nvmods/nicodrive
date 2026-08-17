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

    @Query("SELECT * FROM drive_orders ORDER BY date DESC, id DESC")
    suspend fun getAllOrdersNow(): List<DriveOrderEntity>

    @Query("UPDATE drive_orders SET expenseId = :expenseId WHERE id = :orderRowId")
    suspend fun setExpenseId(orderRowId: Int, expenseId: Long?)

    @Query("SELECT * FROM drive_order_lines WHERE orderId = :orderRowId ORDER BY id")
    suspend fun getLines(orderRowId: Int): List<DriveOrderLineEntity>

    @Delete
    suspend fun deleteOrder(order: DriveOrderEntity)

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

    /**
     * Une seule requête pour obtenir tous les produits agrégés par mois.
     * Cela évite de lancer une requête par mois pour les analyses historiques.
     */
    @Query(
        """
        SELECT substr(o.date, 1, 7)      AS month,
               l.label                    AS label,
               SUM(l.quantity)            AS quantity,
               SUM(l.total)               AS total,
               COUNT(DISTINCT l.orderId)  AS orders
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        GROUP BY substr(o.date, 1, 7), l.label
        ORDER BY month ASC, l.label ASC
        """
    )
    suspend fun getProductMonthlyStatsAll(): List<DriveProductMonthlyStat>

    /** Tous les rayons agrégés par mois pour les calculs de saisonnalité. */
    @Query(
        """
        SELECT substr(o.date, 1, 7) AS month,
               COALESCE(l.section, 'Sans rayon') AS category,
               SUM(l.total) AS total
        FROM drive_order_lines l
        JOIN drive_orders o ON o.id = l.orderId
        GROUP BY substr(o.date, 1, 7), COALESCE(l.section, 'Sans rayon')
        ORDER BY month ASC, total DESC
        """
    )
    suspend fun getSectionMonthlyStatsAll(): List<DriveSectionMonthlyStat>
}
