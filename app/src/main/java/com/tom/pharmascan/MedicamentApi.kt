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

        // Deux endpoints possibles : on tente le plus spécifique d'abord.
        val urls = listOf(
            "$BASE/v1/presentations/$cip13",
            "$BASE/v1/medicaments?cip=$cip13"
        )

        var lastError: String? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "PharmaScan/1.0 (usage personnel)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        lastError = "Trop de requêtes (rate limit), réessaie dans un instant"
                        return@use
                    }
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        lastError = "Réponse vide"
                        return@use
                    }
                    val name = extractName(body)
                    if (name != null) {
                        prefs.cacheName(cip13, name)
                        return Result(name, fromCache = false)
                    }
                    lastError = "Nom introuvable dans la réponse"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Échec requête $url", e)
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return Result(null, fromCache = false, error = lastError)
    }

    /**
     * Extraction tolérante : la forme exacte du JSON varie selon l'endpoint
     * et peut changer côté API. On explore récursivement les enveloppes
     * usuelles et on teste plusieurs clés de dénomination avant d'abandonner.
     */
    private fun extractName(body: String): String? {
        val root = try {
            JSONTokener(body).nextValue()
        } catch (e: Exception) {
            return null
        }

        val candidate = unwrap(root) ?: return null

        for (key in NAME_KEYS) {
            val value = candidate.optString(key, "")
            if (value.isNotBlank() && value != "null") return cleanup(value)
        }

        // Parfois la dénomination est un cran plus bas, dans un objet imbriqué
        for (nested in listOf("medicament", "specialite", "produit")) {
            candidate.optJSONObject(nested)?.let { obj ->
                for (key in NAME_KEYS) {
                    val value = obj.optString(key, "")
                    if (value.isNotBlank() && value != "null") return cleanup(value)
                }
            }
        }
        return null
    }

    /** Déballe les enveloppes { results: [...] } / { data: [...] } / [...] */
    private fun unwrap(root: Any?): JSONObject? = when (root) {
        is JSONArray -> if (root.length() > 0) root.optJSONObject(0) else null
        is JSONObject -> {
            val arr = root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("medicaments")
                ?: root.optJSONArray("presentations")
            if (arr != null && arr.length() > 0) arr.optJSONObject(0) else root
        }
        else -> null
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
        private val NAME_KEYS = listOf(
            "denomination",
            "denomination_medicament",
            "denominationMedicament",
            "libelle",
            "libelle_presentation",
            "nom",
            "name"
        )
    }
}
