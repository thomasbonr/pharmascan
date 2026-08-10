package com.tom.pharmascan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tom.pharmascan.ui.PharmaScanTheme
import com.tom.pharmascan.ui.StatusError
import com.tom.pharmascan.ui.StatusSuccess
import java.util.concurrent.Executors

/**
 * Réglages.
 *
 * Aucune valeur n'est en dur dans le code : l'APK est réutilisable tel quel
 * sur une autre instance Home Assistant, par quelqu'un d'autre, sans
 * recompiler.
 *
 * Le bouton de test est le point d'ergonomie important : il distingue les
 * trois échecs classiques (serveur injoignable / jeton refusé / entité
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
                    initialUrl = prefs.haUrl,
                    initialToken = prefs.haToken,
                    initialEntity = prefs.todoEntity,
                    initialSound = prefs.soundEnabled,
                    initialAutoZoom = prefs.autoZoomEnabled,
                    onSave = { url, token, entity, sound, autoZoom ->
                        prefs.haUrl = url
                        prefs.haToken = token
                        prefs.todoEntity = entity
                        prefs.soundEnabled = sound
                        prefs.autoZoomEnabled = autoZoom
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    initialUrl: String,
    initialToken: String,
    initialEntity: String,
    initialSound: Boolean,
    initialAutoZoom: Boolean,
    onSave: (String, String, String, Boolean, Boolean) -> Unit,
    onTest: ((String) -> Unit) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var entity by remember { mutableStateOf(initialEntity) }
    var sound by remember { mutableStateOf(initialSound) }
    var autoZoom by remember { mutableStateOf(initialAutoZoom) }
    var tokenVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var historyCleared by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = {
                        onSave(url, token, entity, sound, autoZoom)
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
            SectionTitle("Connexion Home Assistant")

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

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; testResult = null },
                label = { Text("Jeton d'accès longue durée") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            if (tokenVisible) "Masquer" else "Afficher"
                        )
                    }
                },
                supportingText = {
                    Text("Profil > Sécurité > Jetons d'accès longue durée. Stocké chiffré via le Keystore Android.")
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

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        // On enregistre d'abord, sinon on testerait les
                        // anciennes valeurs.
                        onSave(url, token, entity, sound, autoZoom)
                        testing = true
                        testResult = null
                        onTest { message ->
                            testing = false
                            testResult = message
                        }
                    },
                    enabled = !testing && url.isNotBlank() && token.isNotBlank(),
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
                onClick = { onSave(url, token, entity, sound, autoZoom); onBack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enregistrer et revenir au scan") }

            Spacer(Modifier.height(32.dp))
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
