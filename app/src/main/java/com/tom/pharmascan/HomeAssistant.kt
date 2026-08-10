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

    /** Appel BLOQUANT. Diagnostique la connexion selon le mode configuré. */
    fun testConnectionBlocking(): String {
        if (!prefs.isConfigured) return "Réglages incomplets"
        return when (prefs.connectionMode) {
            ConnectionMode.WEBHOOK -> testWebhookBlocking()
            ConnectionMode.TOKEN -> testTokenBlocking()
        }
    }

    private fun testTokenBlocking(): String = try {
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

    /**
     * Un webhook n'expose aucune lecture : on ne peut donc pas vérifier que
     * l'automatisation existe ni que l'entité est la bonne. Home Assistant
     * répond d'ailleurs 200 même pour un webhook_id inconnu — c'est
     * délibéré de sa part, pour empêcher d'énumérer les identifiants
     * valides. Un 200 prouve donc seulement que HA est joignable.
     *
     * La seule vérification honnête de bout en bout est visuelle : on envoie
     * un vrai item de test, et l'utilisateur va voir s'il apparaît.
     */
    private fun testWebhookBlocking(): String = try {
        val code = postViaWebhook(
            Item(
                label = "Test PharmaScan",
                dueDate = null,
                description = "Item de test envoyé depuis les réglages. Tu peux le supprimer."
            )
        )
        when {
            code in 200..299 ->
                "OK — Home Assistant a répondu. Vérifie maintenant qu'un item " +
                    "« Test PharmaScan » est apparu dans ta liste : c'est la seule " +
                    "preuve que l'automatisation est bien branchée."
            code == 404 -> "URL joignable mais /api/webhook introuvable (404). Vérifie l'URL."
            code == 405 -> "Méthode refusée (405). Vérifie l'URL de l'instance."
            else -> "Réponse inattendue : HTTP $code"
        }
    } catch (e: Exception) {
        "Injoignable : ${e.message ?: e.javaClass.simpleName}"
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
                code == 401 -> SendResult.Failed("Jeton refusé (401)")
                code == 404 -> SendResult.Failed(
                    when (prefs.connectionMode) {
                        ConnectionMode.WEBHOOK -> "Webhook introuvable (404) — vérifie l'URL"
                        ConnectionMode.TOKEN -> "Entité ${prefs.todoEntity} introuvable (404)"
                    }
                )
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

    private fun postItem(item: Item): Int = when (prefs.connectionMode) {
        ConnectionMode.WEBHOOK -> postViaWebhook(item)
        ConnectionMode.TOKEN -> postViaToken(item)
    }

    /**
     * Mode recommandé. Aucun en-tête d'autorisation : le secret est
     * l'identifiant dans l'URL, et il ne donne accès qu'à l'automatisation
     * qui le porte.
     *
     * On n'envoie DÉLIBÉRÉMENT pas d'entity_id : l'automatisation côté HA
     * fixe elle-même sa liste cible. Laisser l'appli la choisir permettrait à
     * quiconque connaît l'URL d'écrire dans n'importe quelle liste de tâches,
     * ce qui reviendrait à élargir la portée du secret sans raison.
     */
    private fun postViaWebhook(item: Item): Int {
        val payload = JSONObject().apply {
            put("item", item.label)
            item.dueDate?.let { put("due_date", it) }
            item.description?.let { put("description", it) }
        }
        val req = Request.Builder()
            .url("${prefs.haUrl}/api/webhook/${prefs.webhookId}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { return it.code }
    }

    private fun postViaToken(item: Item): Int {
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
