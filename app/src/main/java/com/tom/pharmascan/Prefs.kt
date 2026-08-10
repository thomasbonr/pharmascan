package com.tom.pharmascan

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mode de connexion à Home Assistant.
 *
 * WEBHOOK est le mode recommandé, et de loin le plus sûr : le secret est un
 * identifiant d'URL qui ne peut déclencher QU'UNE automatisation précise. Un
 * secret qui fuite permet, au pire, d'ajouter des lignes dans la liste de
 * l'armoire à pharmacie.
 *
 * TOKEN utilise un jeton longue durée de l'API REST. Home Assistant ne sait
 * pas restreindre la portée d'un tel jeton : il hérite de TOUS les droits du
 * compte qui l'a créé (serrures, alarme, caméras, configuration...). L'appli
 * n'a besoin que d'un seul appel de service, donc c'est disproportionné. Ce
 * mode reste disponible pour la compatibilité et pour qui ne veut pas créer
 * d'automatisation, mais il ne devrait pas être le choix par défaut d'une
 * nouvelle installation.
 */
enum class ConnectionMode { TOKEN, WEBHOOK }

/**
 * Réglages persistés. Les secrets Home Assistant (jeton longue durée ou
 * identifiant de webhook) sont stockés dans EncryptedSharedPreferences
 * (chiffré par une clé du Keystore Android matériel), jamais en dur dans le
 * code ni en clair sur le disque.
 *
 * Si le chiffrement échoue (ROM exotique, Keystore corrompu), on retombe sur
 * des SharedPreferences classiques plutôt que de crasher — mais on log un
 * avertissement, car le secret est alors en clair dans /data/data. C'est une
 * raison de plus de préférer le mode webhook : le secret qui peut fuiter y
 * est beaucoup moins puissant.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pharmascan_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "Keystore indisponible, repli sur des prefs non chiffrées", e)
        context.getSharedPreferences("pharmascan_plain", Context.MODE_PRIVATE)
    }

    /** Cache non sensible (noms de médicaments), séparé du store chiffré. */
    private val cache: SharedPreferences =
        context.getSharedPreferences("pharmascan_cache", Context.MODE_PRIVATE)

    // ---- Réglages Home Assistant ----------------------------------------

    var haUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_URL, v.trim().trimEnd('/')).apply()

    /**
     * TOKEN par défaut pour ne pas casser les installations existantes lors
     * d'une mise à jour. Les nouvelles installations sont orientées vers le
     * webhook depuis l'écran de réglages.
     */
    var connectionMode: ConnectionMode
        get() = if (prefs.getString(KEY_MODE, MODE_TOKEN) == MODE_WEBHOOK) {
            ConnectionMode.WEBHOOK
        } else {
            ConnectionMode.TOKEN
        }
        set(v) = prefs.edit()
            .putString(KEY_MODE, if (v == ConnectionMode.WEBHOOK) MODE_WEBHOOK else MODE_TOKEN)
            .apply()

    var haToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    /**
     * Identifiant du webhook HA. C'est un secret : qui le connaît peut
     * déclencher l'automatisation. Il est donc stocké dans le même magasin
     * chiffré que le jeton — mais sa portée se limite à cette automatisation.
     */
    var webhookId: String
        get() = prefs.getString(KEY_WEBHOOK_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_WEBHOOK_ID, v.trim()).apply()

    var todoEntity: String
        get() = prefs.getString(KEY_ENTITY, "todo.armoire_a_pharmacie")
            ?: "todo.armoire_a_pharmacie"
        set(v) = prefs.edit().putString(KEY_ENTITY, v.trim()).apply()

    /** Nombre de mois avant péremption à partir duquel on considère "bientôt périmé". */
    var alertMonths: Int
        get() = prefs.getInt(KEY_ALERT_MONTHS, 3)
        set(v) = prefs.edit().putInt(KEY_ALERT_MONTHS, v).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    /**
     * Zoom automatique ML Kit (ZoomSuggestionOptions). Utile quand le code
     * est détecté mais trop petit dans le cadre ; sans effet sur la mise au
     * point elle-même — désactivable si le changement de zoom perturbe plus
     * qu'il n'aide sur un appareil donné.
     */
    var autoZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ZOOM, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_ZOOM, v).apply()

    /**
     * Force le capteur arrière capable de faire le point le plus près.
     * DÉSACTIVÉ par défaut : le capteur par défaut est celui qu'utilise
     * l'appli photo native, et c'est le bon choix sur la grande majorité des
     * appareils. À n'activer que si l'objectif principal ne descend pas assez
     * bas — au risque, sur certains appareils, de tomber sur un objectif dont
     * l'AF est moins bon.
     */
    var macroLensEnabled: Boolean
        get() = prefs.getBoolean(KEY_MACRO_LENS, false)
        set(v) = prefs.edit().putBoolean(KEY_MACRO_LENS, v).apply()

    /** Affiche l'état réel de l'autofocus à l'écran, pour diagnostiquer. */
    var afDiagnosticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AF_DIAGNOSTICS, false)
        set(v) = prefs.edit().putBoolean(KEY_AF_DIAGNOSTICS, v).apply()

    /**
     * En mode webhook, l'entité cible est fixée côté Home Assistant dans
     * l'automatisation : l'appli n'a pas à la connaître, et surtout pas à
     * pouvoir la choisir (voir HomeAssistant.postViaWebhook).
     */
    val isConfigured: Boolean
        get() = when (connectionMode) {
            ConnectionMode.WEBHOOK -> haUrl.isNotBlank() && webhookId.isNotBlank()
            ConnectionMode.TOKEN ->
                haUrl.isNotBlank() && haToken.isNotBlank() && todoEntity.isNotBlank()
        }

    // ---- File d'attente hors-ligne --------------------------------------

    var pendingQueueJson: String
        get() = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        set(v) = prefs.edit().putString(KEY_QUEUE, v).apply()

    // ---- Déduplication ---------------------------------------------------

    fun isAlreadyScanned(boxKey: String): Boolean =
        cache.getStringSet(KEY_SCANNED, emptySet())?.contains(boxKey) == true

    fun rememberScanned(boxKey: String) {
        val current = cache.getStringSet(KEY_SCANNED, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(boxKey)
        // Borne la taille pour éviter une croissance infinie
        val trimmed = if (current.size > 2000) current.toList().takeLast(1500).toSet() else current
        cache.edit().putStringSet(KEY_SCANNED, trimmed).apply()
    }

    fun clearScannedHistory() = cache.edit().remove(KEY_SCANNED).apply()

    // ---- Cache des libellés CIP13 -> nom ---------------------------------

    fun cachedName(cip13: String): String? = cache.getString("name_$cip13", null)

    fun cacheName(cip13: String, name: String) =
        cache.edit().putString("name_$cip13", name).apply()

    // ---- Historique consultable (dossier des boîtes déjà scannées) -------

    data class HistoryEntry(
        val cip13: String,
        val name: String,
        val lot: String?,
        val expiryIso: String?,
        val scannedAt: Long
    )

    /**
     * Distinct de [isAlreadyScanned] : ceci est la liste lisible affichée à
     * l'écran Historique, pas la déduplication (qui ne stocke qu'une clé
     * opaque). Le plus récent en tête. Bornée à 500 entrées.
     */
    fun addHistoryEntry(entry: HistoryEntry) {
        val current = historyEntries().toMutableList()
        current.add(0, entry)
        saveHistoryEntries(if (current.size > 500) current.take(500) else current)
    }

    fun historyEntries(): List<HistoryEntry> {
        val raw = cache.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    cip13 = o.getString("cip13"),
                    name = o.getString("name"),
                    lot = o.optString("lot").ifBlank { null },
                    expiryIso = o.optString("expiry").ifBlank { null },
                    scannedAt = o.optLong("scannedAt")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Historique illisible, remis à zéro", e)
            emptyList()
        }
    }

    fun clearHistoryEntries() = cache.edit().remove(KEY_HISTORY).apply()

    private fun saveHistoryEntries(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("cip13", e.cip13)
                put("name", e.name)
                put("lot", e.lot ?: "")
                put("expiry", e.expiryIso ?: "")
                put("scannedAt", e.scannedAt)
            })
        }
        cache.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "PharmaScan/Prefs"
        private const val KEY_URL = "ha_url"
        private const val KEY_TOKEN = "ha_token"
        private const val KEY_MODE = "connection_mode"
        private const val KEY_WEBHOOK_ID = "webhook_id"
        private const val MODE_TOKEN = "token"
        private const val MODE_WEBHOOK = "webhook"
        private const val KEY_ENTITY = "todo_entity"
        private const val KEY_ALERT_MONTHS = "alert_months"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_AUTO_ZOOM = "auto_zoom_enabled"
        private const val KEY_MACRO_LENS = "macro_lens_enabled"
        private const val KEY_AF_DIAGNOSTICS = "af_diagnostics_enabled"
        private const val KEY_QUEUE = "pending_queue"
        private const val KEY_SCANNED = "scanned_keys"
        private const val KEY_HISTORY = "history_entries"
    }
}
