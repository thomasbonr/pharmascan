# PharmaScan

Application Android libre pour tenir l'inventaire de son armoire à pharmacie.
On vise le DataMatrix d'une boîte de médicament française : PharmaScan lit le
CIP13, le lot et la péremption, retrouve le nom dans la base publique des
médicaments (BDPM) et l'enregistre dans un historique local.

Pas de compte, pas de publicité, aucun traceur, aucune bibliothèque Google
propriétaire.

## Fonctionnalités

- **Scan rapide** : DataMatrix seul (l'EAN voisin est ignoré), auto-zoom, torche,
  mise au point au toucher, codes inversés sur emballage sombre.
- **Fiche médicament** : nom, forme, quantité et conditions de délivrance.
- **Historique local** : recherche, suppression par balayage, péremptions en
  couleur, partage de la liste. Une boîte scannée hors ligne retrouve son nom
  dès que la BDPM répond.
- **Rappel de péremption** : une notification quotidienne quand une boîte
  approche de sa date, puis quand elle est périmée.
- **Saisie manuelle** quand le code est illisible.
- **Home Assistant (optionnel)** : chaque boîte est ajoutée à une liste de
  tâches, avec la péremption comme échéance. Une file d'attente prend le relais
  hors ligne.

## Installation

Téléchargez l'APK depuis les [releases GitHub](https://github.com/thomasbonr/pharmascan/releases).
Android 7.0 ou plus récent, processeur ARM.

Identifiant : `io.github.thomasbonr.pharmascan`. Empreinte SHA-256 du
certificat de signature :

```
B5:6B:DE:1C:1D:A7:44:4F:87:C9:74:13:B5:B4:BE:6B:79:C4:F0:3E:4F:B0:D0:12:6B:D2:74:52:92:E9:A5:3F
```

## Vie privée

L'historique reste sur le téléphone : la sauvegarde Android est désactivée et
les réglages sont chiffrés. Par défaut, la seule requête réseau envoie le CIP13
à [medicaments-api.giygas.dev](https://medicaments-api.giygas.dev), un service
tiers qui expose la BDPM, pour retrouver le nom du médicament. Home Assistant
ne reçoit rien tant qu'il n'est pas activé.

## Home Assistant

Désactivé par défaut. Pour l'activer :

1. Dans Home Assistant, créez une **Liste de tâches locale** (par exemple
   « Armoire à pharmacie ») et notez son `entity_id` (`todo.armoire_a_pharmacie`).
2. Dans *Réglages → Home Assistant*, saisissez l'adresse de HA et l'`entity_id`,
   activez **Utiliser un webhook** et générez l'identifiant.
3. Copiez l'automatisation YAML proposée par l'application dans Home Assistant.

Le webhook ne permet que l'ajout dans cette liste. Gardez `local_only: true`
dans l'automatisation. Un jeton d'accès longue durée fonctionne aussi, mais il
donne tous les droits du compte qui l'a créé.

## Conseils de scan

Tenez la boîte à 8-12 cm. L'autofocus est déjà en mode continu : inutile de
toucher l'écran sans arrêt. Si un code résiste, *Réglages → Diagnostic*
affiche l'état de l'autofocus et du décodeur pour savoir d'où vient le
problème.

## Compilation

JDK 17 et SDK Android (compileSdk 34).

```sh
./gradlew assembleRelease     # app/build/outputs/apk/release/
./gradlew installDebug        # installe directement sur le téléphone branché
```

Sans fichier `keystore.properties`, l'APK release sort non signé (c'est ce
qu'attend F-Droid). Pour signer vos propres APK, créez ce fichier à la racine
(il n'est pas versionné) :

```properties
storeFile=chemin/vers/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Tests, lancés aussi par la CI à chaque push :

```sh
./gradlew testDebugUnitTest lintRelease
python3 tools/gs1_reference/test_gs1.py
```

## Architecture

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Caméra, mise au point, enchaînement scan → BDPM → historique |
| `scan/` | Lecture zxing-cpp, lectures de secours, auto-zoom |
| `Gs1Parser.kt` | Décodage et validation GS1 (AI 01, 10, 17, 21, clé GTIN) |
| `MedicamentApi.kt` | CIP13 → nom, forme et quantité |
| `HistoryActivity.kt`, `HistoryShare.kt` | Historique et partage |
| `ExpiryReminder.kt`, `ExpiryWorker.kt` | Rappel de péremption (WorkManager) |
| `HomeAssistant.kt` | Envoi (webhook ou jeton) et file d'attente |
| `ui/` | Écrans Compose |

Les textes de la fiche F-Droid sont dans `fastlane/metadata/android/`.

## Licence

[GNU GPLv3](LICENSE). Décodage par [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache 2.0).
