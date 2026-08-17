package com.example.nicobudget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nicobudget.data.model.DriveTopProduct
import com.example.nicobudget.ui.components.eur

private enum class ProductListSort {
    FREQUENCY,
    QUANTITY,
    SPEND,
    NAME
}

@Composable
fun AllProductsDialog(
    products: List<DriveTopProduct>,
    periodLabel: String,
    onDismiss: () -> Unit,
    onProductClick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(ProductListSort.FREQUENCY) }

    val visibleProducts = remember(products, query, sortMode) {
        val filtered = if (query.isBlank()) {
            products
        } else {
            products.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }

        when (sortMode) {
            ProductListSort.FREQUENCY -> filtered.sortedWith(
                compareByDescending<DriveTopProduct> { it.orders }
                    .thenByDescending { it.quantity }
                    .thenBy { it.label.lowercase() }
            )
            ProductListSort.QUANTITY -> filtered.sortedWith(
                compareByDescending<DriveTopProduct> { it.quantity }
                    .thenByDescending { it.orders }
                    .thenBy { it.label.lowercase() }
            )
            ProductListSort.SPEND -> filtered.sortedWith(
                compareByDescending<DriveTopProduct> { it.total }
                    .thenByDescending { it.orders }
                    .thenBy { it.label.lowercase() }
            )
            ProductListSort.NAME -> filtered.sortedBy { it.label.lowercase() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Tous les produits",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            periodLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Rechercher un produit") }
                )

                Spacer(Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = sortMode == ProductListSort.FREQUENCY,
                            onClick = { sortMode = ProductListSort.FREQUENCY },
                            label = { Text("Fréquence") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = sortMode == ProductListSort.QUANTITY,
                            onClick = { sortMode = ProductListSort.QUANTITY },
                            label = { Text("Quantité") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = sortMode == ProductListSort.SPEND,
                            onClick = { sortMode = ProductListSort.SPEND },
                            label = { Text("Dépense") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = sortMode == ProductListSort.NAME,
                            onClick = { sortMode = ProductListSort.NAME },
                            label = { Text("A-Z") }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "${visibleProducts.size} produit(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = visibleProducts,
                        key = { it.label }
                    ) { product ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onProductClick(product.label) },
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    product.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "${product.orders} achat(s) · x${formatQuantityForList(product.quantity)} · ${product.total.eur()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatQuantityForList(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.3f".format(value).trimEnd('0').trimEnd(',').trimEnd('.')
