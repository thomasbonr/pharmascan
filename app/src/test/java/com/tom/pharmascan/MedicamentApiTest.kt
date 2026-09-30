package com.tom.pharmascan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests unitaires pour MedicamentApi.extractQuantity().
 *
 * Cas documentés dans le README et le TODO : boîte de 8 Doliprane, 30
 * plaquettes de 1 comprimé, conditionnement multi-présentation, forme
 * liquide → null.
 */
class MedicamentApiTest {

    private fun makeMed(cip13: String, libelle: String, form: String = "comprimé pelliculé"): Pair<JSONObject, String> {
        val presentation = JSONObject().apply {
            put("cip13", cip13)
            put("libelle", libelle)
        }
        val med = JSONObject().apply {
            put("formePharmaceutique", form)
            put("presentation", JSONArray().put(presentation))
        }
        return med to form
    }

    @Test
    fun `boite de 8 comprimes`() {
        val (med, form) = makeMed("3400930000007", "plaquette(s) PVC-Aluminium de 8 comprimé(s)")
        val result = MedicamentApi.extractQuantity(med, "3400930000007", form)
        assertEquals("8 comprimés", result)
    }

    @Test
    fun `30 plaquettes de 1 comprime = 30 comprimes`() {
        val (med, form) = makeMed("3400930000007", "30 plaquette(s) thermoformée(s) de 1 comprimé(s)")
        val result = MedicamentApi.extractQuantity(med, "3400930000007", form)
        assertEquals("30 comprimés", result)
    }

    @Test
    fun `produit croise 10 x 1 gelule`() {
        val (med, form) = makeMed("3400930000007", "plaquette(s) de 10 x 1 gélule(s)", "gélule")
        val result = MedicamentApi.extractQuantity(med, "3400930000007", form)
        assertEquals("10 gélules", result)
    }

    @Test
    fun `forme liquide retourne null`() {
        val (med, _) = makeMed("3400930000007", "1 flacon(s) de 200 ml", "solution buvable")
        val result = MedicamentApi.extractQuantity(med, "3400930000007", "solution buvable")
        assertNull(result)
    }

    @Test
    fun `CIP13 absent des presentations retourne null`() {
        val (med, form) = makeMed("3400930000007", "de 8 comprimé(s)")
        val result = MedicamentApi.extractQuantity(med, "9999999999999", form)
        assertNull(result)
    }

    @Test
    fun `multi-presentation filtre par CIP13`() {
        val pres1 = JSONObject().apply {
            put("cip13", "3400930000111")
            put("libelle", "de 100 comprimé(s)")
        }
        val pres2 = JSONObject().apply {
            put("cip13", "3400930000007")
            put("libelle", "de 8 comprimé(s)")
        }
        val med = JSONObject().apply {
            put("formePharmaceutique", "comprimé pelliculé")
            put("presentation", JSONArray().put(pres1).put(pres2))
        }
        val result = MedicamentApi.extractQuantity(med, "3400930000007", "comprimé pelliculé")
        assertEquals("8 comprimés", result)
    }

    @Test
    fun `format pluralise correctement`() {
        assertEquals("1 comprimé", MedicamentApi.format(1, "comprimé"))
        assertEquals("8 comprimés", MedicamentApi.format(8, "comprimé"))
        assertEquals("8 gélules", MedicamentApi.format(8, "gélule"))  // already has 's' sound but not letter
        assertNull(MedicamentApi.format(0, "comprimé"))
        assertNull(MedicamentApi.format(-1, "comprimé"))
        assertNull(MedicamentApi.format(1001, "comprimé"))
    }

    @Test
    fun `suppositoires extraits correctement`() {
        val (med, form) = makeMed("3400930000007", "plaquette(s) de 10 suppositoire(s)", "suppositoire")
        val result = MedicamentApi.extractQuantity(med, "3400930000007", form)
        assertEquals("10 suppositoires", result)
    }

    @Test
    fun `sachets extraits correctement`() {
        val (med, form) = makeMed("3400930000007", "20 sachet(s) de 1 comprimé(s)", "sachet")
        // sachet as a form -> solidForm = true, but the regex looks for "comprimé" as unit
        // so this should find "1 comprimé" * 20 = 20 comprimés
        val result = MedicamentApi.extractQuantity(med, "3400930000007", form)
        assertEquals("20 comprimés", result)
    }
}
