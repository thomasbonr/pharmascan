# PharmaScan

Scanner Android pour armoire à pharmacie : on vise le DataMatrix d'une boîte, l'appli en extrait CIP13, lot et péremption, résout le nom via la base publique des médicaments (BDPM) et l'ajoute à une liste Home Assistant, la péremption servant d'échéance.

Un scan = un geste. Pas de compte, pas de cloud tiers, **100 % logiciel libre** (aucun composant Google propriétaire).

## Fonctionnalités

- **Scan fiable** : lecture du DataMatrix seul (l'EAN voisin est ignoré), confirmation sur deux images, auto-zoom, torche, tap-to-focus. Lectures de secours automatiques (code inversé sur emballage noir, autre binarisation, débruitage).
- **Données enrichies** : nom, forme, quantité et conditions de délivrance (BDPM).
- **GS1 rigoureux** : AI 01, 10, 17, 21, clé de contrôle GTIN vérifiée.
- **Hors ligne** : les scans sont mis en file d'attente puis synchronisés au retour du réseau.
- **Vie privée** : connexion par webhook limité à l'ajout dans la liste, sans accès complet à Home Assistant.
- **Léger** : ~5 Mo (R8, ABI ARM uniquement).

## Compilation

Kotlin / Jetpack Compose, SDK min 24, cible 34, JDK 17.

1. Si Home Assistant est en HTTP, renseignez son adresse exacte dans `app/src/main/res/xml/network_security_config.xml` (inutile en HTTPS).
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   (ou `./gradlew installDebug` pour installer directement).

Le décodage repose sur [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache 2.0).

## Configuration Home Assistant

### Étape 1 : Créer la liste
Dans HA, ajoutez une intégration **Liste de tâches locale** nommée "Armoire à pharmacie". Récupérez son `entity_id` (ex. `todo.armoire_a_pharmacie`).

### Étape 2 : Connecter avec Webhook (Recommandé)
Le webhook limite drastiquement les autorisations aux seules permissions d'écriture dans la liste.

1. Dans les réglages de PharmaScan, activez **Utiliser un webhook** et générez l'identifiant.
2. Saisissez l'`entity_id` dans l'application. 
3. Copiez l'automatisation YAML fournie par l'application dans les automatisations de Home Assistant.

L'automatisation ressemble à ceci :
```yaml
alias: PharmaScan - ajout medicament
triggers:
  - trigger: webhook
    webhook_id: "<identifiant_genere>"
    local_only: true
actions:
  - action: todo.add_item
    target:
      entity_id: todo.armoire_a_pharmacie
    data:
      item: "{{ trigger.json.item }}"
      due_date: "{{ trigger.json.due_date | default(omit) }}"
      description: "{{ trigger.json.description | default('', true) }}"
mode: queued
max: 25
```
> **Attention :** Vérifiez toujours que `local_only: true` est bien préservé dans HA pour empêcher les accès externes.

*Mode Jeton : Il est possible d'utiliser un Jeton d'accès (Token) généré dans HA, mais ce mode est peu sécurisé car il donne accès à tous les privilèges du compte créateur.*

## Utilisation

Approchez la boîte à 8-12 cm et visez le DataMatrix : l'appli scanne et bipe. Ne forcez pas l'autofocus à répétition, il est déjà en mode continu.

### Diagnostic

*Réglages → Diagnostic → Afficher le diagnostic* superpose à l'écran de scan : état de l'autofocus (cherche, net, verrouillé, échec), distance de mise au point, objectif utilisé et méthode/durée de la dernière lecture. Il permet de savoir si le problème vient de la caméra (flou, mauvais verrouillage) ou du décodage.

## Architecture

- `MainActivity.kt` : caméra et stratégie de mise au point (Camera2Interop).
- `scan/` : `ZxingFrameScanner`, cascade de lectures et auto-zoom.
- `diagnostic/` : état caméra/décodeur et son affichage.
- `Gs1Parser.kt` : décodage et validation GS1 (testé).
- `MedicamentApi.kt` : CIP13 → nom et quantité (BDPM).
- `HomeAssistant.kt` : envoi (webhook ou jeton) et file d'attente persistante.
- `ui/` : écrans Compose.

## Licence

[GNU GPLv3](LICENSE).
