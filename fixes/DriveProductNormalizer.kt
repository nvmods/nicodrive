package com.example.nicobudget.data.model

import java.text.Normalizer
import java.util.Locale

/**
 * Regroupement conservateur des libellés Leclerc.
 *
 * Le PDF change parfois seulement la casse, les accents, les séparateurs ou un
 * pluriel (ex. "rondelle" / "rondelles"). Ces variations ne doivent pas créer
 * deux produits dans les statistiques. En revanche les chiffres et unités sont
 * conservés dans la clé : un paquet 400 g et un paquet 800 g restent distincts.
 */
object DriveProductNormalizer {

    fun key(label: String): String {
        var text = label
            .replace('œ', 'o').replace("oe", "oe")
            .replace('Œ', 'O')
            .replace('×', 'x')

        text = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.FRANCE)

        // Uniformise les décimales et les multipacks sans supprimer les tailles.
        text = text.replace(Regex("(\\d)\\s*,\\s*(\\d)"), "$1.$2")
        text = text.replace(Regex("\\bx\\s*(\\d+)"), "x$1")
        text = text.replace(Regex("[^a-z0-9%.]+"), " ")

        val tokens = text.trim().split(Regex("\\s+")).map { token ->
            // Singularisation volontairement minimale : uniquement un 's' final
            // sur les mots longs. Les nombres/unités restent intacts.
            if (
                token.length > 4 &&
                token.endsWith('s') &&
                token != "sans" &&
                token.none { it.isDigit() }
            ) token.dropLast(1) else token
        }
        return tokens.joinToString(" ")
    }

    private fun preferredLabel(rows: List<DriveTopProduct>): String =
        rows.sortedWith(
            compareByDescending<DriveTopProduct> { it.orders }
                .thenByDescending { it.total }
                .thenByDescending { it.label.length }
        ).first().label

    fun mergeTopProducts(rows: List<DriveTopProduct>): List<DriveTopProduct> =
        rows.groupBy { key(it.label) }
            .values
            .map { group ->
                DriveTopProduct(
                    label = preferredLabel(group),
                    quantity = group.sumOf { it.quantity },
                    total = group.sumOf { it.total },
                    orders = group.sumOf { it.orders }
                )
            }
            .sortedByDescending { it.total }

    fun mergeMonthly(rows: List<DriveProductMonthlyStat>): List<DriveProductMonthlyStat> =
        rows.groupBy { it.month to key(it.label) }
            .values
            .map { group ->
                val preferred = group.sortedWith(
                    compareByDescending<DriveProductMonthlyStat> { it.orders }
                        .thenByDescending { it.total }
                        .thenByDescending { it.label.length }
                ).first()
                DriveProductMonthlyStat(
                    month = preferred.month,
                    label = preferred.label,
                    quantity = group.sumOf { it.quantity },
                    total = group.sumOf { it.total },
                    orders = group.sumOf { it.orders }
                )
            }
            .sortedWith(compareBy<DriveProductMonthlyStat> { it.month }.thenBy { it.label })

    fun evolutionFor(
        selectedLabel: String,
        rows: List<DriveProductMonthlyStat>
    ): List<DriveProductStat> {
        val selectedKey = key(selectedLabel)
        return mergeMonthly(rows)
            .filter { key(it.label) == selectedKey }
            .map { DriveProductStat(it.month, it.quantity, it.total) }
            .sortedByDescending { it.month }
    }
}
