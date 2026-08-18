#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch_food_render_perf.py <project_root>")

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/example/nicobudget/ui/DriveFoodInsights.kt"
if not target.exists():
    raise SystemExit(f"Fichier introuvable: {target}")

text = target.read_text(encoding="utf-8")

# La source fixes/DriveFoodInsights.kt contient désormais directement la version
# optimisée (agrégats préparés hors UI + cache mémoire). Le patch reste dans la
# chaîne pour compatibilité avec les anciens ZIP, mais ne doit surtout pas essayer
# de réécrire une seconde fois cette nouvelle implémentation.
if (
    "private data class PreparedFoodAnalysis" in text
    and "private object FoodAnalysisMemoryCache" in text
    and "prepareFoodAnalysis(" in text
    and "Dispatchers.Default" in text
):
    print(f"Stats alimentaires déjà optimisées : {target}")
    print("- préparation hors thread Compose déjà présente")
    print("- cache mémoire des agrégats déjà présent")
    raise SystemExit(0)

# ---------------------------------------------------------------------------
# Compatibilité avec une ancienne source DriveFoodInsights : calculs de
# classification/regroupement hors du thread Compose.
# ---------------------------------------------------------------------------
if "import kotlinx.coroutines.Dispatchers" not in text:
    anchor = "import com.example.nicobudget.ui.components.eur\n"
    if anchor not in text:
        raise SystemExit("Import anchor DriveFoodInsights introuvable")
    text = text.replace(
        anchor,
        anchor + "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n",
        1,
    )

classified_anchor = '''private data class ClassifiedFoodLine(
    val line: DriveFoodAnalysisLine,
    val key: String,
    val family: DriveFoodFamily
)
'''
if "private data class FoodAnalysisSnapshot" not in text:
    if classified_anchor not in text:
        raise SystemExit("ClassifiedFoodLine introuvable")
    text = text.replace(
        classified_anchor,
        classified_anchor + '''
private data class FoodAnalysisSnapshot(
    val allPeriodOrders: Int,
    val visibleOrders: Int,
    val visibleSpend: Double,
    val visibleProducts: Int,
    val familySummaries: List<DriveFoodFamilySummary>
)
''',
        1,
    )

state_old = '''    var lines by remember { mutableStateOf<List<DriveFoodAnalysisLine>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var foodView by remember { mutableStateOf(DriveFoodView.MAIN_DISHES) }
    var selectedFamily by remember { mutableStateOf<DriveFoodFamily?>(null) }
    var editingProduct by remember { mutableStateOf<DriveFoodProductSummary?>(null) }
    var overrideRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            lines = viewModel.getDriveFoodAnalysisLines()
        } finally {
            loading = false
        }
    }

    val periodLines = remember(lines, selectedScope) {
        lines.filter { line ->
            when {
                selectedScope == "ALL" -> true
                selectedScope.length == 4 -> line.month.startsWith(selectedScope)
                else -> line.month == selectedScope
            }
        }
    }

    val classified = remember(periodLines, foodView, overrideRevision) {
        periodLines.map { line ->
            val key = DriveProductNormalizer.key(line.label)
            val override = prefs.familyOverride(key)
            ClassifiedFoodLine(
                line = line,
                key = key,
                family = DriveFoodClassifier.classify(line, override)
            )
        }
    }

    val allPeriodOrders = remember(periodLines) {
        periodLines.asSequence().map { it.orderRowId }.distinct().count()
    }

    val visibleLines = remember(classified, foodView) {
        classified.filter { it.family.includedIn(foodView) }
    }

    val visibleOrders = remember(visibleLines) {
        visibleLines.asSequence().map { it.line.orderRowId }.distinct().count()
    }

    val familySummaries = remember(visibleLines) {
        buildFamilySummaries(visibleLines)
    }

    val visibleSpend = remember(visibleLines) { visibleLines.sumOf { it.line.total } }
    val visibleProducts = remember(visibleLines) {
        visibleLines.asSequence().map { it.key }.distinct().count()
    }
'''
state_new = '''    var lines by remember { mutableStateOf<List<DriveFoodAnalysisLine>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var classifying by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    var classifiedAll by remember { mutableStateOf<List<ClassifiedFoodLine>>(emptyList()) }
    var snapshotCache by remember { mutableStateOf<Map<String, FoodAnalysisSnapshot>>(emptyMap()) }
    var snapshot by remember { mutableStateOf<FoodAnalysisSnapshot?>(null) }
    var foodView by remember { mutableStateOf(DriveFoodView.MAIN_DISHES) }
    var selectedFamily by remember { mutableStateOf<DriveFoodFamily?>(null) }
    var editingProduct by remember { mutableStateOf<DriveFoodProductSummary?>(null) }
    var overrideRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            lines = viewModel.getDriveFoodAnalysisLines()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(lines, overrideRevision) {
        if (lines.isEmpty()) {
            classifiedAll = emptyList()
            snapshotCache = emptyMap()
            snapshot = null
            return@LaunchedEffect
        }
        classifying = true
        try {
            val overrides = prefs.all.mapNotNull { (key, value) ->
                val family = (value as? String)?.let { stored ->
                    runCatching { DriveFoodFamily.valueOf(stored) }.getOrNull()
                }
                family?.let { key to it }
            }.toMap()
            classifiedAll = withContext(Dispatchers.Default) {
                lines.map { line ->
                    val key = DriveProductNormalizer.key(line.label)
                    ClassifiedFoodLine(
                        line = line,
                        key = key,
                        family = DriveFoodClassifier.classify(line, overrides[key])
                    )
                }
            }
            snapshotCache = emptyMap()
            snapshot = null
        } finally {
            classifying = false
        }
    }

    LaunchedEffect(classifiedAll, selectedScope) {
        if (classifiedAll.isEmpty()) {
            snapshot = null
            return@LaunchedEffect
        }
        val wantedKey = foodSnapshotKey(selectedScope, foodView)
        snapshotCache[wantedKey]?.let {
            snapshot = it
            return@LaunchedEffect
        }
        summarizing = true
        try {
            val prepared = withContext(Dispatchers.Default) {
                DriveFoodView.entries.associate { view ->
                    foodSnapshotKey(selectedScope, view) to
                        buildFoodAnalysisSnapshot(classifiedAll, selectedScope, view)
                }
            }
            snapshotCache = snapshotCache + prepared
            snapshot = prepared[wantedKey]
        } finally {
            summarizing = false
        }
    }

    LaunchedEffect(foodView, selectedScope, snapshotCache) {
        snapshotCache[foodSnapshotKey(selectedScope, foodView)]?.let { snapshot = it }
    }

    val allPeriodOrders = snapshot?.allPeriodOrders ?: 0
    val visibleOrders = snapshot?.visibleOrders ?: 0
    val familySummaries = snapshot?.familySummaries.orEmpty()
    val visibleSpend = snapshot?.visibleSpend ?: 0.0
    val visibleProducts = snapshot?.visibleProducts ?: 0
'''

