package com.tom.pharmascan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tom.pharmascan.ui.PharmaScanTheme
import com.tom.pharmascan.ui.StatusError
import com.tom.pharmascan.ui.StatusSuccess
import com.tom.pharmascan.ui.StatusWarning
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Réglages.
 *
 * Aucune valeur n'est en dur dans le code : l'APK est réutilisable tel quel
 * sur une autre instance Home Assistant, par quelqu'un d'autre, sans
 * recompiler.
 *
 * Le bouton de test est le point d'ergonomie important : il distingue les
 * échecs classiques (serveur injoignable / secret refusé / entité
 * inexistante) au lieu de laisser l'utilisateur deviner.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ha: HomeAssistant
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ha = HomeAssistant(prefs)

        // Dessin sous les barres système : la preview caméra occupe tout
        // l'écran, et Compose gère les marges via statusBarsPadding().
        enableEdgeToEdge()

        setContent {
            PharmaScanTheme {
                SettingsScreen(
                    initial = SettingsForm(
                        url = prefs.haUrl,
                        token = prefs.haToken,
                        entity = prefs.todoEntity,
                        webhookId = prefs.webhookId,
                        useWebhook = prefs.connectionMode == ConnectionMode.WEBHOOK,
                        sound = prefs.soundEnabled,
                        autoZoom = prefs.autoZoomEnabled,
                        macroLens = prefs.macroLensEnabled,
                        afDiagnostics = prefs.afDiagnosticsEnabled,
                        haEnabled = prefs.haEnabled
                    ),
                    onSave = { form ->
                        prefs.haUrl = form.url
                        prefs.haToken = form.token
                        prefs.todoEntity = form.entity
                        prefs.webhookId = form.webhookId
                        prefs.connectionMode =
                            if (form.useWebhook) ConnectionMode.WEBHOOK else ConnectionMode.TOKEN
                        prefs.soundEnabled = form.sound
                        prefs.autoZoomEnabled = form.autoZoom
                        prefs.macroLensEnabled = form.macroLens
                        prefs.afDiagnosticsEnabled = form.afDiagnostics
                        prefs.haEnabled = form.haEnabled
                    },
                    onTest = { callback ->
                        executor.execute {
                            val message = ha.testConnectionBlocking()
                            runOnUiThread { callback(message) }
                        }
                    },
                    onClearHistory = { prefs.clearScannedHistory() },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}

/**
 * Regroupe l'état du formulaire. Les réglages se sont multipliés : passer
 * neuf paramètres positionnels à `onSave` était devenu illisible et facile à
 * intervertir silencieusement (tous booléens à la fin).
 */
private data class SettingsForm(
    val url: String,
    val token: String,
    val entity: String,
    val webhookId: String,
    val useWebhook: Boolean,
    val sound: Boolean,
    val autoZoom: Boolean,
    val macroLens: Boolean,
    val afDiagnostics: Boolean,
    val haEnabled: Boolean
)

/** Secret du webhook : 32 caractères tirés d'un générateur cryptographique. */
private fun generateWebhookId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    return (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/**
 * L'automatisation à coller dans Home Assistant.
 *
 * Le `choose` n'est pas de la coquetterie : l'appli omet `due_date` pour une
 * boîte sans date de péremption lisible, et `todo.add_item` refuse une date
 * vide. Sans cette distinction, ces boîtes-là échoueraient silencieusement.
 */
private fun buildAutomationYaml(webhookId: String, entity: String): String {
    val id = webhookId.ifBlank { "<génère un identifiant ci-dessus>" }
    val target = entity.ifBlank { "todo.armoire_a_pharmacie" }
    return """
        alias: PharmaScan - ajout medicament
        triggers:
          - trigger: webhook
            webhook_id: "$id"
            local_only: true
        actions:
          - choose:
              - conditions:
                  - condition: template
                    value_template: "{{ trigger.json.due_date is defined }}"
                sequence:
                  - action: todo.add_item
                    target:
                      entity_id: $target
                    data:
                      item: "{{ trigger.json.item }}"
                      due_date: "{{ trigger.json.due_date }}"
                      description: "{{ trigger.json.description | default('', true) }}"
            default:
              - action: todo.add_item
                target:
                  entity_id: $target
                data:
                  item: "{{ trigger.json.item }}"
                  description: "{{ trigger.json.description | default('', true) }}"
        mode: queued
        max: 25
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    initial: SettingsForm,
    onSave: (SettingsForm) -> Unit,
    onTest: ((String) -> Unit) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf(initial.token) }
    var entity by remember { mutableStateOf(initial.entity) }
    var webhookId by remember { mutableStateOf(initial.webhookId) }
    var useWebhook by remember { mutableStateOf(initial.useWebhook) }
    var sound by remember { mutableStateOf(initial.sound) }
    var autoZoom by remember { mutableStateOf(initial.autoZoom) }
    var macroLens by remember { mutableStateOf(initial.macroLens) }
    var afDiagnostics by remember { mutableStateOf(initial.afDiagnostics) }
    var haEnabled by remember { mutableStateOf(initial.haEnabled) }

    var secretVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var historyCleared by remember { mutableStateOf(false) }

    fun currentForm() = SettingsForm(
        url = url,
        token = token,
        entity = entity,
        webhookId = webhookId,
        useWebhook = useWebhook,
        sound = sound,
        autoZoom = autoZoom,
        macroLens = macroLens,
        afDiagnostics = afDiagnostics,
        haEnabled = haEnabled
    )

    val canTest = url.isNotBlank() &&
        if (useWebhook) webhookId.isNotBlank() else token.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = {
                        onSave(currentForm())
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("Comportement")

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bip sonore au scan", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Deux tonalités distinctes : validé ou rejeté. La vibration reste active dans tous les cas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = sound, onCheckedChange = { sound = it })
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Zoom automatique", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Zoome quand un code est repéré mais trop petit dans le cadre. " +
                            "Ne corrige pas un problème de mise au point : désactive-le si le " +
                            "cadrage saute sans que ça aide à scanner.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoZoom, onCheckedChange = { autoZoom = it })
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("Home Assistant")

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Envoyer les scans vers Home Assistant", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Optionnel. Désactivé, les scans sont simplement enregistrés dans " +
                            "l'historique de l'appli, sans aucune connexion à un serveur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = haEnabled, onCheckedChange = { haEnabled = it; testResult = null })
            }

            if (haEnabled) {
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; testResult = null },
                    label = { Text("URL de l'instance") },
                    placeholder = { Text("http://192.168.1.42:8123") },
                    singleLine = true,
                    supportingText = {
                        Text("Sans slash final. Adresse locale, Tailscale, ou domaine derrière ton reverse proxy.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Utiliser un webhook", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Recommandé. Un webhook ne peut déclencher qu'une seule " +
                                "automatisation : au pire, quelqu'un ajoute des lignes à ta liste " +
                                "de pharmacie. Un jeton longue durée, lui, donne TOUS les droits " +
                                "du compte qui l'a créé — serrures, alarme, caméras, configuration.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useWebhook,
                        onCheckedChange = { useWebhook = it; testResult = null }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (useWebhook) {
                    OutlinedTextField(
                        value = webhookId,
                        onValueChange = { webhookId = it.trim(); testResult = null },
                        label = { Text("Identifiant du webhook") },
                        singleLine = true,
                        visualTransformation = if (secretVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { secretVisible = !secretVisible }) {
                                    Icon(
                                        if (secretVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        if (secretVisible) "Masquer" else "Afficher"
                                    )
                                }
                                IconButton(onClick = {
                                    webhookId = generateWebhookId()
                                    secretVisible = true
                                    testResult = null
                                }) {
                                    Icon(Icons.Default.Refresh, "Générer un identifiant")
                                }
                            }
                        },
                        supportingText = {
                            Text(
                                "Génère-le ici (bouton ↻), puis colle-le dans l'automatisation " +
                                    "ci-dessous. C'est un secret : il tient lieu de mot de passe."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = entity,
                        onValueChange = { entity = it },
                        label = { Text("Entité liste de tâches") },
                        placeholder = { Text("todo.armoire_a_pharmacie") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                "Sert uniquement à pré-remplir l'automatisation ci-dessous. " +
                                    "En mode webhook, l'appli ne l'envoie jamais : c'est " +
                                    "Home Assistant qui décide de la liste cible."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    AutomationCard(buildAutomationYaml(webhookId, entity))
                } else {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; testResult = null },
                        label = { Text("Jeton d'accès longue durée") },
                        singleLine = true,
                        visualTransformation = if (secretVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { secretVisible = !secretVisible }) {
                                Icon(
                                    if (secretVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    if (secretVisible) "Masquer" else "Afficher"
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                "Profil > Sécurité > Jetons d'accès longue durée. Pense à le " +
                                    "générer depuis un compte NON-administrateur dédié : ça limite " +
                                    "les dégâts si le téléphone est compromis."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = entity,
                        onValueChange = { entity = it; testResult = null },
                        label = { Text("Entité liste de tâches") },
                        placeholder = { Text("todo.armoire_a_pharmacie") },
                        singleLine = true,
                        supportingText = {
                            Text("Paramètres > Appareils et services > Liste de tâches locale, puis relève son entity_id.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            // On enregistre d'abord, sinon on testerait les
                            // anciennes valeurs.
                            onSave(currentForm())
                            testing = true
                            testResult = null
                            onTest { message ->
                                testing = false
                                testResult = message
                            }
                        },
                        enabled = !testing && canTest,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Test...")
                        } else {
                            Text("Tester la connexion")
                        }
                    }
                }

                testResult?.let { ResultBanner(it) }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("Mise au point")

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Objectif à focus rapproché", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Par défaut, l'appli utilise le même objectif que l'appli photo native, " +
                            "ce qui est le bon choix sur la plupart des téléphones. À n'activer " +
                            "que si ton objectif principal n'arrive pas à faire le point à " +
                            "8-12 cm. Nécessite de rouvrir l'écran de scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = macroLens, onCheckedChange = { macroLens = it })
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("Diagnostic")

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Afficher le diagnostic", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Affiche sur l'écran de scan l'état réel de l'autofocus, la distance " +
                            "de mise au point, l'objectif utilisé et la méthode de la dernière " +
                            "lecture. Utile pour comprendre si la caméra cherche, reste floue, " +
                            "se verrouille au mauvais endroit, ou si c'est le décodage qui échoue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = afDiagnostics, onCheckedChange = { afDiagnostics = it })
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("Maintenance")

            OutlinedButton(
                onClick = { onClearHistory(); historyCleared = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (historyCleared) "Historique effacé" else "Réinitialiser l'historique des scans")
            }
            Text(
                "L'historique évite d'enregistrer deux fois la même boîte. Il l'identifie " +
                    "par CIP13 + lot + numéro de série, et ne consulte pas l'état réel de " +
                    "Home Assistant : réinitialise-le si tu as vidé ta liste côté HA.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { onSave(currentForm()); onBack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enregistrer et revenir au scan") }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * L'automatisation à recopier dans Home Assistant. Texte sélectionnable pour
 * pouvoir être copié, et scrollable horizontalement : le YAML est sensible à
 * l'indentation, on ne veut surtout pas que les lignes soient repliées.
 */
@Composable
private fun AutomationCard(yaml: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Automatisation à créer dans Home Assistant",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Paramètres > Automatisations > Créer > Modifier en YAML, puis colle ceci.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "⚠ Après avoir enregistré, rouvre l'automatisation en YAML et vérifie que " +
                    "local_only est toujours à true : l'éditeur graphique de Home Assistant " +
                    "peut le repasser à false en sauvegardant. À false, le webhook devient " +
                    "joignable depuis Internet si ton instance est exposée.",
                style = MaterialTheme.typography.bodyMedium,
                color = StatusWarning,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
            )
            SelectionContainer {
                Text(
                    yaml,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ResultBanner(message: String) {
    val success = message.startsWith("OK")
    val tint = if (success) StatusSuccess else StatusError
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp, top = 4.dp)
    )
}
