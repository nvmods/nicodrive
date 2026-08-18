# NicoBudget — architecture PC et synchronisation

## Objectif

Ajouter une version PC sans transformer NicoBudget en application dépendante d'un cloud. Le téléphone et le PC doivent rester utilisables hors ligne, puis se synchroniser quand ils se retrouvent sur le même réseau ou via une liaison explicitement configurée.

## Étape 0 — format de sauvegarde commun

Le format `.nbbackup` est le premier contrat commun entre Android et PC.

Un backup contient :

- `manifest.json` : version du format, date, version de schéma SQLite ;
- `database.json` : tables applicatives et valeurs typées ;
- `preferences.json` : préférences non sensibles utiles à l'utilisateur.

Ne jamais transférer dans ce format : cookies Leclerc, jetons de session, secrets d'authentification, clés Android Keystore.

La version PC pourra importer/exporter ce même format. Cela fournit déjà un moyen de migration et de reprise après panne avant la synchro temps réel.

## Version PC proposée

### Technologie

Préférence : Kotlin/JVM + Compose Desktop.

Raisons :

- partage possible des modèles, moteur de menus, statistiques et format `.nbbackup` avec Android ;
- interface proche de l'application mobile ;
- application Windows autonome ;
- SQLite local côté PC ;
- pas de serveur web ou navigateur obligatoire pour utiliser NicoBudget.

À terme, isoler dans un module Kotlin commun :

- modèles de données et DTO de sync ;
- moteur de menus ;
- classification alimentaire ;
- calculs de statistiques ;
- sérialisation `.nbbackup` ;
- règles de conflits.

Les écrans Android et Desktop restent séparés.

## Principe de synchronisation

Le PC joue le rôle de **hub personnel**. Android reste autonome.

1. Le PC démarre un petit service de synchronisation local.
2. L'utilisateur associe le téléphone avec un QR code / code à usage unique.
3. Une clé de pairage est générée et conservée :
   - Android : Android Keystore ;
   - PC : stockage local protégé.
4. Le téléphone envoie uniquement les changements depuis la dernière synchro.
5. Le PC attribue un numéro de révision global aux changements acceptés.
6. Le téléphone récupère les changements PC qu'il ne possède pas encore.

La découverte automatique mDNS peut être ajoutée plus tard ; le pairage QR doit suffire au départ.

## Pourquoi ne pas synchroniser directement les fichiers SQLite

À éviter absolument :

- copie du fichier DB pendant que Room/SQLite écrit ;
- écrasement complet du téléphone par la base PC ;
- fusion de deux bases via leurs IDs entiers locaux.

Ces approches provoquent tôt ou tard doublons, suppressions perdues ou corruption logique.

## Métadonnées de synchronisation

Pour limiter les migrations du modèle métier existant, utiliser des tables latérales plutôt que d'ajouter immédiatement un UUID dans chaque entité :

### `sync_records`

- `entity_type`
- `local_id`
- `entity_uuid`
- `content_hash`
- `last_server_revision`
- `updated_at`
- `deleted_at`
- `origin_device`

### `sync_devices`

- `device_id`
- `device_name`
- `last_seen`
- `last_server_revision`

### `sync_changes`

- `sequence`
- `entity_type`
- `entity_uuid`
- `operation` (`upsert` / `delete`)
- `payload_json`
- `origin_device`
- `created_at`

## Détection des modifications

Pour la première version, ne pas modifier chaque DAO Android.

Au déclenchement d'une synchro :

1. scanner les tables métier ;
2. construire un hash stable de chaque ligne ;
3. comparer avec `sync_records` ;
4. produire les créations/modifications ;
5. détecter les UUID dont le `local_id` n'existe plus et produire un tombstone de suppression.

Avec quelques milliers de lignes seulement, ce scan est suffisamment léger et évite de fragiliser tous les écrans existants. Plus tard, les DAO peuvent écrire directement dans le journal de changements.

## Identités et doublons

Les IDs entiers SQLite restent locaux.

L'identité synchronisée est `entity_uuid`. Lors de la première synchronisation, toutes les lignes Android existantes reçoivent une correspondance UUID dans `sync_records` sans modifier leur table d'origine.

Ainsi :

- ID Android 42 et ID PC 81 peuvent représenter le même objet ;
- une restauration sur un nouveau téléphone peut reconstruire la correspondance ;
- les créations concurrentes ne peuvent pas entrer en collision.

## Conflits

Le hub PC conserve un `server_revision` monotone.

Quand un appareil envoie une modification, il fournit la dernière révision serveur connue pour cet objet.

- si l'objet n'a pas changé depuis : modification acceptée ;
- s'il a changé sur l'autre appareil : conflit explicite.

Pour NicoBudget, les conflits devraient être rares. Première politique :

- données append-only comme commandes Drive : fusion automatique ;
- suppression vs modification : demander confirmation ;
- dépense ou budget modifié des deux côtés : afficher PC / Android / valeurs ;
- préférences de menus : dernière modification acceptée, avec possibilité d'écraser manuellement.

Éviter un simple « dernier timestamp gagne » car les horloges PC/téléphone peuvent diverger.

## Transport et sécurité

Le service PC peut écouter uniquement sur le LAN.

Les requêtes utilisent :

- clé de pairage 256 bits ;
- nonce unique par message ;
- payload chiffré/authentifié AES-GCM ;
- compteur anti-rejeu ;
- aucun mot de passe Leclerc ou secret externe dans le protocole.

Le chiffrement applicatif évite de dépendre d'une PKI locale pour la première version. HTTPS peut être ajouté ensuite.

## Plan de réalisation

### V1 Desktop

- application Windows ;
- lecture/écriture du `.nbbackup` ;
- dashboard budget ;
- dépenses + archives ;
- stats par catégorie ;
- historique Drive et stats ;
- menus/planning en lecture puis édition.

### V2 Sync LAN

- pairage PC ↔ Android ;
- tables `sync_*` ;
- snapshot initial ;
- synchronisation différentielle ;
- suppressions avec tombstones ;
- écran « dernière synchro / appareil associé ».

### V3

- résolution de conflits ;
- découverte mDNS ;
- sauvegarde automatique sur PC ;
- plusieurs téléphones si besoin ;
- synchro facultative hors LAN via VPN personnel, sans serveur NicoBudget public.

## Règle de conception

Le backup et la synchronisation doivent rester séparés :

- **backup** = photographie complète, restaurable ;
- **sync** = journal de changements entre deux bases actives.

Ne jamais utiliser la synchro comme unique sauvegarde.
