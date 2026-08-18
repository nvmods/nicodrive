package com.nicobudget.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private val parityEuro = NumberFormat.getCurrencyInstance(Locale.FRANCE)
private fun Double.peur(): String = parityEuro.format(this)

@Composable
internal fun ExpensesParityScreen(model: AppModel) {
    val source = remember(model.revision) { if (DesktopStore.tableExists("expenses")) DesktopStore.rows("expenses").sortedByDescending { it.string("date") } else emptyList() }
    var search by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<DbRow?>(null) }
    var create by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<DbRow?>(null) }
    val visible = remember(source, search) { source.filter { search.isBlank() || it.values.values.joinToString(" ").contains(search, true) } }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dépenses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Création, modification et suppression comme sur le téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { create = true }, enabled = DesktopStore.tableExists("expenses")) { Text("+ Nouvelle dépense") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.width(360.dp))
            Text("${visible.size} opération(s) · ${visible.sumOf { it.double("amount") ?: 0.0 }.peur()}", fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.string("date") ?: "—", modifier = Modifier.width(115.dp), fontWeight = FontWeight.SemiBold)
                        Column(Modifier.weight(1f)) {
                            Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold)
                            row.string("description", "label", "name", "note", "title")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { edit = row }) { Text("Modifier") }
                        TextButton(onClick = { delete = row }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    if (create) ExpenseEditDialog(null, onDismiss = { create = false }) { date, category, amount, description ->
        runCatching {
            DesktopEditor.insertLike("expenses", expenseOverrides(date, category, amount, description))
            DesktopEditor.recomputeCurrentBudget()
        }.onSuccess { model.refresh("Dépense ajoutée sur le PC."); create = false }
            .onFailure { model.fail("Ajout impossible : ${it.message}") }
    }
    edit?.let { row -> ExpenseEditDialog(row, onDismiss = { edit = null }) { date, category, amount, description ->
        runCatching {
            DesktopEditor.updateRow("expenses", row.desktopId, expenseOverrides(date, category, amount, description))
            DesktopEditor.recomputeCurrentBudget()
        }.onSuccess { model.refresh("Dépense modifiée sur le PC."); edit = null }
            .onFailure { model.fail("Modification impossible : ${it.message}") }
    } }
    delete?.let { row ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Supprimer cette dépense ?") }, text = { Text("${row.string("date")} · ${row.string("category")} · ${(row.double("amount") ?: 0.0).peur()}") }, confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopEditor.deleteRow("expenses", row.desktopId); DesktopEditor.recomputeCurrentBudget() }
                    .onSuccess { model.refresh("Dépense supprimée."); delete = null }.onFailure { model.fail("Suppression impossible : ${it.message}") }
            }) { Text("Supprimer") }
        }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Annuler") } })
    }
}

private fun expenseOverrides(date: String, category: String, amount: Double, description: String): Map<String, Any?> = mapOf(
    "date" to date,
    "category" to category,
    "amount" to amount,
    "description" to description,
    "label" to description,
    "note" to description,
    "title" to description
)

@Composable
private fun ExpenseEditDialog(row: DbRow?, onDismiss: () -> Unit, onSave: (String, String, Double, String) -> Unit) {
    var date by remember { mutableStateOf(row?.string("date") ?: LocalDate.now().toString()) }
    var category by remember { mutableStateOf(row?.string("category") ?: DesktopEditor.categoryNames().firstOrNull().orEmpty()) }
    var amount by remember { mutableStateOf(row?.double("amount")?.toString() ?: "") }
    var description by remember { mutableStateOf(row?.string("description", "label", "name", "note", "title").orEmpty()) }
    var categoryOpen by remember { mutableStateOf(false) }
    val cats = remember { DesktopEditor.categoryNames() }
    val parsed = amount.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (row == null) "Nouvelle dépense" else "Modifier la dépense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Date (AAAA-MM-JJ)") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { categoryOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(category.ifBlank { "Choisir une catégorie" }) }
                    DropdownMenu(categoryOpen, { categoryOpen = false }) { cats.forEach { cat -> DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; categoryOpen = false }) } }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Montant") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
            }
        },
        confirmButton = { TextButton(enabled = parsed != null && parsed > 0 && category.isNotBlank() && runCatching { LocalDate.parse(date) }.isSuccess, onClick = { onSave(date, category, parsed!!, description) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun ArchivesParityScreen(model: AppModel) {
    val source = remember(model.revision) { if (DesktopStore.tableExists("expense_archive")) DesktopStore.rows("expense_archive").sortedByDescending { it.string("date") } else emptyList() }
    var search by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<DbRow?>(null) }
    var delete by remember { mutableStateOf<DbRow?>(null) }
    val visible = remember(source, search) { source.filter { search.isBlank() || it.values.values.joinToString(" ").contains(search, true) } }
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Archives", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Les archives sont maintenant modifiables et supprimables individuellement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.width(360.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible, key = { it.desktopId }) { row ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(row.string("category") ?: "Sans catégorie", fontWeight = FontWeight.SemiBold); Text(row.string("date") ?: "—", style = MaterialTheme.typography.bodySmall) }
                    Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { edit = row }) { Text("Modifier") }
                    TextButton(onClick = { delete = row }) { Text("Supprimer") }
                } }
            }
        }
    }
    edit?.let { row -> ExpenseEditDialog(row, { edit = null }) { d,c,a,desc ->
        runCatching { DesktopEditor.updateRow("expense_archive", row.desktopId, expenseOverrides(d,c,a,desc)) }
            .onSuccess { model.refresh("Archive modifiée."); edit = null }.onFailure { model.fail("Modification impossible : ${it.message}") }
    } }
    delete?.let { row -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Supprimer l'archive ?") }, text = { Text("Cette suppression sera incluse dans le prochain backup/sync.") }, confirmButton = { TextButton(onClick = { runCatching { DesktopEditor.deleteRow("expense_archive", row.desktopId) }.onSuccess { model.refresh("Archive supprimée."); delete = null }.onFailure { model.fail("Suppression impossible : ${it.message}") } }) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Annuler") } }) }
}

