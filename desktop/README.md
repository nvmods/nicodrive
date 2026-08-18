# NicoBudget Desktop

Application Windows autonome de NicoBudget, basée sur Kotlin/JVM + Compose Desktop.

## V0.2

La cible est la parité fonctionnelle avec l'application Android : Windows n'est plus seulement un lecteur de backup.

- import d'un `.nbbackup` Android ;
- base SQLite locale Windows ;
- tableau de bord du cycle ;
- création, modification et suppression des dépenses ;
- modification et suppression unitaire des archives ;
- édition du budget courant ;
- gestion des charges fixes et revenus fixes ;
- ajout, renommage et suppression contrôlée des catégories ;
- statistiques par catégorie ;
- historique Leclerc Drive ;
- planning midi/soir éditable ;
- modification du nombre de convives et des repas ;
- incompatibilités alimentaires éditables ;
- profils personnes éditables ;
- export `.nbbackup` réinjectable sur Android ;
- écran préparé pour la future synchronisation LAN.

Les modifications faites sur le PC sont conservées dans sa base locale puis réexportées dans le même format `.nbbackup` qu'Android. Le protocole de synchronisation différentielle remplacera ensuite les échanges manuels de backup sans changer les écrans métier.

## Build Windows

Le workflow produit :

- un installateur MSI ;
- un installateur EXE ;
- une version portable ZIP.

Les distributions embarquent le runtime Java nécessaire : aucun JDK, Android Studio ou environnement de développement n'est requis sur le PC utilisateur.
