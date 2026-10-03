package com.tom.pharmascan.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * État observable de l'écran de scan.
 *
 * Volontairement une simple classe à `mutableStateOf` plutôt qu'un ViewModel :
 * l'activité est unique, l'état ne survit pas à sa destruction (une session de
 * scan dure quelques minutes), et Compose recompose automatiquement.
 */
class ScanUiState {

    enum class Phase { STARTING, READY, LOCKING_ON, PROCESSING, SUCCESS, REJECTED, ERROR }

    /** Statut principal affiché dans la carte du bas. */
    var phase by mutableStateOf(Phase.STARTING)
        private set

    var headline by mutableStateOf("Démarrage de la caméra")
        private set

    var detail by mutableStateOf<String?>(null)
        private set

    /** Vrai pendant que le zoom automatique se cale sur un code repéré. */
    var lockingOn by mutableStateOf(false)

    /** Vrai quand le dernier décodage a nécessité l'inversion (emballage sombre). */
    var invertedDecode by mutableStateOf(false)

    var torchOn by mutableStateOf(false)
    var zoomRatio by mutableStateOf(1f)
    var flashHint by mutableStateOf(false)

    var pendingCount by mutableStateOf(0)
    var sessionCount by mutableStateOf(0)

    /** Ligne de diagnostic autofocus, non nulle seulement si le réglage est actif. */
    var debugInfo by mutableStateOf<String?>(null)

    /** Historique de la session courante, le plus récent en tête. */
    val recent = mutableStateListOf<ScanEntry>()

    /** Action en attente pour forcer l'ajout d'un doublon. */
    var forceAddAction by mutableStateOf<(() -> Unit)?>(null)

    data class ScanEntry(
        val name: String,
        val expiry: String?,
        val cip13: String,
        val status: EntryStatus
    )

    enum class EntryStatus { SENT, QUEUED, REJECTED }

    fun set(phase: Phase, headline: String, detail: String? = null) {
        this.phase = phase
        this.headline = headline
        this.detail = detail
    }

    fun addEntry(entry: ScanEntry) {
        recent.add(0, entry)
        if (recent.size > 30) recent.removeAt(recent.lastIndex)
        if (entry.status != EntryStatus.REJECTED) sessionCount++
    }
}
