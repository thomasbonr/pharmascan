package com.tom.pharmascan

/**
 * L'historique en texte, pour le partager (message, mail, note) : la liste
 * de l'armoire à pharmacie, la péremption la plus proche en tête, les boîtes
 * périmées signalées. `todayIso` au format AAAA-MM-JJ.
 */
fun historyAsText(entries: List<Prefs.HistoryEntry>, todayIso: String): String {
    val sorted = entries.sortedWith(compareBy<Prefs.HistoryEntry, String?>(nullsLast()) { it.expiryIso }.thenBy { it.name })
    val expired = sorted.count { it.expiryIso != null && it.expiryIso < todayIso }
    return buildString {
        append("Armoire à pharmacie — ${sorted.size} boîte${if (sorted.size > 1) "s" else ""}, au ${frenchDate(todayIso)}")
        if (expired > 0) append(" ($expired périmée${if (expired > 1) "s" else ""})")
        append("\n")
        for (e in sorted) {
            append("\n• ").append(e.name)
            when {
                e.expiryIso == null -> append(" — péremption inconnue")
                e.expiryIso < todayIso -> append(" — PÉRIMÉ depuis le ${frenchDate(e.expiryIso)}")
                else -> append(" — périme le ${frenchDate(e.expiryIso)}")
            }
            e.lot?.let { append(" (lot $it)") }
        }
    }
}
