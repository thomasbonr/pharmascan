# PharmaScan

Scanner Android pour armoire à pharmacie. On vise le DataMatrix d'une boîte
de médicament, l'appli en extrait le CIP13, le lot et la date de péremption,
résout le nom via la base publique des médicaments, et pousse le tout dans
une liste Home Assistant avec la péremption comme échéance.

Un scan = un geste. Pas de saisie, pas de compte, pas de cloud tiers.

---

> **État de vérification.** La logique GS1 (parsing des Application
> Identifiers, clé de contrôle GTIN, règles de date) a été portée puis
> validée par **30 tests automatisés qui passent tous** — dont un qui a
> révélé une vraie faille, corrigée depuis (voir §9). Le code Android
> compile (`assembleDebug`) et a été testé en usage réel : le choix du
> capteur pour l'autofocus (§6) et le mapping des champs de l'API
> Médicaments FR (§11) ont depuis été corrigés suite à des retours
> d'usage — sur le second point, la réponse réelle de l'API a été vérifiée
> en direct, pas seulement supposée depuis sa documentation.

## Table des matières

1. [Pourquoi cette approche](#1-pourquoi-cette-approche)
2. [Ce que fait l'appli](#2-ce-que-fait-lappli)
3. [Installation](#3-installation)
4. [Configuration Home Assistant](#4-configuration-home-assistant)
5. [Première utilisation](#5-première-utilisation)
6. [Le problème du focus, et comment il est résolu](#6-le-problème-du-focus-et-comment-il-est-résolu)
7. [Dashboard et automatisations HA](#7-dashboard-et-automatisations-ha)
8. [Architecture du code](#8-architecture-du-code)
9. [Ce que les tests ont trouvé](#9-ce-que-les-tests-ont-trouvé)
10. [Dépannage](#10-dépannage)
11. [Limites connues](#11-limites-connues)

---

## 1. Pourquoi cette approche

Les boîtes de médicaments vendues en France portent depuis la directive
européenne « médicaments falsifiés » un DataMatrix GS1 qui contient déjà,
en dur, tout ce dont on a besoin :

| Application Identifier | Contenu | Exemple |
|---|---|---|
| `01` | GTIN-14 (= `0` + CIP13) | `03400930000000` |
| `17` | Date de péremption AAMMJJ | `271130` |
| `10` | Numéro de lot | `L4A21` |
| `21` | Numéro de série | `XK7291056` |

Autrement dit : **la date de péremption est déjà lisible par machine sur
chaque boîte**. Aucune raison de la retaper à la main, ni de faire confiance
à une appli propriétaire pour la stocker.

Le nom du médicament, lui, n'est pas dans le code — seulement le CIP13. On
le résout via l'API Médicaments FR, qui expose la BDPM officielle du
ministère de la Santé (mise à jour deux fois par jour).

---

## 2. Ce que fait l'appli

**Scan**
- Détection DataMatrix uniquement (ignore le code-barres EAN voisin)
- Autofocus continu, auto-zoom, tap-to-focus, pinch-to-zoom, torche
- Confirmation sur deux lectures identiques avant validation
- Retour haptique + bip distinct pour succès / échec

**Fiabilité des données**
- Parser GS1 complet (AI à longueur fixe et variable, séparateurs FNC1)
- Vérification de la clé de contrôle GTIN — rejette les lectures corrompues
- Jour `00` correctement interprété comme *dernier jour du mois*
- Fenêtre glissante de 50 ans pour déduire le siècle

**Robustesse réseau**
- File d'attente persistée : scanner toute l'armoire hors-ligne fonctionne,
  la synchro se fait au retour de la connexion
- Distinction entre erreur temporaire (on réessaie) et définitive (on
  prévient l'utilisateur)
- Cache local des noms de médicaments — un CIP déjà vu ne redéclenche pas
  de requête

**Ergonomie**
- Tout configurable depuis le téléphone, rien en dur dans le code
- Bouton « Tester la connexion » qui distingue les trois erreurs classiques
- Déduplication : rescanner la même boîte ne crée pas de doublon
- Saisie manuelle du CIP13 quand le code est abîmé
- **Dossier des boîtes déjà scannées** (icône historique, en haut de
  l'écran de scan) : liste persistée sur disque, consultable et cherchable
  par nom ou CIP13 à tout moment — distinct de l'historique de session
  affiché sous le réticule, qui lui est effacé à la fermeture de l'appli

**Sécurité**
- Token HA chiffré via le Keystore Android (`EncryptedSharedPreferences`)
- HTTP en clair autorisé uniquement vers l'adresse locale déclarée
- Aucune donnée envoyée ailleurs que vers ta propre instance HA

---

## 3. Installation

### 3.1 Créer le projet

Android Studio → **New Project** → **Empty Views Activity** → Kotlin,
package `com.tom.pharmascan`, minimum SDK 24.

Puis copier les fichiers fournis en respectant l'arborescence :

```
PharmaScan/
├── build.gradle.kts                        ← racine (plugins + versions)
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/{themes,colors,strings}.xml
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml
        │   └── xml/network_security_config.xml   ← À ADAPTER
        └── java/com/tom/pharmascan/
            ├── MainActivity.kt          caméra, focus, orchestration
            ├── SettingsActivity.kt      écran de réglages
            ├── HistoryActivity.kt       dossier des boîtes déjà scannées
            ├── Gs1Parser.kt             décodage GS1 (testé, 30/30)
            ├── MedicamentApi.kt         CIP13 → nom du médicament
            ├── HomeAssistant.kt         envoi + file d'attente
            ├── Prefs.kt                 réglages chiffrés
            └── ui/
                ├── Theme.kt             palette Material 3
                ├── ScanUiState.kt       état observable
                ├── ScannerScreen.kt     écran de scan (Compose)
                └── ManualEntryDialog.kt saisie manuelle
```

L'interface est entièrement en **Jetpack Compose** : les layouts XML générés
par le template (`activity_main.xml`) peuvent être supprimés.

Le projet n'utilise ni AppCompat ni Material Components XML — uniquement
Compose Material 3, ce qui allège l'arbre de dépendances.

**Point de vigilance Gradle** : `kotlinCompilerExtensionVersion` (dans
`app/build.gradle.kts`) doit correspondre à la version du plugin Kotlin
déclarée à la racine. Ici Kotlin 1.9.24 → extension 1.5.14. Un décalage
produit une erreur de compilation Compose peu explicite.

### 3.2 Adapter la config réseau

Ouvrir `res/xml/network_security_config.xml` et remplacer `192.168.1.42`
par l'adresse **exacte** de ton instance Home Assistant. Les balises
`<domain>` n'acceptent pas de notation CIDR : il faut un nom d'hôte ou une
IP littérale.

Si tu accèdes à HA en HTTPS (reverse proxy), tu peux supprimer ce fichier
et l'attribut `android:networkSecurityConfig` du manifest.

### 3.3 Compiler

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Transférer sur le téléphone et installer (autoriser les sources inconnues).

Pour un APK release signé, générer un keystore une fois :
```bash
keytool -genkey -v -keystore pharmascan.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias pharmascan
```
puis Build → Generate Signed Bundle / APK.

---

## 4. Configuration Home Assistant

### 4.1 Créer la liste

**Paramètres → Appareils et services → Ajouter une intégration → Liste de
tâches locale**, nommée par exemple « Armoire à pharmacie ».

Relever l'`entity_id` généré dans **Outils de développement → États**
(typiquement `todo.armoire_a_pharmacie`).

Cette intégration native gère nativement une échéance par item, ce qui
permet de trier par date de péremption sans intégration custom.

### 4.2 Créer le jeton

Clic sur ton nom en bas de la barre latérale → onglet **Sécurité** → bas de
page → **Créer un jeton**. Le copier immédiatement, il n'est affiché
qu'une fois.

### 4.3 Renseigner dans l'appli

Ouvrir PharmaScan → **Réglages** → saisir URL, jeton, entité →
**Tester la connexion**. Le message doit indiquer `OK`.

| Message | Signification |
|---|---|
| `OK — entité trouvée` | Tout est bon |
| `Injoignable` | URL fausse, HA éteint, ou pas sur le bon réseau/VPN |
| `Token refusé (401)` | Jeton mal copié ou révoqué |
| `L'entité n'existe pas` | Connexion OK, mais mauvais `entity_id` |

---

## 5. Première utilisation

1. Ouvrir l'appli, accepter la permission caméra.
2. Prendre une boîte, repérer le petit carré de points (souvent près du
   code-barres, parfois sur le rabat).
3. Approcher à **8-12 cm**, code dans le cadre vert.
4. Attendre le bip. L'appli enchaîne : parsing → recherche du nom → envoi.
5. Reposer la boîte, prendre la suivante. Pas besoin de toucher l'écran.

Un rythme de 3 à 5 secondes par boîte est réaliste une fois le geste pris.

Le compteur « File » en haut à droite doit rester à 0. S'il monte, HA n'est
pas joignable — ce n'est pas grave, continue de scanner, ça partira tout
seul.

---

## 6. Le problème du focus, et comment il est résolu

C'est **le** point qui fait échouer la plupart des scanners maison, et il
mérite une explication.

### Le calcul

Un DataMatrix pharmaceutique fait 5 à 8 mm de côté pour environ 50 modules
par ligne. ML Kit exige au minimum **2 pixels par module** pour décoder de
façon fiable. Il faut donc au moins ~100 pixels utiles sur le code, nets.

Avec la résolution d'analyse par défaut de CameraX (640×480) et un
téléphone tenu à bout de bras, le code occupe 30 à 40 pixels. Flou par
dessus. Le scan ne marche jamais, et on ne comprend pas pourquoi.

### Les six mesures appliquées

**1. Choix du capteur arrière** *(souvent la cause principale d'un AF qui n'accroche jamais)*
Sur un téléphone à plusieurs caméras arrière, `CameraSelector.DEFAULT_BACK_CAMERA`
pointe presque toujours vers le capteur principal, dont la distance de mise
au point minimale (10-15 cm sur beaucoup d'appareils) est **supérieure** à
la distance de scan visée (8-12 cm) : l'autofocus hunte en continu et
n'accroche jamais, ce qui se ressent comme un AF simplement défaillant.
L'appli interroge `CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE`
de chaque capteur arrière disponible et sélectionne automatiquement celui
qui fait le point le plus près (souvent l'ultra grand-angle), au lieu du
premier de la liste. Voir `MainActivity.pickBestBackCameraSelector()`.

**2. Résolution d'analyse relevée à 1920×1080**
`setTargetResolution(Size(1920, 1080))` sur l'`ImageAnalysis`. Levier
efficace une fois le bon capteur choisi. Coût : quelques fps d'analyse en
moins, sans conséquence ici.

**3. Autofocus continu forcé via Camera2Interop**
Beaucoup de téléphones utilisent par défaut un AF paresseux qui ne
re-converge pas quand on approche l'objet. On force explicitement
`CONTROL_AF_MODE_CONTINUOUS_PICTURE` sur la requête de capture.

**4. Auto-zoom ML Kit** *(optionnel — Réglages → Zoom automatique)*
`ZoomSuggestionOptions` : quand ML Kit repère un code présent mais trop
petit pour être décodé, il calcule lui-même le facteur de zoom nécessaire
et nous le demande via un callback. Utile si le code est scanné trop loin ;
**ça ne corrige pas un problème de mise au point** (mesure 1), seulement un
cadrage trop petit — désactivable dans les réglages si le changement de
zoom perturbe plus qu'il n'aide sur un appareil donné. Le réticule passe à
l'orange pendant cette phase, pour signaler « je te vois, je zoome ».
Nécessite ML Kit **17.3.0** minimum.

**5. Relance périodique de l'AF**
Si rien n'est décodé pendant 2 secondes, on redéclenche une mise au point
sur une zone large (~60 % du cadre, alignée sur le réticule) plutôt qu'un
point central étroit, et **en AF seul** — sans re-mesurer l'exposition à
chaque relance, ce qui produisait un scintillement de luminosité
perceptible pendant la visée. Sans cette relance, le capteur reste parfois
verrouillé sur l'arrière-plan pendant qu'on approche la boîte, et ne
re-converge jamais.

**6. Tap-to-focus et pinch-to-zoom**
Reprise en main manuelle immédiate. La mise au point manuelle s'auto-annule
au bout de 3 secondes pour repasser en AF continu, sinon la caméra reste
bloquée sur un plan devenu obsolète.

### En complément : confirmation multi-frames

Google recommande explicitement d'attendre plusieurs lectures identiques
consécutives, car un décodage sur image floue peut produire des résultats
différents d'une frame à l'autre. L'appli exige **2 lectures identiques**
(constante `REQUIRED_CONSECUTIVE_READS`). Combiné à la vérification de la
clé de contrôle GTIN, un faux positif est très improbable.

### Si ça coince encore

- **Torche** : les DataMatrix sont souvent imprimés en gris pâle sur carton
  blanc, le contraste est mauvais en lumière ambiante.
- **Ne pas coller la boîte** : sous ~7 cm, la plupart des capteurs
  n'arrivent plus à faire le point du tout — y compris le capteur
  automatiquement sélectionné pour son focus rapproché (mesure 1).
- Monter `REQUIRED_CONSECUTIVE_READS` à 3 si des lectures erronées passent.
- Si l'AF reste mauvais malgré tout sur un appareil donné, vérifier dans
  `adb logcat -s PharmaScan` la ligne `Capteur retenu : id=...` au
  lancement : elle indique la distance de mise au point minimale mesurée
  pour le capteur choisi, utile pour comprendre si le matériel est en cause.

---

## 7. Dashboard et automatisations HA

### Carte simple

```yaml
type: todo-list
entity: todo.armoire_a_pharmacie
```

Les items sont affichés avec leur échéance, donc triables par date de
péremption.

### Automatisation : alerte mensuelle

Envoie une notification ntfy le 1er de chaque mois avec ce qui périme dans
les 90 jours.

```yaml
alias: Pharmacie - alerte péremptions
triggers:
  - trigger: time
    at: "09:00:00"
conditions:
  - condition: template
    value_template: "{{ now().day == 1 }}"
actions:
  - action: todo.get_items
    target:
      entity_id: todo.armoire_a_pharmacie
    data:
      status: needs_action
    response_variable: items
  - variables:
      bientot: >
        {{ items['todo.armoire_a_pharmacie']['items']
           | selectattr('due', 'defined')
           | selectattr('due', '<=', (now() + timedelta(days=90)).strftime('%Y-%m-%d'))
           | map(attribute='summary') | list }}
  - condition: template
    value_template: "{{ bientot | length > 0 }}"
  - action: notify.ntfy
    data:
      title: "{{ bientot | length }} médicament(s) à surveiller"
      message: "{{ bientot | join('\n') }}"
mode: single
```

### Capteur du nombre de médicaments périmés

```yaml
template:
  - sensor:
      - name: "Médicaments périmés"
        unit_of_measurement: "boîtes"
        state: >
          {{ state_attr('todo.armoire_a_pharmacie', 'items') | default([], true)
             | selectattr('due', 'defined')
             | selectattr('due', '<', now().strftime('%Y-%m-%d'))
             | list | count }}
```

Ces trois snippets sont donnés comme point de départ : la syntaxe des
templates HA évolue, à vérifier dans **Outils de développement → Modèle**
avant de les coller dans une automatisation.

---

## 8. Architecture du code

| Fichier | Rôle | Points d'attention |
|---|---|---|
| `MainActivity.kt` | Caméra, focus, orchestration | Stratégie de focus documentée en tête de fichier ; **l'ordre d'initialisation y est critique** (voir §6) |
| `Gs1Parser.kt` | Décodage GS1 | Table des AI, checksum GTIN, dates fin de mois — 30 tests |
| `MedicamentApi.kt` | CIP13 → nom | Échoue en silence par conception, cache local |
| `HomeAssistant.kt` | Envoi + file d'attente | Distingue erreurs temporaires et définitives |
| `Prefs.kt` | Réglages chiffrés | Repli non chiffré si Keystore HS ; stocke aussi le dossier consultable des scans (JSON, non chiffré, non sensible) |
| `SettingsActivity.kt` | Écran de config | Test de connexion diagnostique |
| `HistoryActivity.kt` | Dossier des scans | Persisté sur disque, recherche par nom/CIP13, ne relit pas l'état réel de HA |
| `ui/Theme.kt` | Palette Material 3 | Sombre forcé sur l'écran de scan |
| `ui/ScanUiState.kt` | État observable | Simple `mutableStateOf`, pas de ViewModel |
| `ui/ScannerScreen.kt` | Écran de scan | Réticule animé, historique, badges |
| `ui/ManualEntryDialog.kt` | Saisie manuelle | Valide le CIP13 avec le même checksum que le scan |

### Interface

Compose Material 3, thème sombre sur l'écran de scan (une interface claire
derrière une preview caméra crée des halos qui gênent la visée).

- **Réticule** : cadre à 64 % de la largeur, coins arrondis, légère
  respiration pour signaler que l'appli cherche. **Vert** = prêt,
  **ambre** = code repéré mais illisible (l'auto-zoom travaille),
  **menthe** = traitement en cours.
- **Ligne de balayage** animée pendant la recherche, masquée dès qu'un
  résultat s'affiche.
- **Carte de statut** en bas : icône et couleur avant le texte, pour saisir
  l'état d'un coup d'œil sans lire.
- **Historique de session** scrollable au-dessus : quand on enchaîne vingt
  boîtes, on veut vérifier qu'on n'en a pas raté une sans quitter l'écran.
- **Badge de zoom** au-delà de 1,1× : l'auto-zoom modifie le cadrage sans
  action de l'utilisateur, sans ce repère l'image « saute » sans raison
  apparente.
- **Compteur de session** en haut à gauche, **badge de file d'attente** en
  haut à droite (affiché seulement s'il y a réellement quelque chose en
  attente — un badge à zéro permanent est du bruit visuel).
- **Écran maintenu allumé** pendant une session de scan.
- Icône adaptative vectorielle : croix de pharmacie composée de modules
  carrés, en clin d'œil au DataMatrix.

**Règle de conception transverse** : aucune étape n'est bloquante pour la
suivante. Nom introuvable → on garde le CIP13. HA injoignable → on met en
file. Keystore cassé → prefs en clair avec avertissement. L'appli ne perd
jamais un scan.

**Threading** : la caméra tourne sur son propre executor, le réseau sur un
pool séparé, l'UI est mise à jour via le `mainHandler`. Aucun appel réseau
sur le thread principal.

---

## 9. Ce que les tests ont trouvé

La logique GS1 a été portée hors Android et soumise à 30 cas de test. Deux
enseignements valent d'être connus.

### Faille : un GTIN entièrement à zéro passait la validation

La clé de contrôle GTIN est un modulo 10. Pour `00000000000000`, la somme
pondérée vaut 0, donc la clé attendue vaut 0 — et la chaîne est déclarée
**valide**. Une image bruitée décodée en zéros aurait donc été acceptée et
envoyée dans Home Assistant comme un vrai médicament.

Trois garde-fous ont été ajoutés :

- rejet de tout GTIN composé d'un seul chiffre répété ;
- avertissement si le CIP13 ne commence pas par `3400` (préfixe des
  médicaments français ; les dispositifs médicaux commencent par `3401`) —
  non bloquant, pour ne pas interdire un produit importé ;
- rejet si le lot dépasse 20 caractères, longueur maximale GS1. Un
  dépassement signale presque toujours un séparateur GS manquant : le champ
  a « avalé » l'AI suivante.

En conséquence, le parser distingue désormais les **erreurs bloquantes**
(scan rejeté) des **remarques** (scan accepté, remarque jointe en
description de l'item HA).

### Comportement documenté : lot sans séparateur

Si un DataMatrix encode `01…10ABC12321SERIE1` sans séparateur GS avant
l'AI `21`, le lot avale la fin de la chaîne. C'est **conforme à la norme**
(le GS est obligatoire après un champ variable), et c'est aujourd'hui la
limite de longueur qui rattrape le cas. Comportement vérifié par test, pas
un accident.

### Bug corrigé à la relecture : auto-zoom inopérant

Non détecté par les tests, mais par relecture : dans la première version, le
scanner ML Kit était construit **avant** le binding de la caméra. Or
`maxZoomRatio` n'est connu qu'**après**. `ZoomSuggestionOptions` recevait
donc un plafond de 1.0×, ce qui désactivait silencieusement l'auto-zoom —
c'est-à-dire le mécanisme le plus efficace de toute la stratégie de focus.
L'ordre est désormais : binding → lecture du zoom max → construction du
scanner → branchement de l'analyseur.

Le fichier `MainActivity.kt` porte un avertissement explicite à cet endroit.

---

## 10. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| Rien ne se scanne | Distance ou lumière | Torche, 8-12 cm, cadre vert |
| Réticule orange en continu | Auto-zoom au max, code trop petit | Rapprocher, allumer la torche |
| « Clé de contrôle GTIN invalide » | Lecture partielle | Nettoyer la boîte, meilleure lumière |
| « Code lu mais inexploitable » | Pas un DataMatrix pharma (EAN, QR promo) | Viser le bon carré, ou saisie manuelle |
| Nom générique « Médicament CIP … » | API tierce indisponible | Sans gravité, le CIP13 est en description |
| File qui monte | HA injoignable | Vérifier VPN/réseau, bouton Réessayer |
| Erreur 401 | Jeton invalide | En régénérer un dans HA |
| Boîte refusée « déjà enregistrée » | Déduplication | Réglages → Réinitialiser l'historique |
| Crash au lancement | Dépendance manquante | Vérifier `build.gradle.kts`, resync Gradle |
| Erreur Compose à la compilation | Versions Kotlin / compilateur Compose désaccordées | Aligner `kotlinCompilerExtensionVersion` sur le plugin Kotlin |
| « Code non exploitable : GTIN dégénéré » | Décodage bruité | Torche, meilleur angle |
| Le cadrage bouge tout seul | Auto-zoom ML Kit, comportement normal | Le badge de zoom l'indique |

Logs utiles : `adb logcat -s PharmaScan PharmaScan/HA PharmaScan/API PharmaScan/Prefs`

---

## 11. Limites connues

- **L'API Médicaments FR est un service tiers** (`medicaments-api.giygas.dev`),
  gratuit et sans garantie. Le nom est résolu via `GET /v1/medicaments?cip=`,
  champ `elementPharmaceutique` (vérifié contre l'API en direct, cf.
  `openapi.yaml` du service). **Piège à ne pas réintroduire** : la réponse
  contient aussi un tableau imbriqué `presentation[]` dont le champ
  `libelle` ressemble à un nom mais décrit en réalité le conditionnement
  ("plaquette(s) PVC-Aluminium de 18 comprimé(s)") — l'utiliser comme nom
  affichait le blister à la place du médicament (bug réel, corrigé). En cas
  de disparition du service, l'alternative est de télécharger l'export
  complet de la BDPM (~20 Mo) et de l'embarquer en base locale — plus
  robuste, mais plus lourd.
- **Pas de suppression depuis l'appli.** Retirer un médicament de l'armoire
  se fait côté Home Assistant, en cochant l'item.
- **Pas de gestion des quantités.** Une boîte = un item. Un médicament pris
  quotidiennement n'est pas décompté.
- **Pas de notification locale.** Les alertes de péremption sont
  déléguées à Home Assistant, ce qui est le bon endroit, mais implique
  d'écrire les automatisations de la section 7.
- **Déduplication locale au téléphone.** Elle ne consulte pas l'état réel
  de HA : si tu coches un item côté HA puis rescannes la boîte, l'appli la
  refusera tant que l'historique n'est pas réinitialisé.
- **Pas de mode multi-appareils.** Deux téléphones scannant la même armoire
  ne partagent pas leur historique de déduplication.
- **La logique GS1 est testée par des tests automatisés, le reste du code
  Android l'est par l'usage.** Les 30 tests couvrent le parsing GS1, le
  checksum et les dates. Le reste — caméra, Compose, réseau — n'a pas de
  suite de tests embarquée ; il compile et a été ajusté suite à des retours
  d'usage réel (choix du capteur, mapping API). Reste à surveiller au fil de
  l'usage : le comportement de l'autofocus sur les appareils sans capteur à
  focus rapproché adéquat (voir §6).
- **Pas de tests unitaires embarqués dans le projet.** La validation a été
  faite sur un port de la logique. Si tu veux les rapatrier, `Gs1Parser` est
  un `object` sans dépendance Android : il se teste directement en JUnit,
  sans instrumentation ni émulateur.