if state_old in text:
    text = text.replace(state_old, state_new, 1)
elif "var classifiedAll by remember" not in text:
    raise SystemExit("Bloc calcul alimentaire DriveFoodHabits non reconnu")

text = text.replace(
    '''        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@SectionCard
        }
''',
    '''        if (loading || classifying || summarizing || snapshot == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                when {
                    loading -> "Chargement de l'historique Drive…"
                    classifying -> "Classification alimentaire…"
                    else -> "Préparation des statistiques alimentaires…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
''',
    1,
)

helpers_anchor = '''private fun SharedPreferences.familyOverride(key: String): DriveFoodFamily? =
'''
if "private fun buildFoodAnalysisSnapshot(" not in text:
    if helpers_anchor not in text:
        raise SystemExit("Point insertion snapshot alimentaire introuvable")
    helpers = '''private fun foodSnapshotKey(scope: String, view: DriveFoodView): String =
    "$scope:${view.name}"

private fun buildFoodAnalysisSnapshot(
    allLines: List<ClassifiedFoodLine>,
    selectedScope: String,
    foodView: DriveFoodView
): FoodAnalysisSnapshot {
    val periodLines = allLines.filter { row ->
        when {
            selectedScope == "ALL" -> true
            selectedScope.length == 4 -> row.line.month.startsWith(selectedScope)
            else -> row.line.month == selectedScope
        }
    }
    if (periodLines.isEmpty()) {
        return FoodAnalysisSnapshot(0, 0, 0.0, 0, emptyList())
    }

    val allOrders = periodLines.asSequence().map { it.line.orderRowId }.distinct().count()
    val visible = periodLines.filter { it.family.includedIn(foodView) }
    if (visible.isEmpty()) {
        return FoodAnalysisSnapshot(allOrders, 0, 0.0, 0, emptyList())
    }

    return FoodAnalysisSnapshot(
        allPeriodOrders = allOrders,
        visibleOrders = visible.asSequence().map { it.line.orderRowId }.distinct().count(),
        visibleSpend = visible.sumOf { it.line.total },
        visibleProducts = visible.asSequence().map { it.key }.distinct().count(),
        familySummaries = buildFamilySummaries(visible)
    )
}

'''
    text = text.replace(helpers_anchor, helpers + helpers_anchor, 1)

target.write_text(text, encoding="utf-8")
print(f"Stats alimentaires calculées hors UI : {target}")
print("- classification globale en Dispatchers.Default")
print("- snapshots par période et par vue mis en cache")
print("- aucun groupBy massif pendant une recomposition Compose")
