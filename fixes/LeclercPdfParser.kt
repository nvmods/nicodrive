package com.example.nicobudget.drive

import android.content.Context
import android.net.Uri
import com.example.nicobudget.data.model.ParsedDriveLine
import com.example.nicobudget.data.model.ParsedDriveOrder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object LeclercPdfParser {

    private val RE_HEADER = Regex(
        """commande\s*n\s*°?\s*(\d+)\s*du\s*(\d{2}/\d{2}/\d{4})\s*à\s*(\d{1,2})\s*h\s*(\d{2})(?:\s*\(\s*(\d+)\s*produits?\s*\))?""",
        RegexOption.IGNORE_CASE
    )
    private val RE_RAYON = Regex(
        """^([A-ZÀ-ÖØ-Þ0-9'’ \-&]+?)\s*\(\s*(\d+)\s*produits?\s*\)"""
    )
    private val RE_PRODUIT = Regex(
        """^(.+?)\s+(\d+(?:[.,]\d{1,3})?)\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})\s*€?\s*$"""
    )
    private val RE_TOTAL = Regex(
        """Total\s+de\s+la\s+commande\s*:?\s*(\d+[.,]\d{2})""",
        RegexOption.IGNORE_CASE
    )
    private val RE_ECONOMIES = Regex("""économiser\s+(\d+[.,]\d{2})\s*€""")
    private val RE_TICKET = Regex(
        """gagné\s+(\d+[.,]\d{2})\s*€\s*en\s*Ticket""",
        RegexOption.IGNORE_CASE
    )
    private val RE_MAGASIN = Regex("""au drive ([A-ZÀ-ÖØ-Þ\- ']+)""")

    private fun String.eur(): Double = replace(',', '.').toDouble()

    /** Normalise les pièges d'extraction : NBSP, espaces fines, tabulations. */
    private fun normalize(raw: String): String =
        raw.replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\u2009', ' ')
            .replace('\t', ' ')
            .replace(Regex(" {2,}"), " ")

    @Volatile
    private var initialized = false

    private fun ensureInit(context: Context) {
        if (!initialized) synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }

    fun parse(context: Context, uri: Uri): ParsedDriveOrder {
        ensureInit(context)
        val text = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossible d'ouvrir le fichier." }
            PDDocument.load(input).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        }
        return parseText(normalize(text))
    }

    internal fun parseText(text: String): ParsedDriveOrder {
        val header = RE_HEADER.find(text)
            ?: throw IllegalArgumentException(
                "En-tête de commande introuvable. Début du texte extrait : [" +
                    text.take(300).replace('\n', '|') + "]"
            )
        val orderId = header.groupValues[1]
        val (d, m, y) = header.groupValues[2].split("/")

        val lines = mutableListOf<ParsedDriveLine>()
        var section: String? = null
        var inDetail = !text.contains("Détail de votre commande")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.contains("Détail de votre commande") -> inDetail = true
                !inDetail -> {}
                RE_TOTAL.containsMatchIn(line) -> break
                else -> {
                    val rayon = RE_RAYON.find(line)
                    if (rayon != null) {
                        section = rayon.groupValues[1].trim()
                            .lowercase().replaceFirstChar { it.uppercase() }
                    } else {
                        RE_PRODUIT.find(line)?.let { p ->
                            lines += ParsedDriveLine(
                                section = section,
                                label = p.groupValues[1].trim(),
                                quantity = p.groupValues[2].eur(),
                                unitPrice = p.groupValues[3].eur(),
                                total = p.groupValues[4].eur()
                            )
                        }
                    }
                }
            }
        }

        val total = RE_TOTAL.find(text)?.groupValues?.get(1)?.eur()
            ?: throw IllegalArgumentException(
                "Total introuvable. Fin du texte extrait : [" +
                    text.takeLast(300).replace('\n', '|') + "]"
            )

        if (lines.isEmpty()) {
            throw IllegalArgumentException(
                "Aucune ligne produit reconnue. Extrait : [" +
                    text.take(600).replace('\n', '|') + "]"
            )
        }

        return ParsedDriveOrder(
            orderId = orderId,
            date = "$y-$m-$d",
            time = "%02d:%s".format(
                header.groupValues[3].toInt(), header.groupValues[4]
            ),
            store = RE_MAGASIN.find(text)?.groupValues?.get(1)?.trim(),
            productCount = header.groupValues[5].toIntOrNull()
                ?: lines.sumOf { it.quantity }.toInt(),
            total = total,
            savings = RE_ECONOMIES.find(text)?.groupValues?.get(1)?.eur() ?: 0.0,
            ticketLeclerc = RE_TICKET.find(text)?.groupValues?.get(1)?.eur() ?: 0.0,
            lines = lines
        )
    }

    fun isConsistent(order: ParsedDriveOrder): Boolean {
        val sum = order.lines.sumOf { it.total }
        return kotlin.math.abs(sum - order.total) < 0.01
    }
}
