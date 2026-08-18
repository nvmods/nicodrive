package com.example.nicobudget.data.model

import java.text.Normalizer
import java.util.Locale

/** Une ligne Drive enrichie de sa commande et de son mois pour les analyses alimentaires. */
data class DriveFoodAnalysisLine(
    val orderRowId: Int,
    val month: String,
    val section: String,
    val label: String,
    val quantity: Double,
    val total: Double
)

enum class DriveFoodKind {
    CORE,
    MEAL,
    PERIPHERAL,
    NON_FOOD
}

enum class DriveFoodView(val label: String) {
    ALL_FOOD("Tout alimentaire"),
    MEALS("Repas"),
    MAIN_DISHES("Plats principaux")
}

enum class DriveFoodFamily(
    val label: String,
    val kind: DriveFoodKind
) {
    VEGETABLES("Légumes", DriveFoodKind.CORE),
    POTATOES("Pommes de terre / frites", DriveFoodKind.CORE),
    STARCHES("Pâtes / riz / féculents", DriveFoodKind.CORE),
    POULTRY("Poulet / dinde", DriveFoodKind.CORE),
    BEEF("Bœuf / steaks", DriveFoodKind.CORE),
    PORK("Porc / jambon / saucisses", DriveFoodKind.CORE),
    FISH("Poisson / produits de la mer", DriveFoodKind.CORE),
    EGGS("Œufs", DriveFoodKind.CORE),
    PIZZA_QUICHE("Pizza / quiches / tartes salées", DriveFoodKind.CORE),
    READY_MEALS("Plats préparés / traiteur", DriveFoodKind.CORE),
    SANDWICH_SALAD("Sandwichs / salades-repas", DriveFoodKind.CORE),
    OTHER_CORE("Autres plats / accompagnements", DriveFoodKind.CORE),

    FRUIT("Fruits", DriveFoodKind.MEAL),
    BREAD("Pain / boulangerie salée", DriveFoodKind.MEAL),
    DAIRY_CHEESE("Fromages / laitier", DriveFoodKind.MEAL),
    OTHER_MEAL("Autres aliments de repas", DriveFoodKind.MEAL),

    BREAKFAST("Petit-déjeuner / viennoiseries", DriveFoodKind.PERIPHERAL),
    DESSERTS("Desserts / produits sucrés", DriveFoodKind.PERIPHERAL),
    DRINKS("Boissons", DriveFoodKind.PERIPHERAL),
    SNACKS("Apéritif / snacking", DriveFoodKind.PERIPHERAL),
    CONDIMENTS("Condiments / sauces", DriveFoodKind.PERIPHERAL),
    OTHER_FOOD("Autres aliments", DriveFoodKind.PERIPHERAL),

    NON_FOOD("Non alimentaire", DriveFoodKind.NON_FOOD);

    fun includedIn(view: DriveFoodView): Boolean = when (view) {
        DriveFoodView.ALL_FOOD -> kind != DriveFoodKind.NON_FOOD
        DriveFoodView.MEALS -> kind == DriveFoodKind.CORE || kind == DriveFoodKind.MEAL
        DriveFoodView.MAIN_DISHES -> kind == DriveFoodKind.CORE
    }
}

data class DriveFoodProductSummary(
    val key: String,
    val label: String,
    val section: String,
    val family: DriveFoodFamily,
    val orders: Int,
    val quantity: Double,
    val total: Double
)

data class DriveFoodFamilySummary(
    val family: DriveFoodFamily,
    val orders: Int,
    val quantity: Double,
    val total: Double,
    val products: List<DriveFoodProductSummary>
)

/**
 * Classificateur local volontairement explicable.
 *
 * Les sections Leclerc donnent un premier indice, puis le libellé affine le rôle
 * réel du produit. On préfère quelques faux "Autres" à un rapprochement agressif
 * qui ferait passer une boisson, un dessert ou un produit ménager pour un repas.
 */
object DriveFoodClassifier {

