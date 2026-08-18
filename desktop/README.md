# NicoBudget Desktop

Application Windows autonome de NicoBudget, basée sur Kotlin/JVM + Compose Desktop.

## V1

- import d'un `.nbbackup` Android ;
- base SQLite locale Windows ;
- tableau de bord du cycle ;
- dépenses et archives ;
- suppression unitaire d'une archive ;
- statistiques par catégorie ;
- historique Leclerc Drive ;
- planning de menus en lecture ;
- export `.nbbackup` réinjectable sur Android ;
- écran préparé pour la future synchronisation LAN.

## Build Windows

Le workflow `Build NicoBudget Desktop` produit :

- un installateur MSI ;
- un installateur EXE ;
- une version portable ZIP.

Les distributions embarquent le runtime Java nécessaire : aucun JDK, Android Studio ou environnement de développement n'est requis sur le PC utilisateur.
