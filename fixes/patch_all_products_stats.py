#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_all_products_stats.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/DriveStatsScreen.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

old_state = '    var byQuantity by remember { mutableStateOf(false) }\n'
new_state = '''    // Le classement par défaut privilégie la fréquence d'achat : c'est plus
    // représentatif de ce qui est réellement consommé que le seul montant.
    var topSortMode by remember { mutableStateOf("FREQUENCY") }
    var showAllProducts by remember { mutableStateOf(false) }
'''
if old_state in text:
    text = text.replace(old_state, new_state, 1)
elif "var topSortMode by remember" not in text:
    raise SystemExit("Etat byQuantity introuvable")

marker = "    // ---------------- Dialogue d'évolution d'un produit ----------------\n"
dialog_block = '''    if (showAllProducts) {
        AllProductsDialog(
            products = topProducts,
            periodLabel = periodLabel,
            onDismiss = { showAllProducts = false },
            onProductClick = { label ->
                showAllProducts = false
                evolutionOf = label
                scope.launch {
                    evolution = viewModel.getDriveProductEvolution(label)
                }
            }
        )
    }

'''
if "AllProductsDialog(" not in text:
    if marker not in text:
        raise SystemExit("Point insertion AllProductsDialog introuvable")
    text = text.replace(marker, dialog_block + marker, 1)

start_marker = "        // ---------------- Top 10 produits ----------------\n"
end_marker = "        // ---------------- Répartition par rayon ----------------\n"
start = text.find(start_marker)
end = text.find(end_marker, start + 1)
if start < 0 or end < 0:
    raise SystemExit("Bloc Top 10 introuvable")

new_top = r'''        // ---------------- Top 10 produits ----------------
        SectionCard(
            Icons.Default.EmojiEvents,
            "Top 10 produits — $periodLabel"
        ) {
            if (topProducts.isEmpty()) {
                EmptyHint("Aucun produit sur cette période.")
            } else {
                Text(
                    "Le classement peut être basé sur la fréquence d'achat, la quantité ou la dépense. " +
                        "La fréquence répond mieux à « qu'est-ce qu'on achète régulièrement ? ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = topSortMode == "FREQUENCY",
                            onClick = { topSortMode = "FREQUENCY" },
                            label = { Text("Fréquence") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = topSortMode == "QUANTITY",
                            onClick = { topSortMode = "QUANTITY" },
                            label = { Text("Quantité") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = topSortMode == "SPEND",
                            onClick = { topSortMode = "SPEND" },
                            label = { Text("Dépense") }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                val ranked = when (topSortMode) {
                    "QUANTITY" -> topProducts.sortedWith(
                        compareByDescending<DriveTopProduct> { it.quantity }
                            .thenByDescending { it.orders }
                    )
                    "SPEND" -> topProducts.sortedWith(
                        compareByDescending<DriveTopProduct> { it.total }
                            .thenByDescending { it.orders }
                    )
                    else -> topProducts.sortedWith(
                        compareByDescending<DriveTopProduct> { it.orders }
                            .thenByDescending { it.quantity }
                            .thenByDescending { it.total }
                    )
                }.take(10)

                val maxValue = when (topSortMode) {
                    "QUANTITY" -> ranked.maxOfOrNull { it.quantity } ?: 0.0
                    "SPEND" -> ranked.maxOfOrNull { it.total } ?: 0.0
                    else -> ranked.maxOfOrNull { it.orders.toDouble() } ?: 0.0
                }

                ranked.forEachIndexed { index, p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                evolutionOf = p.label
                                scope.launch {
                                    evolution = viewModel.getDriveProductEvolution(p.label)
                                }
                            }
                            .padding(vertical = 5.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${index + 1}. ${p.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (topSortMode) {
                                    "QUANTITY" -> "x${formatQuantity(p.quantity)} · ${p.orders} achats"
                                    "SPEND" -> "${p.total.eur()} · ${p.orders} achats"
                                    else -> "${p.orders} achats · x${formatQuantity(p.quantity)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "Dépense cumulée : ${p.total.eur()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                val value = when (topSortMode) {
                                    "QUANTITY" -> p.quantity
                                    "SPEND" -> p.total
                                    else -> p.orders.toDouble()
                                }
                                if (maxValue > 0) (value / maxValue).toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAllProducts = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Voir tous les produits (${topProducts.size})")
                }
            }
        }

'''

text = text[:start] + new_top + text[end:]
target.write_text(text, encoding="utf-8")
print(f"Catalogue complet des produits ajouté : {target}")
