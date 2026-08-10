package com.tom.pharmascan

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Réglages persistés. Le token Home Assistant est un secret de longue durée :
 * il est stocké dans EncryptedSharedPreferences (chiffré par une clé du
 * Keystore Android matériel), jamais en dur dans le code ni en clair sur le
 * disque.
 *
 * Si le chiffrement échoue (ROM exotique, Keystore corrompu), on retombe sur
 * des SharedPreferences classiques plutôt que de crasher — mais on log un
 * avertissement, car le token est alors en clair dans /data/data.
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

    var haToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

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

    val isConfigured: Boolean
        get() = haUrl.isNotBlank() && haToken.isNotBlank() && todoEntity.isNotBlank()

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

    companion object {
        private const val TAG = "PharmaScan/Prefs"
        private const val KEY_URL = "ha_url"
        private const val KEY_TOKEN = "ha_token"
        private const val KEY_ENTITY = "todo_entity"
        private const val KEY_ALERT_MONTHS = "alert_months"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_QUEUE = "pending_queue"
        private const val KEY_SCANNED = "scanned_keys"
    }
}