@Composable
internal fun BudgetManagementScreen(model: AppModel) {
    val budget = remember(model.revision) { if (DesktopStore.tableExists("monthly_budget")) DesktopStore.rows("monthly_budget").firstOrNull() else null }
    var income by remember(model.revision) { mutableStateOf(budget?.double("monthlyIncome")?.toString() ?: "") }
    var start by remember(model.revision) { mutableStateOf(budget?.string("startDate").orEmpty()) }
    var end by remember(model.revision) { mutableStateOf(budget?.string("endDate").orEmpty()) }
    var addCharge by remember { mutableStateOf(false) }
    var addIncome by remember { mutableStateOf(false) }
    var addCategory by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<String?>(null) }
    val charges = remember(model.revision) { if (DesktopStore.tableExists("fixed_charges")) DesktopStore.rows("fixed_charges") else emptyList() }
    val incomes = remember(model.revision) { if (DesktopStore.tableExists("fixed_incomes")) DesktopStore.rows("fixed_incomes") else emptyList() }
    val categories = remember(model.revision) { DesktopEditor.categoryNames() }

    Column(Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Budget & récurrents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Paramètres budgétaires, charges, revenus fixes et catégories éditables depuis Windows.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cycle courant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(income, { income = it }, label = { Text("Revenu mensuel") }, modifier = Modifier.weight(1f))
                OutlinedTextField(start, { start = it }, label = { Text("Début") }, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("Fin") }, modifier = Modifier.weight(1f))
            }
            Button(enabled = budget != null && income.replace(',','.').toDoubleOrNull() != null, onClick = {
                runCatching {
                    DesktopEditor.updateRow("monthly_budget", budget!!.desktopId, mapOf("monthlyIncome" to income.replace(',','.').toDouble(), "startDate" to start, "endDate" to end))
                    DesktopEditor.recomputeCurrentBudget()
                }.onSuccess { model.refresh("Budget courant mis à jour.") }.onFailure { model.fail("Budget impossible à modifier : ${it.message}") }
            }) { Text("Enregistrer le cycle") }
        } }

        RecurringSection("Charges fixes", "fixed_charges", charges, onAdd = { addCharge = true }, model = model)
        RecurringSection("Revenus fixes", "fixed_incomes", incomes, onAdd = { addIncome = true }, model = model)

        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Catégories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Button(onClick = { addCategory = true }) { Text("+ Ajouter") } }
            categories.forEach { cat -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(cat, Modifier.weight(1f)); TextButton(onClick = { renameCategory = cat }) { Text("Renommer") }; TextButton(onClick = { runCatching { DesktopEditor.deleteCategory(cat) }.onSuccess { model.refresh("Catégorie supprimée.") }.onFailure { model.fail(it.message ?: "Suppression impossible") } }) { Text("Supprimer") } } }
        } }
    }

    if (addCharge) RecurringEditDialog("Nouvelle charge fixe", null, { addCharge = false }) { n,a -> runCatching { DesktopEditor.insertLike("fixed_charges", mapOf("name" to n, "label" to n, "amount" to a)); DesktopEditor.recomputeCurrentBudget() }.onSuccess { model.refresh("Charge fixe ajoutée."); addCharge=false }.onFailure { model.fail(it.message ?: "Erreur") } }
    if (addIncome) RecurringEditDialog("Nouveau revenu fixe", null, { addIncome = false }) { n,a -> runCatching { DesktopEditor.insertLike("fixed_incomes", mapOf("name" to n, "label" to n, "amount" to a)) }.onSuccess { model.refresh("Revenu fixe ajouté."); addIncome=false }.onFailure { model.fail(it.message ?: "Erreur") } }
    if (addCategory) NameDialog("Nouvelle catégorie", "", { addCategory=false }) { name -> runCatching { DesktopEditor.addCategory(name) }.onSuccess { model.refresh("Catégorie ajoutée."); addCategory=false }.onFailure { model.fail(it.message ?: "Erreur") } }
    renameCategory?.let { old -> NameDialog("Renommer la catégorie", old, { renameCategory=null }) { new -> runCatching { DesktopEditor.renameCategory(old,new) }.onSuccess { model.refresh("Catégorie renommée."); renameCategory=null }.onFailure { model.fail(it.message ?: "Erreur") } } }
}

