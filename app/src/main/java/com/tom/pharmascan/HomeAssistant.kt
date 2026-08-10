package com.tom.pharmascan

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client REST Home Assistant.
 *
 * Le point clé pour la robustesse : rien n'est jamais perdu. Si HA est
 * injoignable (hors du réseau local, VPN coupé, HA en cours de reboot),
 * l'item est mis en file d'attente PERSISTÉE sur le disque et renvoyé
 * automatiquement au prochain scan réussi ou au prochain lancement de
 * l'appli. On peut donc scanner toute son armoire hors-ligne et synchroniser
 * plus tard.
 */
class HomeAssistant(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    data class Item(
        val label: String,
        val dueDate: String?,
        val description: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("label", label)
            put("dueDate", dueDate ?: JSONObject.NULL)
            put("description", description ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(o: JSONObject) = Item(
                label = o.getString("label"),
                // isNull() distingue proprement JSONObject.NULL d'une chaîne
                // vide ; optString aurait renvoyé le littéral "null".
                dueDate = if (o.isNull("dueDate")) null else o.getString("dueDate"),
                description = if (o.isNull("description")) null else o.getString("description")
            )
        }
    }

    sealed class SendResult {
        object Success : SendResult()
        data class Queued(val reason: String, val queueSize: Int) : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    // ---- Test de connexion (utilisé par l'écran Réglages) -----------------

    /** Appel BLOQUANT. Vérifie l'URL, le token, et l'existence de l'entité todo. */
    fun testConnectionBlocking(): String {
        if (!prefs.isConfigured) return "Réglages incomplets"
        return try {
            val req = Request.Builder()
                .url("${prefs.haUrl}/api/states/${prefs.todoEntity}")
                .header("Authorization", "Bearer ${prefs.haToken}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> "OK — entité ${prefs.todoEntity} trouvée"
                    401 -> "Token refusé (401). Régénère un jeton longue durée."
                    404 -> "Connexion OK mais l'entité « ${prefs.todoEntity} » n'existe pas."
                    else -> "Réponse inattendue : HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            "Injoignable : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---- Envoi ------------------------------------------------------------

    /**
     * Appel BLOQUANT. Tente l'envoi ; en cas d'échec réseau, met en file
     * d'attente. Un échec 401/404 n'est PAS mis en file (ça ne marchera pas
     * mieux plus tard) — on remonte l'erreur à l'utilisateur.
     */
    fun sendBlocking(item: Item): SendResult {
        if (!prefs.isConfigured) {
            enqueue(item)
            return SendResult.Queued("Home Assistant non configuré", queueSize())
        }

        return try {
            val code = postItem(item)
            when {
                code in 200..299 -> {
                    flushQueueBlocking() // profite de la connexion retrouvée
                    SendResult.Success
                }
                code == 401 -> SendResult.Failed("Token refusé (401)")
                code == 404 -> SendResult.Failed("Entité ${prefs.todoEntity} introuvable (404)")
                code == 400 -> SendResult.Failed("Requête refusée par HA (400)")
                else -> {
                    enqueue(item)
                    SendResult.Queued("HTTP $code", queueSize())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Envoi échoué, mise en file", e)
            enqueue(item)
            SendResult.Queued(e.message ?: "réseau indisponible", queueSize())
        }
    }

    private fun postItem(item: Item): Int {
        val payload = JSONObject().apply {
            put("entity_id", prefs.todoEntity)
            put("item", item.label)
            item.dueDate?.let { put("due_date", it) }
            item.description?.let { put("description", it) }
        }
        val req = Request.Builder()
            .url("${prefs.haUrl}/api/services/todo/add_item")
            .header("Authorization", "Bearer ${prefs.haToken}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { return it.code }
    }

    // ---- File d'attente persistée ----------------------------------------

    private fun readQueue(): MutableList<Item> {
        return try {
            val arr = JSONArray(prefs.pendingQueueJson)
            MutableList(arr.length()) { Item.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "File d'attente corrompue, réinitialisation", e)
            mutableListOf()
        }
    }

    private fun writeQueue(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.pendingQueueJson = arr.toString()
    }

    private fun enqueue(item: Item) {
        val q = readQueue()
        q.add(item)
        // Borne de sécurité : on ne garde pas indéfiniment
        writeQueue(if (q.size > 500) q.takeLast(500) else q)
    }

    fun queueSize(): Int = readQueue().size

    /**
     * Appel BLOQUANT. Tente de vider la file. S'arrête au premier échec pour
     * préserver l'ordre et ne pas marteler un serveur injoignable.
     * Renvoie le nombre d'items envoyés avec succès.
     */
    fun flushQueueBlocking(): Int {
        if (!prefs.isConfigured) return 0
        val queue = readQueue()
        if (queue.isEmpty()) return 0

        var sent = 0
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            try {
                val code = postItem(item)
                if (code in 200..299) {
                    iterator.remove()
                    sent++
                } else if (code == 401 || code == 404 || code == 400) {
                    // Erreur définitive : on retire pour ne pas bloquer la file
                    Log.w(TAG, "Item abandonné (HTTP $code) : ${item.label}")
                    iterator.remove()
                } else {
                    break
                }
            } catch (e: Exception) {
                break // réseau toujours HS, on réessaiera
            }
        }
        writeQueue(queue)
        return sent
    }

    companion object {
        private const val TAG = "PharmaScan/HA"
    }
}
