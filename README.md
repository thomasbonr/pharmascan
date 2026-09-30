# PharmaScan

Scanner Android pour armoire à pharmacie. On vise le DataMatrix d'une boîte de médicament, l'appli en extrait le CIP13, le lot et la date de péremption, résout le nom via la base publique des médicaments, et pousse le tout dans une liste Home Assistant avec la péremption comme échéance. 

Un scan = un geste. Pas de saisie, pas de compte, pas de cloud tiers.

## Fonctionnalités

- **Scan automatique** : Détection du DataMatrix (ignore les codes EAN), auto-zoom, torche, tap-to-focus. Supporte les emballages sombres à codes inversés.
- **Enrichissement des données** : Nom, forme, quantité (ex. "8 comprimés") et conditions de délivrance via l'API Médicaments FR (BDPM).
- **Fiabilité GS1** : Décodage complet des identifiants (AI 01, 10, 17, 21), vérification de clé de contrôle GTIN, robustesse.
- **Fonctionnement hors-ligne** : Scans mis en file d'attente locale et synchronisés au retour du réseau. 
- **Sécurité et respect de la vie privée** : Communication par Webhook restreint sans donner les accès totaux à Home Assistant, données privées.

## Installation et Compilation

L'application est développée en Kotlin / Jetpack Compose.
Pour l'installer via Android Studio (SDK min : 24, cible : 34) :

1. Clonez le dépôt et ouvrez-le avec Android Studio.
2. Éditez le fichier `app/src/main/res/xml/network_security_config.xml` pour renseigner l'adresse **exacte** (IP locale) de votre Home Assistant. (Si accès via HTTPS complet, vous pouvez supprimer cette étape).
3. Construisez et installez sur votre téléphone :
   - `./gradlew installFdroidDebug` : variante 100 % logiciel libre (lecture des codes par zxing-cpp) ;
   - `./gradlew installPlayDebug` : variante Google Play (lecture par ML Kit, meilleure détection).

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

## Utilisation et Détection du Focus

- Ouvrez l'application, approchez la boîte à 8-12 cm, ciblez le carré du DataMatrix. L'application scanne et bip.
- Si le scan ne marche pas, **ne forcez pas l'autofocus répétitivement**. L'appli utilise le mode Continu de la caméra (CameraX).
- **Diagnostics de focus** : Disponibles dans les paramètres pour comprendre si la caméra bloque sur la distance, si le contraste manque, ou si la mise au point cherche en boucle.

## Architecture 

- `MainActivity.kt` : Caméra, focus (gestion de Camera2Interop).
- `scan/` : interface `FrameScanner` ; implémentations dans `src/play` (ML Kit) et `src/fdroid` (zxing-cpp).
- `Gs1Parser.kt` : Décodage et validations standards GS1 (éprouvé par tests).
- `MedicamentApi.kt` : Recherche BDPM. Transforme un CIP13 en nom et compte le nombre de comprimés.
- `HomeAssistant.kt` : Interface de transport (Jetons & Webhook). File d'attente persistente.
- Interface gérée en Jetpack Compose (Dossier `ui/`). 

## Licence
PharmaScan est un logiciel libre, distribué sous licence [GNU GPLv3](LICENSE).
Le décodage respecte le format standard GS1-DataMatrix pour les médicaments.

La variante `fdroid` n'utilise que des composants libres. La variante `play` embarque ML Kit (Google), propriétaire, qui n'est pas couvert par cette licence.