    fun classify(
        line: DriveFoodAnalysisLine,
        override: DriveFoodFamily? = null
    ): DriveFoodFamily {
        override?.let { return it }

        // Ici on ne réutilise volontairement pas DriveProductNormalizer.key() :
        // ce dernier singularise quelques mots pour regrouper des références,
        // alors que le classificateur a besoin de conserver les libellés/rayons
        // tels qu'ils sont écrits ("fruits légumes", "boissons", "frites", etc.).
        val section = classificationKey(line.section)
        val label = classificationKey(line.label)

        if (
            section.containsAny(
                "animalerie", "hygiene beaute", "entretien nettoyage",
                "maison loisirs", "maison textile", "bebe"
            )
        ) return DriveFoodFamily.NON_FOOD

        if (
            label.containsAny(
                "papier toilette", "essuie tout", "mouchoir", "serviette hygienique",
                "gel lavant", "deodorant", "savon", "shampoo", "dentifrice",
                "liquide vaisselle", "lave vaisselle", "sac poubelle", "nettoyant",
                "lessive", "litiere", "patee chat", "barquette chat", "barquette chien",
                "drynites", "alese", "lingette bebe", "serum physiologique",
                "terreau", "feutre", "crayon", "papier cuisson", "gobelet", "assiette carton"
            )
        ) return DriveFoodFamily.NON_FOOD

        if (
            section == "boissons" ||
            label.containsAny(
                "soda", "eau gazeuse", "eau minerale", "jus de", "pur jus",
                "boisson vegetale", "lait amande", "lait d amande", "sirop",
                "vin ", "biere", "mousseux", "kriter", "limonade", "cola"
            )
        ) return DriveFoodFamily.DRINKS

        if (
            label.containsAny(
                "croissant", "pain au chocolat", "brioche", "cereale", "muesli",
                "granola", "petit dej", "dosette", "cafe", "cappuccino",
                "chocolat chaud", "confiture", "pate a tartiner"
            )
        ) return DriveFoodFamily.BREAKFAST

        if (
            section.containsAny("epicerie sucree", "chocolats de noel", "chocolats de paques") ||
            label.containsAny(
                "biscuit", "cookie", "gateau", "compote", "glace", "cone glace",
                "creme dessert", "dessert", "bonbon", "chocolat", "rocher", "mon cheri",
                "flan", "mousse au chocolat", "madeleine", "brownie", "gaufre"
            )
        ) {
            if (label.containsAny("pain de mie", "pain complet")) return DriveFoodFamily.BREAD
            return DriveFoodFamily.DESSERTS
        }

        if (
            label.containsAny(
                "chips", "croustille", "cacahuete", "aperitif", "cracker",
                "gressin", "tortillas chips", "pop corn", "popcorn"
            )
        ) return DriveFoodFamily.SNACKS

        if (
            label.containsAny(
                "mayonnaise", "ketchup", "moutarde", "vinaigrette", "sauce ",
                "huile ", "vinaigre", "sel ", "poivre", "bouillon", "puree de tomate"
            )
        ) return DriveFoodFamily.CONDIMENTS

        if (label.containsAny("pizza", "quiche", "tarte aux poireaux", "tarte salee")) {
            return DriveFoodFamily.PIZZA_QUICHE
        }

        if (
            label.containsAny(
                "sandwich", "salade cie", "salade repas", "salade composee",
                "taboule", "wrap "
            )
        ) return DriveFoodFamily.SANDWICH_SALAD

        if (
            label.containsAny(
                "cannelloni", "lasagne", "ravioli", "hachis", "paella", "gratin",
                "macaroni sauce", "nems ", "croque monsieur", "plat cuisine",
                "boulette de viande", "couscous cuisine"
            )
        ) return DriveFoodFamily.READY_MEALS

        if (
            label.containsAny(
                "poulet", "dinde", "volaille", "cordon bleu", "nugget",
                "escalope de dinde", "filet poulet", "poulet jaune"
            )
        ) return DriveFoodFamily.POULTRY

        if (
            label.containsAny("boeuf", "steak hache", "steak charolais", "viande hachee")
        ) return DriveFoodFamily.BEEF

        if (
            label.containsAny(
                "porc", "jambon", "lardon", "chipolata", "saucisse", "knack",
                "bacon", "chorizo", "roti de porc"
            )
        ) return DriveFoodFamily.PORK

        if (
            label.containsAny(
                "poisson", "colin", "saumon", "thon", "surimi", "crevette", "cabillaud",
                "merlu", "moule", "truite", "sardine", "maquereau"
            )
        ) return DriveFoodFamily.FISH

        if (label.containsAny("oeuf", "oeufs")) return DriveFoodFamily.EGGS

        if (
            label.containsAny(
                "pomme de terre", "pommes de terre", "frites", "rosti",
                "pommes noisettes", "pommes rissolees"
            )
        ) return DriveFoodFamily.POTATOES

        if (
            label.containsAny(
                "pates ", "spaghetti", "torsade", "macaroni", "riz ",
                "semoule", "couscous", "quinoa", "boulgour", "polenta", "gnocchi"
            )
        ) return DriveFoodFamily.STARCHES

        if (
            label.containsAny(
                "banane", "pomme bicolore", "pommes bicolores", "prune", "poire ",
                "fraise", "raisin", "kiwi", "melon", "pasteque", "peche", "nectarine",
                "abricot", "clementine", "mandarine", "orange ", "citron ", "ananas"
            )
        ) return DriveFoodFamily.FRUIT

        if (
            section.contains("fruits legumes") ||
            label.containsAny(
                "tomate", "haricot vert", "laitue", "concombre", "carotte", "courgette",
                "brocoli", "poireau", "legume", "champignon", "aubergine", "epinard",
                "petit pois", "chou ", "oignon", "mais "
            )
        ) return DriveFoodFamily.VEGETABLES

        if (
            label.containsAny(
                "fromage", "emmental", "mozzarella", "camembert", "chevre", "comte",
                "beurre", "yaourt", "creme fraiche", "lait entier", "lait demi"
            ) || section.contains("laitier oeufs vegetal")
        ) return DriveFoodFamily.DAIRY_CHEESE

        if (
            section.contains("pains patisseries") ||
            label.containsAny("baguette", "pain burger", "pain hot dog", "pain de mie", "pain precuit")
        ) return DriveFoodFamily.BREAD

        if (section.contains("viandes poissons")) return DriveFoodFamily.OTHER_CORE
        if (section.contains("charcuterie traiteur")) return DriveFoodFamily.READY_MEALS
        if (section.contains("surgeles")) return DriveFoodFamily.OTHER_CORE
        if (section.containsAny("epicerie salee", "saveurs du monde", "anti gaspi")) {
            return DriveFoodFamily.OTHER_MEAL
        }

        return DriveFoodFamily.OTHER_FOOD
    }

    private fun classificationKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.FRANCE)
            .replace('œ', 'o')
            .replace(Regex("[^a-z0-9%]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }
}
