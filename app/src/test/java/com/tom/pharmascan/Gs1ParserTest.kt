package com.tom.pharmascan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du VRAI Gs1Parser Kotlin, exécutés sur la JVM.
 *
 * Reprend les cas de tools/gs1_reference/test_gs1.py : jusqu'ici, seul le
 * port Python était testé, et rien ne garantissait que le Kotlin restait
 * aligné sur lui. Les deux doivent évoluer ensemble.
 */
class Gs1ParserTest {

    private val gs = '\u001D'

    /** GTIN-14 valide = "0" + 12 chiffres + clé calculée. */
    private fun makeGtin(cip12: String): String {
        val body = "0$cip12"
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return body + ((10 - sum % 10) % 10)
    }

    private val gtin = makeGtin("340093000000")
    private val year = 2026

    // ---- 1. Clé de contrôle ------------------------------------------------

    @Test fun `checksum d'un GTIN fabrique`() {
        assertEquals(14, gtin.length)
        assertTrue(Gs1Parser.isValidGtinChecksum(gtin))
    }

    @Test fun `checksum rejette une corruption du dernier chiffre`() {
        val corrupted = gtin.dropLast(1) + ((gtin.last() - '0' + 1) % 10)
        assertFalse(Gs1Parser.isValidGtinChecksum(corrupted))
    }

    @Test fun `checksum accepte un CIP13 seul (saisie manuelle)`() {
        assertTrue(Gs1Parser.isValidGtinChecksum(gtin.substring(1)))
    }

    @Test fun `checksum rejette longueur et caracteres invalides`() {
        assertFalse(Gs1Parser.isValidGtinChecksum("123"))
        assertFalse(Gs1Parser.isValidGtinChecksum(gtin.dropLast(1) + "X"))
    }

    // ---- 2. DataMatrix pharma FR typique ----------------------------------

    @Test fun `datamatrix typique 01 17 10 21`() {
        val d = Gs1Parser.parse("01${gtin}17271130" + "10L4A21" + gs + "21XK7291056")
        assertEquals(gtin, d.gtin)
        assertEquals("271130", d.expiryRaw)
        assertEquals("L4A21", d.lot)
        assertEquals("XK7291056", d.serial)
        assertEquals(emptyList<String>(), d.errors)
        assertEquals(emptyList<String>(), d.notices)
        assertEquals(gtin.substring(1), d.cip13)
        assertTrue(d.isUsable)
    }

    @Test fun `ordre inverse, AI variable avant AI fixe`() {
        val d = Gs1Parser.parse("10LOT99" + gs + "01$gtin" + "17280229")
        assertEquals(gtin, d.gtin)
        assertEquals("LOT99", d.lot)
        assertEquals("280229", d.expiryRaw)
        assertTrue(d.isUsable)
    }

    @Test fun `lot en dernier sans GS va jusqu'a la fin`() {
        val d = Gs1Parser.parse("01${gtin}17271130" + "10ABC123")
        assertEquals("ABC123", d.lot)
        assertEquals(emptyList<String>(), d.errors)
    }

    @Test fun `lot avant-dernier sans GS avale la suite (comportement documente)`() {
        val d = Gs1Parser.parse("01$gtin" + "10ABC123" + "21SERIE1")
        assertEquals("ABC12321SERIE1", d.lot)
        assertNull(d.serial)
    }

    @Test fun `FNC1 initial rendu comme GS est ignore`() {
        val d = Gs1Parser.parse("$gs" + "01${gtin}17271130")
        assertEquals(gtin, d.gtin)
        assertTrue(d.isUsable)
    }

    // ---- 3. Normalisation --------------------------------------------------

    @Test fun `prefixe de symbologie d2 retire`() {
        assertEquals(gtin, Gs1Parser.parse("]d2" + "01${gtin}17271130").gtin)
    }

    @Test fun `GS represente en clair`() {
        for (sep in listOf("<GS>", "{GS}", "␝", "\u001E")) {
            val d = Gs1Parser.parse("01$gtin" + "10LOT" + sep + "17271130")
            assertEquals(sep, "LOT", d.lot)
            assertEquals(sep, "271130", d.expiryRaw)
        }
    }

    // ---- 4. Dates ----------------------------------------------------------

