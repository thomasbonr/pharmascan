package com.tom.pharmascan

import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Résolution CIP13 -> informations médicament via l'API Médicaments FR
 * (https://medicaments-api.giygas.dev), qui expose la BDPM officielle du
 * ministère de la Santé, rafraîchie deux fois par jour.
 *
 * ATTENTION : c'est une API tierce, gratuite et sans garantie de service.
 * Toute la classe est donc conçue pour échouer proprement : si la résolution
 * ne marche pas, l'appli continue avec le CIP13 brut comme libellé. On ne
 * bloque JAMAIS un scan sur cette requête.
 *
 * Rate limiting annoncé : 1000 jetons/IP, recharge 3 jetons/s, coût 5-200
 * jetons selon l'endpoint. Un usage domestique n'en approche pas, mais le
 * cache local évite de refaire la requête pour un médicament déjà vu.
 */
class MedicamentApi(private val prefs: Prefs) {

    private val client = SharedHttpClient

    data class Result(
        val name: String?,
        val fromCache: Boolean,
        val error: String? = null,
        /** Ex. "16 comprimés". Null si non déterminable avec certitude. */
        val quantityLabel: String? = null,
        /** Ex. "comprimé pelliculé". */
        val form: String? = null,
        /** Conditions de délivrance BDPM, ex. "liste I". */
        val conditions: List<String> = emptyList()
    )

    /**
     * Appel BLOQUANT — à n'exécuter que depuis un thread de fond.
     * Renvoie toujours un Result, jamais d'exception.
     */
    fun lookupBlocking(cip13: String): Result {
        prefs.cachedInfo(cip13)?.let { return it.copy(fromCache = true) }

        val url = "$BASE/v1/medicaments?cip=$cip13"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "PharmaScan/1.0 (usage personnel)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return Result(null, false, "Trop de requêtes (rate limit), réessaie dans un instant")
                }
                if (!response.isSuccessful) {
                    return Result(null, false, "HTTP ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return Result(null, false, "Réponse vide")
                }
                val parsed = parse(body, cip13)
                if (parsed.name != null) {
                    prefs.cacheInfo(cip13, parsed)
                    return parsed
                }
                return Result(null, false, "Nom introuvable dans la réponse")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec requête $url", e)
            return Result(null, false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * `GET /v1/medicaments?cip=` renvoie soit un objet Medicament nu (cas
     * courant), soit un tableau nu, soit un objet paginé `{ data: [...] }`.
     * Le nom du médicament est dans `elementPharmaceutique`.
     *
     * ATTENTION : `presentation[].libelle` N'EST PAS le nom du médicament —
     * c'est le conditionnement ("plaquettes PVC-Aluminium de 16 comprimés").
     * Confondre les deux affichait le blister à la place du médicament.
     */
    private fun parse(body: String, cip13: String): Result {
        val root = try {
            JSONTokener(body).nextValue()
        } catch (e: Exception) {
            return Result(null, false, "JSON illisible")
        }

        val med = when (root) {
            is JSONArray -> if (root.length() > 0) root.optJSONObject(0) else null
            is JSONObject -> {
                val data = root.optJSONArray("data")
                if (data != null && data.length() > 0) data.optJSONObject(0) else root
            }
            else -> null
        } ?: return Result(null, false, "Structure inattendue")

        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            med.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        }?.let(::cleanup) ?: return Result(null, false, "Nom absent")

        val form = med.optString("formePharmaceutique", "")
            .takeIf { it.isNotBlank() && it != "null" }

        val conditions = med.optJSONArray("conditions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList()

        return Result(
            name = name,
            fromCache = false,
            quantityLabel = extractQuantity(med, cip13, form),
            form = form,
            conditions = conditions
        )
    }

    /**
     * Les dénominations BDPM sont verbeuses :
     * "DOLIPRANE 1000 mg, comprimé, boîte de 8" -> on garde une forme lisible
     * mais on coupe les mentions les plus longues pour tenir dans une ligne
     * de liste Home Assistant.
     */
    private fun cleanup(raw: String): String {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.length > 90) trimmed.take(87) + "..." else trimmed
    }

    companion object {
        private const val TAG = "PharmaScan/API"
        private const val BASE = "https://medicaments-api.giygas.dev"

        /**
         * "elementPharmaceutique" est le champ confirmé de la réponse réelle
         * de /v1/medicaments (vérifié en interrogeant l'API en direct). Le
         * reste est un filet de sécurité si le schéma change côté API.
         */
        private val NAME_KEYS = listOf(
            "elementPharmaceutique",
            "denomination",
            "denominationMedicament",
            "nom",
            "name"
        )

        private val SOLID_FORMS = listOf(
            "comprim", "gélule", "gelule", "capsule", "dragée", "dragee", "pastille",
            "suppositoire", "ovule", "sachet"
        )

        private const val UNIT = "(comprimé|comprime|gélule|gelule|capsule|dragée|dragee|pastille|suppositoire|ovule)"

        /** « de 28 comprimé(s) » -> nombre par contenant. */
        private val RE_PER_CONTAINER = Regex("""\bde\s+(\d+)\s*$UNIT""")

        /** « 30 plaquette(s) ... » en tête -> multiplicateur de contenants. */
        private val RE_MULTIPLIER =
            Regex("""^\s*(\d+)\s+(?:plaquette|pilulier|flacon|tube|film|étui|etui|boîte|boite|récipient|recipient|pot|sachet)""")

        /** « de 10 x 1 gélule », « de (14 x 4 x 1) comprimés ». */
        private val RE_PRODUCT = Regex("""\bde\s+\(?\s*(\d+(?:\s*[x×]\s*\d+)+)\s*\)?\s*$UNIT""")

        private val PRODUCT_SEPARATORS = charArrayOf('x', '×')

        /**
         * Garde-fou de vraisemblance : la plus grosse valeur observée sur
         * l'échantillon réel était 500 (conditionnement hospitalier). Au-delà,
         * on suspecte une regex qui a mordu sur autre chose et on préfère ne
         * rien afficher.
         */
        private const val MAX_PLAUSIBLE_UNITS = 1000

        /**
         * Quantité par boîte, déduite du libellé de conditionnement.
         */
        internal fun extractQuantity(med: JSONObject, cip13: String, form: String?): String? {
            val solidForm = form?.lowercase()?.let { f ->
                SOLID_FORMS.any { f.contains(it) }
            } ?: false
            if (!solidForm) return null

            val presentations = med.optJSONArray("presentation") ?: return null
            val label = (0 until presentations.length())
                .mapNotNull { presentations.optJSONObject(it) }
                .firstOrNull { it.optString("cip13") == cip13 || it.optLong("cip13").toString() == cip13 }
                ?.optString("libelle")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val lower = label.lowercase()

            RE_PRODUCT.find(lower)?.let { m ->
                val total = m.groupValues[1].split(*PRODUCT_SEPARATORS)
                    .mapNotNull { it.trim().toIntOrNull() }
                    .takeIf { it.isNotEmpty() }
                    ?.reduce { a, b -> a * b }
                if (total != null) return format(total, m.groupValues[2])
            }

            val per = RE_PER_CONTAINER.find(lower) ?: return null
            val count = per.groupValues[1].toIntOrNull() ?: return null
            val multiplier = RE_MULTIPLIER.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return format(count * multiplier, per.groupValues[2])
        }

        internal fun format(count: Int, unit: String): String? {
            if (count <= 0 || count > MAX_PLAUSIBLE_UNITS) return null
            val plural = if (count > 1 && !unit.endsWith("s")) "${unit}s" else unit
            return "$count $plural"
        }
    }
}
