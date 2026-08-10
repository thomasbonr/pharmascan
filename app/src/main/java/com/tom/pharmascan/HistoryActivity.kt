package com.tom.pharmascan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.tom.pharmascan.ui.PharmaScanTheme
import com.tom.pharmascan.ui.StatusError
import com.tom.pharmascan.ui.StatusNeutral
import com.tom.pharmascan.ui.StatusSuccess
import com.tom.pharmascan.ui.StatusWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dossier des boîtes déjà scannées.
 *
 * Différent de l'historique de session (liste `recent` de ScanUiState,
 * affichée sur l'écran de scan, effacée à la fermeture de l'appli) : celui-ci
 * est persisté sur disque (voir Prefs.HistoryEntry) et consultable à tout
 * moment, y compris après redémarrage.
 *
 * Purement local, comme le reste de l'appli : ne relit pas l'état réel de
 * Home Assistant (un item coché côté HA reste listé ici).
 */
class HistoryActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        enableEdgeToEdge()

        setContent {
            PharmaScanTheme {
                HistoryScreen(
                    entries = prefs.historyEntries(),
                    alertMonths = prefs.alertMonths,
                    onClear = { prefs.clearHistoryEntries() },
                    onBack = { finish() }
                )
            }
        }
    }
}

private enum class ExpiryStatus { EXPIRED, SOON, OK, UNKNOWN }

private fun isoDate(cal: Calendar): String {
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
}

/** Comparaison lexicographique valide : les dates ISO "AAAA-MM-JJ" trient dans l'ordre chronologique. */
private fun expiryStatus(expiryIso: String?, alertMonths: Int): ExpiryStatus {
    if (expiryIso.isNullOrBlank()) return ExpiryStatus.UNKNOWN
    val today = isoDate(Calendar.getInstance())
    if (expiryIso < today) return ExpiryStatus.EXPIRED
    val cutoff = isoDate(Calendar.getInstance().apply { add(Calendar.MONTH, alertMonths) })
    return if (expiryIso <= cutoff) ExpiryStatus.SOON else ExpiryStatus.OK
}

private fun frenchDate(iso: String): String {
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}

private val scannedAtFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    entries: List<Prefs.HistoryEntry>,
    alertMonths: Int,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var currentEntries by remember { mutableStateOf(entries) }
    var cleared by remember { mutableStateOf(false) }

    val filtered = remember(query, currentEntries) {
        if (query.isBlank()) currentEntries
        else currentEntries.filter {
            it.name.contains(query, ignoreCase = true) || it.cip13.contains(query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher") },
                placeholder = { Text("Nom ou CIP13") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            if (currentEntries.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun scan enregistré pour l'instant.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered, key = { "${it.scannedAt}_${it.cip13}" }) { entry ->
                        HistoryRow(entry, expiryStatus(entry.expiryIso, alertMonths))
                        Spacer(Modifier.height(8.dp))
                    }
                }

                OutlinedButton(
                    onClick = {
                        onClear()
                        currentEntries = emptyList()
                        cleared = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(if (cleared) "Historique effacé" else "Vider l'historique")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: Prefs.HistoryEntry, status: ExpiryStatus) {
    val tint = when (status) {
        ExpiryStatus.EXPIRED -> StatusError
        ExpiryStatus.SOON -> StatusWarning
        ExpiryStatus.OK -> StatusSuccess
        ExpiryStatus.UNKNOWN -> StatusNeutral
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("CIP ${entry.cip13}")
                        entry.lot?.let { append(" · lot $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Scanné le ${scannedAtFormat.format(Date(entry.scannedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.expiryIso?.let {
                Text(
                    frenchDate(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint
                )
            }
        }
    }
}
