package dev.shampoore.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class VpnConfig(
    val name: String,
    val source: String,
    val subscriptionId: String? = null,
    val subscriptionName: String? = null,
    val subtitleOverride: String? = null
) {
    val subtitle: String
        get() = subtitleOverride ?: describeSource(source)

    val isEncrypted: Boolean
        get() = sourceHasEncryptionPassword(source)

    val isFromSubscription: Boolean
        get() = subscriptionId != null
}

private val Context.dataStore by preferencesDataStore(name = "shampoore_configs")

class ConfigStore(private val context: Context) {
    private val CONFIGS_KEY = stringPreferencesKey("configs")
    private val SELECTED_KEY = stringPreferencesKey("selected_source")

    val configs: Flow<List<VpnConfig>> = context.dataStore.data
        .map { prefs ->
            prefs[CONFIGS_KEY]?.let { parseConfigs(it) } ?: emptyList()
        }

    suspend fun getSelectedSource(): String? = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[SELECTED_KEY]
    }

    suspend fun setSelectedSource(source: String?) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            if (source == null) prefs.remove(SELECTED_KEY)
            else prefs[SELECTED_KEY] = source
        }
    }

    suspend fun addConfigFromSource(source: String): VpnConfig = withContext(Dispatchers.IO) {
        val name = extractName(source) ?: fallbackName(source)
        val cfg = VpnConfig(name, source)
        context.dataStore.edit { prefs ->
            val current = prefs[CONFIGS_KEY]?.let { parseConfigs(it) } ?: emptyList()
            val updated = current + cfg
            prefs[CONFIGS_KEY] = serializeConfigs(updated)
            prefs[SELECTED_KEY] = cfg.source
        }
        cfg
    }

    suspend fun deleteConfig(source: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            val current = prefs[CONFIGS_KEY]?.let { parseConfigs(it) } ?: emptyList()
            val updated = current.filter { it.source != source }
            prefs[CONFIGS_KEY] = serializeConfigs(updated)
            if (prefs[SELECTED_KEY] == source) {
                prefs.remove(SELECTED_KEY)
            }
        }
    }

    suspend fun updateConfig(oldSource: String, newSource: String): VpnConfig =
        withContext(Dispatchers.IO) {
            val name = extractName(newSource) ?: fallbackName(newSource)
            val cfg = VpnConfig(name, newSource)
            context.dataStore.edit { prefs ->
                val current = prefs[CONFIGS_KEY]?.let { parseConfigs(it) } ?: emptyList()
                val updated = current.map { if (it.source == oldSource) cfg else it }
                prefs[CONFIGS_KEY] = serializeConfigs(updated)
                if (prefs[SELECTED_KEY] == oldSource) {
                    prefs[SELECTED_KEY] = newSource
                }
            }
            cfg
        }

    private fun serializeConfigs(configs: List<VpnConfig>): String {
        val arr = JSONArray()
        configs.forEach { cfg ->
            val obj = JSONObject()
            obj.put("name", cfg.name)
            obj.put("source", cfg.source)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseConfigs(raw: String): List<VpnConfig> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(VpnConfig(obj.getString("name"), obj.getString("source")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fallbackName(source: String): String {
        val t = source.trim()
        return if (t.length > 30) t.take(30) + "..." else t
    }

    private fun extractName(source: String): String? {
        val trimmed = source.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                obj.optString("name").takeIf { it.isNotEmpty() }
                    ?: obj.optString("type").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
        if (trimmed.startsWith("shampoore://")) {
            val hashIdx = trimmed.indexOf('#')
            if (hashIdx >= 0 && hashIdx < trimmed.length - 1) {
                val raw = trimmed.substring(hashIdx + 1)
                return try {
                    URLDecoder.decode(raw, "UTF-8").takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    raw.takeIf { it.isNotEmpty() }
                }
            }
            val qIdx = trimmed.indexOf('?')
            if (qIdx >= 0) {
                val q = trimmed.substring(qIdx + 1).substringBefore('#')
                for (pair in q.split("&")) {
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2 && parts[0] == "type") {
                        return try {
                            URLDecoder.decode(parts[1], "UTF-8").takeIf { it.isNotEmpty() }
                        } catch (_: Exception) {
                            parts[1].takeIf { it.isNotEmpty() }
                        }
                    }
                }
            }
            val legacyMatch = Regex("^shampoore://([^?#]+)").find(trimmed)
            return legacyMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        }
        return null
    }
}

internal fun sourceHasEncryptionPassword(source: String): Boolean {
    val trimmed = source.trim()
    if (trimmed.startsWith("{")) {
        return try {
            val obj = JSONObject(trimmed)
            obj.optString("encryption_password").isNotEmpty() ||
                obj.optJSONObject("advanced")
                    ?.optString("encryption_password")
                    ?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }
    if (trimmed.startsWith("shampoore://")) {
        return try {
            val q = parseQuery(URI(trimmed).rawQuery ?: "")
            !q["pw"].isNullOrEmpty() || !q["encryption_password"].isNullOrEmpty()
        } catch (_: Exception) {
            val query = trimmed.substringAfter('?', "").substringBefore('#')
            val q = parseQuery(query)
            !q["pw"].isNullOrEmpty() || !q["encryption_password"].isNullOrEmpty()
        }
    }
    return false
}

private fun describeSource(source: String): String {
    val trimmed = source.trim()
    var type: String? = null
    var encrypted = false

    if (trimmed.startsWith("{")) {
        try {
            val obj = JSONObject(trimmed)
            type = obj.optString("type").takeIf { it.isNotEmpty() }
                ?: obj.optString("transport_kind").takeIf { it.isNotEmpty() }
            encrypted = obj.optString("encryption_password").isNotEmpty()
        } catch (_: Exception) {
        }
    } else if (trimmed.startsWith("shampoore://")) {
        try {
            val uri = URI(trimmed)
            val q = parseQuery(uri.rawQuery ?: "")
            type = q["type"]?.takeIf { it.isNotEmpty() }
                ?: uri.host?.takeIf { it.isNotEmpty() }
            encrypted = !q["pw"].isNullOrEmpty()
        } catch (_: Exception) {
        }
    }

    val kindLabel = when (type) {
        "yandex" -> "Yandex Board"
        "yandex_telemost_raw" -> "Telemost Raw"
        "yandex_telemost_squares" -> "Telemost Squares"
        null, "" -> "Unknown"
        else -> type
    }

    return if (encrypted) "$kindLabel · Encrypted" else kindLabel
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val out = mutableMapOf<String, String>()
    for (pair in raw.split("&")) {
        val idx = pair.indexOf('=')
        if (idx <= 0) continue
        try {
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            out[k] = v
        } catch (_: Exception) {
        }
    }
    return out
}