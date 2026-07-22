package com.example.nicobudget.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.nicobudget.data.model.*
import com.example.nicobudget.drive.DriveMailSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DriveScreen(
    viewModel: BudgetViewModel,
    pendingPdfUri: Uri? = null,
    onPendingConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val orders by viewModel.driveOrdersLiveData.observeAsState(emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var monthlyTotals by remember { mutableStateOf(emptyList<DriveMonthlyTotal>()) }
    var expandedOrderId by remember { mutableStateOf<Int?>(null) }
    var expandedLines by remember { mutableStateOf(emptyList<DriveOrderLineEntity>()) }
    var search by remember { mutableStateOf("") }
    var productStats by remember { mutableStateOf(emptyList<DriveProductStat>()) }
    var orderToDelete by remember { mutableStateOf<DriveOrderEntity?>(null) }
    var showConfig by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    fun refreshTotals() {
        scope.launch { monthlyTotals = viewModel.getDriveMonthlyTotals() }
    }

    LaunchedEffect(orders) { refreshTotals() }

    LaunchedEffect(pendingPdfUri) {
        pendingPdfUri?.let { uri ->
            viewModel.importDrivePdf(uri, context) { status = it }
            onPendingConsumed()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            status = "Aucun fichier sélectionné."
        } else {
            uris.forEach { uri ->
                viewModel.importDrivePdf(uri, context) { msg -> status = msg }
            }
        }
    }

    fun syncFromMail() {
        val config = DriveMailSync.loadConfig(context)
        if (config == null) {
            showConfig = true
            return
        }
        syncing = true
        status = "Connexion à la boîte mail…"
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    DriveMailSync.sync(context, config)
                }
                status = "${report.mailsScanned} mails scannés, " +
                    "${report.leclercMails} Leclerc, " +
                    "${report.linksFound} lien(s), " +
                    "${report.pdfs.size} PDF." +
                    (report.failures.firstOrNull()?.let { "\nÉchec : $it" } ?: "")
                report.pdfs.forEach { file ->
                    viewModel.importDrivePdf(Uri.fromFile(file), context) {
                        status = it
                    }
                }
            } catch (e: Exception) {
                status = "Erreur mail : ${e.message}"
            } finally {
                syncing = false
            }
        }
    }

    if (showConfig) {
        var user by remember {
            mutableStateOf(DriveMailSync.loadConfig(context)?.user ?: "")
        }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showConfig = false },
            title = { Text("Compte SFR Mail") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Identifiants stockés chiffrés sur le téléphone, " +
                            "utilisés uniquement pour lire les mails Leclerc " +
                            "(imap.sfr.fr)."
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("Adresse SFR complète") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        DriveMailSync.saveConfig(
                            context,
                            DriveMailSync.MailConfig(
                                "imap.sfr.fr", user.trim(), password
                            )
                        )
                        showConfig = false
                        syncFromMail()
                    },
                    enabled = user.isNotBlank() && password.isNotBlank()
                ) { Text("Enregistrer et vérifier") }
            },
            dismissButton = {
                TextButton(onClick = { showConfig = false }) { Text("Annuler") }
            }
        )
    }

    orderToDelete?.let { order ->
        AlertDialog(
            onDismissRequest = { orderToDelete = null },
            title = { Text("Supprimer la commande ?") },
            text = {
                Text(
                    "La commande n°${order.orderId} et la dépense budget " +
                        "associée (%.2f €) seront supprimées.".format(order.total)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDriveOrder(order)
                    orderToDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { orderToDelete = null }) { Text("Annuler") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Leclerc Drive", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { showConfig = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Config mail")
                }
            }
        }

        item {
            Button(
                onClick = { syncFromMail() },
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (syncing) "Vérification…" else "Vérifier les commandes (mail)")
            }
        }

        item {
            OutlinedButton(
                onClick = { launcher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Importer des PDF manuellement")
            }
        }

        status?.let { s ->
            item { Text(s, color = MaterialTheme.colorScheme.primary) }
        }

        if (monthlyTotals.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Dépenses Drive par mois", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        monthlyTotals.take(6).forEach { m ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${m.month} (${m.orderCount} cmd)")
                                Text("%.2f € (éco %.2f €)".format(m.total, m.savings))
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Rechercher un produit (ex : eau)") },
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            productStats =
                                if (search.isBlank()) emptyList()
                                else viewModel.getDriveProductStats(search.trim())
                        }
                    }) { Icon(Icons.Default.Search, contentDescription = "Rechercher") }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (productStats.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("« $search » par mois", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        productStats.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(p.month)
                                Text(
                                    "x%s — %.2f €".format(
                                        if (p.quantity % 1.0 == 0.0)
                                            p.quantity.toInt().toString()
                                        else "%.3f".format(p.quantity),
                                        p.total
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Commandes (${orders.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(orders, key = { it.id }) { order ->
            val expanded = expandedOrderId == order.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (expanded) {
                            expandedOrderId = null
                        } else {
                            expandedOrderId = order.id
                            scope.launch {
                                expandedLines = viewModel.getDriveLines(order.id)
                            }
                        }
                    }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "${order.date} — %.2f €".format(order.total),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "n°${order.orderId} · ${order.productCount} produits" +
                                    (order.store?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { orderToDelete = order }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Supprimer",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            Icon(
                                if (expanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        expandedLines.forEach { l ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    l.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "x%s  %.2f €".format(
                                        if (l.quantity % 1.0 == 0.0)
                                            l.quantity.toInt().toString()
                                        else "%.3f".format(l.quantity),
                                        l.total
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (order.savings > 0.0 || order.ticketLeclerc > 0.0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Économies : %.2f € · Ticket E.Leclerc : %.2f €"
                                    .format(order.savings, order.ticketLeclerc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
