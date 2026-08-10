package com.tom.pharmascan

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * Résolution CIP13 -> nom du médicament via l'API Médicaments FR
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Result(val name: String?, val fromCache: Boolean, val error: String? = null)

    /**
     * Appel BLOQUANT — à n'exécuter que depuis un thread de fond.
     * Renvoie toujours un Result, jamais d'exception.
     */
    fun lookupBlocking(cip13: String): Result {
        prefs.cachedName(cip13)?.let { return Result(it, fromCache = true) }

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
                    return Result(null, fromCache = false, error = "Trop de requêtes (rate limit), réessaie dans un instant")
                }
                if (!response.isSuccessful) {
                    return Result(null, fromCache = false, error = "HTTP ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return Result(null, fromCache = false, error = "Réponse vide")
                }
                val name = extractName(body)
                if (name != null) {
                    prefs.cacheName(cip13, name)
                    return Result(name, fromCache = false)
                }
                return Result(null, fromCache = false, error = "Nom introuvable dans la réponse")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec requête $url", e)
            return Result(null, fromCache = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * `GET /v1/medicaments?cip=` renvoie soit un objet Medicament nu (cas
     * courant, un seul résultat pour un CIP exact), soit un tableau nu, soit
     * un objet paginé `{ data: [...] }` — les trois formes sont vues en
     * pratique. Le nom du médicament est dans `elementPharmaceutique`
     * ("PARACETAMOL/CODEINE VIATRIS 500 mg/30 mg, comprimé").
     *
     * ATTENTION : `presentation[].libelle` (imbriqué) N'EST PAS le nom du
     * médicament — c'est le conditionnement ("plaquette(s) PVC-Aluminium de
     * 18 comprimé(s)"). Confondre les deux a longtemps affiché le
     * conditionnement à la place du nom ; ne pas réintroduire "libelle"
     * comme clé de premier niveau pour cette raison.
     */
    private fun extractName(body: String): String? {
        val root = try {
            JSONTokener(body).nextValue()
        } catch (e: Exception) {
            return null
        }

        val candidate = when (root) {
            is JSONArray -> if (root.length() > 0) root.optJSONObject(0) else null
            is JSONObject -> {
                val data = root.optJSONArray("data")
                if (data != null && data.length() > 0) data.optJSONObject(0) else root
            }
            else -> null
        } ?: return null

        for (key in NAME_KEYS) {
            val value = candidate.optString(key, "")
            if (value.isNotBlank() && value != "null") return cleanup(value)
        }
        return null
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
    }
}