    @Test fun `jour 00 = fin de mois`() {
        assertEquals("2027-11-30", Gs1Parser.parseExpiry("271100", year))
        assertEquals("2028-02-29", Gs1Parser.parseExpiry("280200", year))
        assertEquals("2027-02-28", Gs1Parser.parseExpiry("270200", year))
        assertEquals("2027-12-31", Gs1Parser.parseExpiry("271200", year))
    }

    @Test fun `jour explicite conserve`() {
        assertEquals("2027-11-15", Gs1Parser.parseExpiry("271115", year))
    }

    @Test fun `jour inexistant ramene au dernier jour du mois`() {
        assertEquals("2027-02-28", Gs1Parser.parseExpiry("270231", year))
    }

    @Test fun `dates invalides rejetees`() {
        assertNull(Gs1Parser.parseExpiry("271301", year))
        assertNull(Gs1Parser.parseExpiry("270001", year))
        assertNull(Gs1Parser.parseExpiry("271132", year))
        assertNull(Gs1Parser.parseExpiry("27AB01", year))
        assertNull(Gs1Parser.parseExpiry("2711", year))
    }

    @Test fun `fenetre glissante du siecle`() {
        assertEquals("1999-12-31", Gs1Parser.parseExpiry("991231", year))
        assertEquals("2027-12-31", Gs1Parser.parseExpiry("271231", year))
        assertEquals("2070-12-31", Gs1Parser.parseExpiry("701231", year))
        // Écart +50 : siècle courant ; +51 : siècle précédent.
        assertEquals("2076-01-01", Gs1Parser.parseExpiry("760101", year))
        assertEquals("1977-01-01", Gs1Parser.parseExpiry("770101", year))
    }

    @Test fun `date illisible = remarque, pas rejet`() {
        val d = Gs1Parser.parse("01${gtin}17271399")
        assertTrue(d.isUsable)
        assertNull(d.expiryIso)
        assertTrue(d.notices.any { it.contains("illisible") })
    }

    // ---- 5. Codes non exploitables ----------------------------------------

    @Test fun `texte libre rejete`() {
        val d = Gs1Parser.parse("HELLO WORLD")
        assertFalse(d.isUsable)
        assertNull(d.cip13)
        assertTrue(d.errors.isNotEmpty())
    }

    @Test fun `GTIN tout a zero rejete malgre un checksum valide`() {
        val d = Gs1Parser.parse("0100000000000000")
        assertFalse(d.isUsable)
        assertTrue(d.errors.any { it.contains("dégénéré") })
    }

    @Test fun `GTIN a cle invalide rejete`() {
        val bad = gtin.dropLast(1) + ((gtin.last() - '0' + 1) % 10)
        val d = Gs1Parser.parse("01${bad}17271130")
        assertFalse(d.isUsable)
        assertTrue(d.rejectionReason!!.contains("Clé de contrôle"))
    }

    @Test fun `GTIN tronque rejete`() {
        val d = Gs1Parser.parse("01" + gtin.take(10))
        assertFalse(d.isUsable)
    }

    @Test fun `lot de plus de 20 caracteres rejete (GS manquant)`() {
        val d = Gs1Parser.parse("01$gtin" + "10" + "A".repeat(25))
        assertFalse(d.isUsable)
        assertTrue(d.errors.any { it.contains("Lot anormalement long") })
    }

    @Test fun `produit hors 3400 accepte avec remarque`() {
        val other = makeGtin("401234500000")
        val d = Gs1Parser.parse("01$other")
        assertTrue(d.isUsable)
        assertTrue(d.notices.any { it.contains("CIP13") })
    }

    // ---- 6. AI à 4 chiffres ------------------------------------------------

    @Test fun `AI 3103 lue sur 4 plus 6`() {
        val d = Gs1Parser.parse("01$gtin" + "3103000250")
        assertEquals("000250", d.fields["3103"])
    }

    // ---- 7. Déduplication --------------------------------------------------

    @Test fun `boxKey distingue deux boites du meme produit`() {
        val a = Gs1Parser.parse("01${gtin}17271130" + "10L1" + gs + "21S1")
        val b = Gs1Parser.parse("01${gtin}17271130" + "10L1" + gs + "21S2")
        assertFalse(Gs1Parser.boxKey(a) == Gs1Parser.boxKey(b))
        assertEquals(Gs1Parser.boxKey(a), Gs1Parser.boxKey(Gs1Parser.parse("]d2" + a.raw)))
    }
}
