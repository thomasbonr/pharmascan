package com.tom.pharmascan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryShareTest {

    private fun entry(name: String, expiry: String?, lot: String? = null) =
        Prefs.HistoryEntry(cip13 = "3400930000007", name = name, lot = lot, expiryIso = expiry, scannedAt = 0L)

    @Test
    fun `la peremption la plus proche en tete, les inconnues a la fin`() {
        val text = historyAsText(
            listOf(entry("Spasfon", null), entry("Doliprane", "2027-11-30", "AB12"), entry("Smecta", "2026-12-31")),
            "2026-10-19"
        )
        val lines = text.lines().filter { it.startsWith("•") }
        assertEquals(
            listOf(
                "• Smecta — périme le 31/12/2026",
                "• Doliprane — périme le 30/11/2027 (lot AB12)",
                "• Spasfon — péremption inconnue",
            ),
            lines
        )
    }

    @Test
    fun `les boites perimees sont signalees et comptees`() {
        val text = historyAsText(listOf(entry("Fervex", "2026-01-31"), entry("Doliprane", "2027-11-30")), "2026-10-19")
        assertTrue(text.startsWith("Armoire à pharmacie — 2 boîtes, au 19/10/2026 (1 périmée)"))
        assertTrue(text.contains("• Fervex — PÉRIMÉ depuis le 31/01/2026"))
    }

    @Test
    fun `une seule boite reste au singulier`() {
        assertTrue(historyAsText(listOf(entry("Doliprane", "2027-11-30")), "2026-10-19").startsWith("Armoire à pharmacie — 1 boîte, au"))
    }
}
