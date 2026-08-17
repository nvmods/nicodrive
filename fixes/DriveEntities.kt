package com.example.nicobudget.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Une commande Leclerc Drive importée depuis son bon de commande PDF.
 * [expenseId] référence la dépense créée dans le budget pour cette commande,
 * ce qui intègre automatiquement le Drive au calcul du restant disponible.
 */
@Entity(
    tableName = "drive_orders",
    indices = [Index(value = ["orderId"], unique = true)]
)
data class DriveOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val orderId: String,          // n° de commande Leclerc (unique)
    val date: String,             // yyyy-MM-dd
    val time: String?,            // HH:mm
    val store: String?,
    val productCount: Int,
    val total: Double,
    val savings: Double,          // économies/remises indiquées par Leclerc
    val ticketLeclerc: Double,    // gains Ticket E.Leclerc
    val expenseId: Long?          // dépense budget liée
)

@Entity(
    tableName = "drive_order_lines",
    foreignKeys = [
        ForeignKey(
            entity = DriveOrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["orderId"]), Index(value = ["label"])]
)
data class DriveOrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val orderId: Int,             // FK -> drive_orders.id
    val section: String?,         // rayon Leclerc (Surgelés, Anti-Gaspi...)
    val label: String,
    val quantity: Double,         // décimal pour les produits au poids
    val unitPrice: Double,
    val total: Double
)

/** Agrégats pour les écrans de stats. */
data class DriveMonthlyTotal(
    val month: String,            // yyyy-MM
    val orderCount: Int,
    val total: Double,            // total réellement payé
    val savings: Double,          // économies/remises immédiates
    val ticketLeclerc: Double,    // Ticket E.Leclerc gagné
    val lineTotal: Double         // somme des lignes produits reconnues
)

data class DriveProductStat(
    val month: String,
    val quantity: Double,
    val total: Double
)

/** Résultat d'un parsing de PDF, avant insertion. */
data class ParsedDriveOrder(
    val orderId: String,
    val date: String,
    val time: String?,
    val store: String?,
    val productCount: Int,
    val total: Double,
    val savings: Double,
    val ticketLeclerc: Double,
    val lines: List<ParsedDriveLine>
)

data class ParsedDriveLine(
    val section: String?,
    val label: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
)

/** Produit agrégé pour le classement "top produits" d'un mois. */
data class DriveTopProduct(
    val label: String,
    val quantity: Double,
    val total: Double,
    val orders: Int
)