@Composable
private fun RecurringSection(title: String, table: String, rows: List<DbRow>, onAdd: () -> Unit, model: AppModel) {
    var edit by remember { mutableStateOf<DbRow?>(null) }; var delete by remember { mutableStateOf<DbRow?>(null) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Button(onClick = onAdd, enabled = DesktopStore.tableExists(table)) { Text("+ Ajouter") } }
        rows.sortedByDescending { it.double("amount") ?: 0.0 }.forEach { row -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(row.string("name","label","title") ?: "Élément", Modifier.weight(1f)); Text((row.double("amount") ?: 0.0).peur(), fontWeight = FontWeight.SemiBold); TextButton(onClick = { edit=row }) { Text("Modifier") }; TextButton(onClick = { delete=row }) { Text("Supprimer") } } }
    } }
    edit?.let { row -> RecurringEditDialog("Modifier", row, { edit=null }) { n,a -> runCatching { DesktopEditor.updateRow(table,row.desktopId,mapOf("name" to n,"label" to n,"title" to n,"amount" to a)); if(table=="fixed_charges") DesktopEditor.recomputeCurrentBudget() }.onSuccess { model.refresh("Élément modifié."); edit=null }.onFailure { model.fail(it.message ?: "Erreur") } } }
    delete?.let { row -> AlertDialog(onDismissRequest={delete=null}, title={Text("Supprimer ?")}, text={Text(row.string("name","label","title") ?: "Cet élément")}, confirmButton={TextButton(onClick={runCatching { DesktopEditor.deleteRow(table,row.desktopId); if(table=="fixed_charges") DesktopEditor.recomputeCurrentBudget() }.onSuccess{model.refresh("Élément supprimé.");delete=null}.onFailure{model.fail(it.message?:"Erreur")}}){Text("Supprimer")}}, dismissButton={TextButton(onClick={delete=null}){Text("Annuler")}}) }
}

@Composable private fun RecurringEditDialog(title:String,row:DbRow?,onDismiss:()->Unit,onSave:(String,Double)->Unit){ var name by remember{mutableStateOf(row?.string("name","label","title").orEmpty())}; var amount by remember{mutableStateOf(row?.double("amount")?.toString().orEmpty())}; val a=amount.replace(',','.').toDoubleOrNull(); AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("Nom")});OutlinedTextField(amount,{amount=it},label={Text("Montant")})}},confirmButton={TextButton(enabled=name.isNotBlank()&&a!=null,onClick={onSave(name.trim(),a!!)}){Text("Enregistrer")}},dismissButton={TextButton(onClick=onDismiss){Text("Annuler")}}) }
@Composable private fun NameDialog(title:String,initial:String,onDismiss:()->Unit,onSave:(String)->Unit){ var value by remember{mutableStateOf(initial)}; AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},label={Text("Nom")})},confirmButton={TextButton(enabled=value.isNotBlank(),onClick={onSave(value.trim())}){Text("Enregistrer")}},dismissButton={TextButton(onClick=onDismiss){Text("Annuler")}}) }

