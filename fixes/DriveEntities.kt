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
    val orderId: String,
    val date: String,
    val time: String?,
    val store: String?,
    val productCount: Int,
    val total: Double,
    val savings: Double,
    val ticketLeclerc: Double,
    val expenseId: Long?
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
    val orderId: Int,
    val section: String?,
    val label: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
)

/** Agrégats pour les écrans de stats. */
data class DriveMonthlyTotal(
    val month: String,
    val orderCount: Int,
    val total: Double,
    val savings: Double,
    val ticketLeclerc: Double,
    val lineTotal: Double
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

/** Produit agrégé pour le classement des produits. */
data class DriveTopProduct(
    val label: String,
    val quantity: Double,
    val total: Double,
    val orders: Int
)

/**
 * Produit agrégé par mois. Sert aux comparaisons de prix à produit identique,
 * à l'indice de panier comparable et aux alertes de hausse de prix.
 */
data class DriveProductMonthlyStat(
    val month: String,
    val label: String,
    val quantity: Double,
    val total: Double,
    val orders: Int
)

/** Rayon agrégé par mois pour analyser la saisonnalité. */
data class DriveSectionMonthlyStat(
    val month: String,
    val category: String,
    val total: Double
)
