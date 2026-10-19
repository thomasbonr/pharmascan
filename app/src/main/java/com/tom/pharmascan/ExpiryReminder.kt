package com.tom.pharmascan

/**
 * Ce que le rappel de péremption doit signaler aujourd'hui.
 *
 * Chaque boîte est rappelée au plus deux fois : quand elle entre dans la
 * fenêtre « bientôt périmée » (le réglage alertMonths, comme dans
 * l'historique), puis quand elle est périmée. [keys] sont les étapes
 * signalées par ce rappel, à ajouter à celles déjà faites une fois la
 * notification affichée.
 */
data class ExpiryNotice(
    val soon: List<Prefs.HistoryEntry>,
    val expired: List<Prefs.HistoryEntry>,
    val keys: Set<String>
) {
    val isEmpty get() = soon.isEmpty() && expired.isEmpty()
}

/** Une même boîte scannée deux fois (même CIP, lot, péremption) compte une fois. */
private fun boxKey(e: Prefs.HistoryEntry) = "${e.cip13}|${e.lot ?: ""}|${e.expiryIso}"

/**
 * Dates au format AAAA-MM-JJ (l'ordre des chaînes est l'ordre chronologique).
 * `cutoffIso` : la limite de « bientôt périmée », aujourd'hui + alertMonths.
 */
fun expiryNotice(
    entries: List<Prefs.HistoryEntry>,
    todayIso: String,
    cutoffIso: String,
    notified: Set<String>
): ExpiryNotice {
    val soon = mutableListOf<Prefs.HistoryEntry>()
    val expired = mutableListOf<Prefs.HistoryEntry>()
    val keys = mutableSetOf<String>()
    for (e in entries.filter { !it.expiryIso.isNullOrBlank() }.sortedBy { it.expiryIso }) {
        val expiry = e.expiryIso!!
        val (stage, list) = when {
            expiry < todayIso -> "expired" to expired
            expiry <= cutoffIso -> "soon" to soon
            else -> continue
        }
        val key = "${boxKey(e)}|$stage"
        if (key in notified || !keys.add(key)) continue
        list.add(e)
    }
    return ExpiryNotice(soon, expired, keys)
}

/** Les étapes déjà signalées dont la boîte est encore dans l'historique : le reste est oublié. */
fun keepNotifiedKeys(entries: List<Prefs.HistoryEntry>, notified: Set<String>): Set<String> {
    val boxes = entries.map(::boxKey).toSet()
    return notified.filter { it.substringBeforeLast('|') in boxes }.toSet()
}

/** Titre et texte de la notification. */
fun expiryNotificationText(notice: ExpiryNotice): Pair<String, String> {
    fun boxes(n: Int) = "$n boîte${if (n > 1) "s" else ""}"
    val title = when {
        notice.expired.isNotEmpty() && notice.soon.isNotEmpty() ->
            "${boxes(notice.expired.size)} périmée${if (notice.expired.size > 1) "s" else ""}, " +
                "${notice.soon.size} bientôt"
        notice.expired.isNotEmpty() ->
            "${boxes(notice.expired.size)} périmée${if (notice.expired.size > 1) "s" else ""}"
        else -> "${boxes(notice.soon.size)} bientôt périmée${if (notice.soon.size > 1) "s" else ""}"
    }
    val text = (notice.expired + notice.soon).joinToString(", ") { e ->
        "${e.name} (${frenchDate(e.expiryIso!!)})"
    }
    return title to text
}