private data class PersonProfile(val id:String,val name:String,val excluded:Set<String>)
private val foodRules = listOf(
    "courgettes" to "Courgettes", "brocoli" to "Brocoli", "haricots_verts" to "Haricots verts", "carottes" to "Carottes", "salade" to "Salade", "tomate" to "Tomate / sauce tomate", "aubergines" to "Aubergines", "epinards" to "Épinards", "poireaux" to "Poireaux", "petits_pois" to "Petits pois", "champignons" to "Champignons", "oignons" to "Oignons", "concombre" to "Concombre", "poivrons" to "Poivrons", "chou_fleur" to "Chou-fleur", "chou" to "Chou", "mais" to "Maïs", "lentilles" to "Lentilles", "frites" to "Frites", "pommes_de_terre" to "Pommes de terre", "riz" to "Riz", "pates" to "Pâtes", "semoule" to "Semoule / couscous", "boeuf" to "Bœuf", "porc" to "Porc / jambon / saucisses", "poulet" to "Poulet / dinde", "poisson" to "Poisson / fruits de mer", "oeufs" to "Œufs", "fromage" to "Fromage"
)

@Composable
internal fun MenusParityScreen(model: AppModel) {
    val pref="drive_menu_planner_v2"
    var plan by remember(model.revision){mutableStateOf(DesktopStore.preferenceString(pref,"plan_v3").orEmpty().split('|').let{if(it.size==14)it else List(14){""}})}
    var servings by remember(model.revision){mutableStateOf(DesktopStore.preferenceString(pref,"servings_v3").orEmpty().split(',').mapNotNull{it.toIntOrNull()}.let{if(it.size==14)it else List(14){i->if(i%2==0)1 else 2}})}
    var excluded by remember(model.revision){mutableStateOf(DesktopStore.preferenceStringSet(pref,"excluded_v3"))}
    var profiles by remember(model.revision){mutableStateOf(parseProfiles(DesktopStore.preferenceString(pref,"profiles_v4").orEmpty()))}
    val names=remember{menuNamesParity()}; var chooseSlot by remember{mutableStateOf<Int?>(null)}; var showExcluded by remember{mutableStateOf(false)}; var showProfiles by remember{mutableStateOf(false)}

    fun persist(){DesktopEditor.setPreferenceString(pref,"plan_v3",plan.joinToString("|"));DesktopEditor.setPreferenceString(pref,"servings_v3",servings.joinToString(","));DesktopEditor.setPreferenceStringSet(pref,"excluded_v3",excluded);DesktopEditor.setPreferenceString(pref,"profiles_v4",encodeProfiles(profiles));model.refresh("Planning enregistré côté PC.")}
    Column(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Menus & courses",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Le planning est maintenant éditable depuis Windows.",color=MaterialTheme.colorScheme.onSurfaceVariant)};OutlinedButton(onClick={showExcluded=true}){Text("Incompatibilités (${excluded.size})")};Spacer(Modifier.width(8.dp));OutlinedButton(onClick={showProfiles=true}){Text("Profils (${profiles.size})")};Spacer(Modifier.width(8.dp));Button(onClick={persist}){Text("Enregistrer")}}
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(7){day->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(LocalDate.now().plusDays(day.toLong()).toString(),fontWeight=FontWeight.Bold);Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){for(offset in 0..1){val idx=day*2+offset;val people=servings[idx];Surface(Modifier.weight(1f),tonalElevation=1.dp,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(if(offset==0)"Midi" else "Soir",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));TextButton(onClick={servings=servings.toMutableList().also{it[idx]=(people-1).coerceIn(0,8)}}){Text("−")};Text(if(people==0)"aucun" else "$people pers");TextButton(onClick={servings=servings.toMutableList().also{it[idx]=(people+1).coerceIn(0,8)}}){Text("+")}};Text(if(people==0)"Pas de repas" else names[plan[idx]]?:"Menu non défini");if(people>0)TextButton(onClick={chooseSlot=idx}){Text("Choisir le repas")}}}}}}}}}
    }
    chooseSlot?.let{idx->AlertDialog(onDismissRequest={chooseSlot=null},title={Text("Choisir le repas")},text={LazyColumn(Modifier.heightIn(max=520.dp)){items(names.entries.toList()){e->TextButton(onClick={plan=plan.toMutableList().also{it[idx]=e.key};chooseSlot=null},modifier=Modifier.fillMaxWidth()){Text(e.value)}}}},confirmButton={})}
    if(showExcluded){var draft by remember(excluded){mutableStateOf(excluded)};AlertDialog(onDismissRequest={showExcluded=false},title={Text("Incompatibilités du foyer")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState())){foodRules.forEach{(id,label)->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(id in draft,{c->draft=if(c)draft+id else draft-id});Text(label)}}}},confirmButton={TextButton(onClick={excluded=draft;showExcluded=false}){Text("Appliquer")}},dismissButton={TextButton(onClick={showExcluded=false}){Text("Annuler")}})}
    if(showProfiles) ProfileManagerDialog(profiles,{showProfiles=false}){profiles=it;showProfiles=false}
}

