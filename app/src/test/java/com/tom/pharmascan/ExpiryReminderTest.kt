package com.tom.pharmascan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryReminderTest {

    private fun entry(name: String, expiry: String?, cip: String = "3400930000007", lot: String? = null, at: Long = 0L) =
        Prefs.HistoryEntry(cip13 = cip, name = name, lot = lot, expiryIso = expiry, scannedAt = at)

    private val today = "2026-10-19"
    private val cutoff = "2027-01-19"

    @Test
    fun `seules les boites perimees ou dans la fenetre sont signalees, la plus proche en tete`() {
        val n = expiryNotice(
            listOf(
                entry("Doliprane", "2027-11-30", cip = "1"),
                entry("Smecta", "2026-12-31", cip = "2"),
                entry("Fervex", "2026-01-31", cip = "3"),
                entry("Spasfon", null, cip = "4"),
                entry("Gaviscon", "2026-11-05", cip = "5"),
            ),
            today, cutoff, emptySet()
        )
        assertEquals(listOf("Gaviscon", "Smecta"), n.soon.map { it.name })
        assertEquals(listOf("Fervex"), n.expired.map { it.name })
        assertEquals(3, n.keys.size)
    }

    @Test
    fun `une etape deja signalee ne l'est plus, l'etape suivante si`() {
        val box = entry("Smecta", "2026-12-31", lot = "L1")
        val first = expiryNotice(listOf(box), today, cutoff, emptySet())
        assertEquals(1, first.soon.size)
        assertTrue(expiryNotice(listOf(box), today, cutoff, first.keys).isEmpty)
        // Deux mois et demi plus tard, la même boîte est périmée : nouveau rappel.
        val later = expiryNotice(listOf(box), "2027-01-02", "2027-04-02", first.keys)
        assertEquals(listOf("Smecta"), later.expired.map { it.name })
    }

    @Test
    fun `la meme boite scannee deux fois n'est signalee qu'une fois`() {
        val n = expiryNotice(
            listOf(entry("Smecta", "2026-12-31", lot = "L1", at = 1), entry("Smecta", "2026-12-31", lot = "L1", at = 2)),
            today, cutoff, emptySet()
        )
        assertEquals(1, n.soon.size)
    }

    @Test
    fun `les etapes des boites retirees de l'historique sont oubliees`() {
        val kept = entry("Smecta", "2026-12-31", cip = "2")
        val removed = entry("Fervex", "2026-01-31", cip = "3")
        val keys = expiryNotice(listOf(kept, removed), today, cutoff, emptySet()).keys
        assertEquals(setOf("2||2026-12-31|soon"), keepNotifiedKeys(listOf(kept), keys))
    }

    @Test
    fun `texte de la notification`() {
        val n = expiryNotice(
            listOf(entry("Fervex", "2026-01-31", cip = "3"), entry("Smecta", "2026-12-31", cip = "2"), entry("Gaviscon", "2026-11-05", cip = "5")),
            today, cutoff, emptySet()
        )
        assertEquals(
            "1 boîte périmée, 2 bientôt" to "Fervex (31/01/2026), Gaviscon (05/11/2026), Smecta (31/12/2026)",
            expiryNotificationText(n)
        )
        val soonOnly = expiryNotice(listOf(entry("Smecta", "2026-12-31")), today, cutoff, emptySet())
        assertEquals("1 boîte bientôt périmée", expiryNotificationText(soonOnly).first)
    }
}
