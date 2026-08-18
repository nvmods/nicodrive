# NicoBudget Desktop

Application Windows autonome de NicoBudget, basée sur Kotlin/JVM + Compose Desktop.

## V0.3

La cible est la parité fonctionnelle locale avec l'application Android. La synchronisation automatique est volontairement reportée : le choix du transport (fichier, réseau local ou autre) sera fait séparément sans bloquer les fonctions métier.

### Budget et opérations

- import/export complet `.nbbackup` compatible Android ;
- base SQLite locale Windows ;
- tableau de bord du cycle avec actions rapides et évolution récente ;
- création, modification et suppression des dépenses ;
- modification et suppression des archives ;
- édition du cycle budgétaire ;
- gestion des charges fixes et revenus fixes ;
- ajout, renommage et suppression contrôlée des catégories ;
- recalcul du reste disponible après modifications ;
- import/export CSV des dépenses.

### Statistiques

- périodes Cycle, 12 mois, année, mois et historique complet ;
- totaux, moyenne mensuelle et moyenne par opération ;
- comparaison année courante / précédente ;
- répartition par catégorie avec détail des opérations ;
- évolution mensuelle ;
- classement des plus grosses dépenses ;
- onglets Budget, Leclerc Drive et Produits.

### Leclerc Drive

- historique filtrable par recherche, magasin et année ;
- total, panier moyen, économies et Ticket E.Leclerc ;
- détail complet d'une commande et de ses lignes produit ;
- correction locale de la date, du magasin et des montants ;
- suppression d'une commande avec ses lignes et, au choix, de la dépense budget liée ;
- statistiques par mois, magasin et rayon ;
- catalogue produits avec tri par dépense, quantité, fréquence ou ordre alphabétique ;
- recherche produit/rayon ;
- évolution du prix unitaire d'un produit ;
- export CSV des commandes et des produits.

### Menus & courses

- 14 créneaux midi/soir ;
- nombre de convives par repas et verrouillage ;
- choix manuel ou régénération d'un repas ;
- modes Habitudes, Varié, Économique et Rapide ;
- génération pondérée par l'historique réel des achats Drive ;
- références habituelles et médiane des trois derniers prix observés ;
- estimation du coût des repas ;
- incompatibilités du foyer ;
- profils individuels et affectation des personnes à chaque repas ;
- substitutions limitées aux personnes concernées ;
- liste de courses consolidée sur toute la semaine ;
- quantité arrondie une seule fois après consolidation ;
- total des prix connus et signalement des références génériques ;
- copie de la liste de courses dans le presse-papiers ;
- validation de la semaine pour limiter les répétitions futures.

## Synchronisation

Elle reste volontairement séparée de la V0.3. En attendant, le `.nbbackup` permet de transférer l'état complet entre Android et Windows. Le choix du mécanisme automatique sera traité en dernier afin de ne pas imposer prématurément une solution par fichier ou par réseau.

## Build Windows

Le workflow produit un installateur MSI, un installateur EXE et une version portable ZIP. Les distributions embarquent le runtime Java nécessaire : aucun JDK ni environnement de développement n'est requis sur le PC utilisateur.