@Composable private fun ProfileManagerDialog(initial:List<PersonProfile>,onDismiss:()->Unit,onSave:(List<PersonProfile>)->Unit){var draft by remember{mutableStateOf(initial)};var editing by remember{mutableStateOf<Int?>(null)};AlertDialog(onDismissRequest=onDismiss,title={Text("Profils personnes")},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){draft.forEachIndexed{i,p->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.SemiBold);Text("${p.excluded.size} incompatibilité(s)",style=MaterialTheme.typography.bodySmall)};TextButton(onClick={editing=i}){Text("Modifier")};TextButton(onClick={draft=draft.toMutableList().also{it.removeAt(i)}}){Text("Supprimer")}}}};OutlinedButton(onClick={draft=draft+PersonProfile(UUID.randomUUID().toString(),"Personne ${draft.size+1}",emptySet());editing=draft.lastIndex}){Text("+ Ajouter une personne")}}},confirmButton={TextButton(onClick={onSave(draft)}){Text("Enregistrer")}},dismissButton={TextButton(onClick=onDismiss){Text("Annuler")}});editing?.let{i->val p=draft[i];var name by remember(p.id){mutableStateOf(p.name)};var ex by remember(p.id){mutableStateOf(p.excluded)};AlertDialog(onDismissRequest={editing=null},title={Text("Profil")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())){OutlinedTextField(name,{name=it},label={Text("Nom")});foodRules.forEach{(id,label)->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(id in ex,{c->ex=if(c)ex+id else ex-id});Text(label)}}}},confirmButton={TextButton(onClick={draft=draft.toMutableList().also{it[i]=p.copy(name=name.trim().ifBlank{p.name},excluded=ex)};editing=null}){Text("Appliquer")}},dismissButton={TextButton(onClick={editing=null}){Text("Annuler")}})}}}

private fun parseProfiles(encoded:String):List<PersonProfile>=encoded.split("||").mapNotNull{s->if(s.isBlank())null else s.split("::",limit=3).let{p->PersonProfile(p.getOrNull(0).orEmpty(),p.getOrNull(1).orEmpty(),p.getOrNull(2).orEmpty().split(',').filter{it.isNotBlank()}.toSet())}}
private fun encodeProfiles(v:List<PersonProfile>)=v.joinToString("||"){p->"${p.id}::${p.name.replace("::"," ").replace("||"," ")}::${p.excluded.sorted().joinToString(",")}"}
private fun menuNamesParity():Map<String,String> = linkedMapOf(
"poulet_haricots_pdt" to "Poulet, haricots verts & pommes de terre","poulet_riz_courgettes" to "Poulet, riz & courgettes","cordon_puree_carottes" to "Cordon bleu, purée & carottes","nuggets_frites_haricots" to "Nuggets, frites & haricots verts","poulet_pates_brocoli" to "Poulet, pâtes & brocoli","steak_haricots_pdt" to "Steak haché, haricots verts & pommes de terre","burger_frites_salade" to "Burgers maison, frites & salade","boulettes_spaghetti" to "Boulettes de bœuf & spaghetti","bolognaise" to "Spaghetti bolognaise","boeuf_riz_carottes" to "Bœuf, riz & carottes","saucisses_lentilles" to "Saucisses & lentilles","chipolatas_frites_courgettes" to "Chipolatas, frites & courgettes","jambon_coquillettes" to "Jambon, coquillettes & fromage","porc_pdt_carottes" to "Porc, pommes de terre & carottes","croque_salade" to "Croque-monsieur & salade","saumon_riz_brocoli" to "Saumon, riz & brocoli","colin_puree_haricots" to "Colin, purée & haricots verts","thon_pates_tomate" to "Pâtes au thon & tomate","poisson_frites_legumes" to "Poisson, frites & légumes","surimi_riz_salade" to "Salade de riz au surimi","omelette_jambon_salade" to "Omelette jambon-fromage & salade","oeufs_pdt_haricots" to "Œufs, pommes de terre & haricots verts","omelette_legumes" to "Omelette aux légumes","pizza_salade" to "Pizza & salade","quiche_salade" to "Quiche & salade","tarte_poireaux" to "Tarte aux poireaux & salade","lasagnes_salade" to "Lasagnes & salade","ravioli_legumes" to "Ravioli & légumes","gratin_salade" to "Gratin & salade","paella_salade" to "Paella & salade","couscous_legumes" to "Couscous & légumes","gnocchi_tomate_fromage" to "Gnocchi tomate-fromage","pates_fromage_legumes" to "Pâtes, fromage & légumes")
