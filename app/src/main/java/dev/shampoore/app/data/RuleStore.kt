package dev.shampoore.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val RULE_STATE_FILE = "rules.state.json"

data class RuleItem(
    val name: String,
    val enabled: Boolean,
    val cidrCount: Int,
    val sizeBytes: Long = 0L,
    val sourceUrl: String? = null,
    val sourceHost: String? = null
)

data class RuleImportResult(
    val fileName: String? = null,
    val sourceUrl: String? = null,
    val error: String? = null
) {
    val success: Boolean get() = error == null
}

data class RemoteUpdateResult(
    val updated: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val error: String? = null
) {
    val success: Boolean get() = error == null && failures.isEmpty()
}

class RuleStore(private val context: Context) {
    private val sourceStore = RuleSourceStore(File(context.filesDir, RULE_SOURCES_FILE))

    val rulesDir: File
        get() = File(context.filesDir, "rules").apply {
            if (!exists()) mkdirs()
        }

    suspend fun initEngine() = withContext(Dispatchers.IO) {
        ensureEngine()
    }

    suspend fun getRulesList(): List<RuleItem> = withContext(Dispatchers.IO) {
        ensureEngine()
        
        
        val jsonStr = runCatching { Mobile.getRules() }.getOrNull() ?: "[]"
        val links = runCatching { sourceStore.snapshot() }.getOrDefault(emptyMap())
        parseRulesJson(jsonStr, links)
    }

    



    suspend fun reloadRulesFromDisk(): String? = withContext(Dispatchers.IO) {
        ensureEngine()
        reloadRulesNow()
    }

    private fun reloadRulesNow(): String? {
        val result = runCatching { Mobile.reloadRules() }
            .getOrElse { return it.message ?: "Failed to reload rules" }
        
        
        return if (result.trimStart().startsWith("[")) null
        else result.ifBlank { "Failed to reload rules" }
    }

    suspend fun setRuleEnabled(name: String, enabled: Boolean): String? = withContext(Dispatchers.IO) {
        ensureEngine()
        val message = runCatching { Mobile.setRuleEnabled(name, enabled) }
            .getOrElse { it.message ?: "Failed to update rule" }
        message.takeIf { it.isNotBlank() }
    }

    suspend fun importRuleFile(sourceUri: Uri, displayName: String?): RuleImportResult =
        withContext(Dispatchers.IO) {
            try {
                rememberReadPermission(sourceUri)
                val rawName = displayName?.takeIf { it.isNotBlank() }
                    ?: resolveDisplayName(sourceUri)
                    ?: "imported_${System.currentTimeMillis()}.txt"

                val rawContent = context.contentResolver.openInputStream(sourceUri)
                    ?.use { input -> input.bufferedReader().readText() }
                    ?: return@withContext RuleImportResult(
                        error = "Cannot open the selected file"
                    )

                val prepared = prepareRuleContent(rawName, rawContent)
                val dest = writeRuleAtomically(prepared.baseName, prepared.ext, prepared.content)
                
                sourceStore.remove(prepared.baseName)

                ensureEngine()
                val reloadError = reloadRulesNow()
                RuleImportResult(
                    fileName = dest.name,
                    error = reloadError?.let {
                        "Imported ${dest.name}, but rules were not reloaded: $it"
                    }
                )
            } catch (e: Exception) {
                RuleImportResult(error = e.message ?: "Import failed")
            }
        }

    



    suspend fun importRuleFromUrl(url: String, customName: String? = null): RuleImportResult =
        withContext(Dispatchers.IO) {
            try {
                val normalized = normalizeRuleUrl(url)
                if (!isSupportedRuleUrl(normalized)) {
                    return@withContext RuleImportResult(
                        error = "Enter a valid http:// or https:// link"
                    )
                }

                val download = RuleDownloader.download(normalized)
                if (!download.success) {
                    return@withContext RuleImportResult(
                        error = download.error ?: "Download failed"
                    )
                }
                val rawContent = download.body.orEmpty()
                remoteRuleContentError(rawContent, download.contentType)?.let { error ->
                    return@withContext RuleImportResult(error = error)
                }

                val rawName = customName?.takeIf { it.isNotBlank() }
                    ?: ruleFileNameFromUrl(download.finalUrl.ifBlank { normalized })
                    ?: "imported_${System.currentTimeMillis()}.txt"
                val prepared = prepareRuleContent(rawName, rawContent)
                val dest = writeRuleAtomically(prepared.baseName, prepared.ext, prepared.content)
                sourceStore.put(prepared.baseName, normalized)

                ensureEngine()
                val reloadError = reloadRulesNow()
                RuleImportResult(
                    fileName = dest.name,
                    sourceUrl = normalized,
                    error = reloadError?.let {
                        "Imported ${dest.name}, but rules were not reloaded: $it"
                    }
                )
            } catch (e: Exception) {
                RuleImportResult(error = e.message ?: "Import failed")
            }
        }

    



    suspend fun updateRemoteRules(ruleName: String? = null): RemoteUpdateResult =
        withContext(Dispatchers.IO) {
            val all = runCatching { sourceStore.snapshot() }.getOrDefault(emptyMap())
            val targets: Map<String, String> = if (ruleName != null) {
                val link = all[ruleName]
                    ?: return@withContext RemoteUpdateResult(
                        error = "\"$ruleName\" is not linked to a source URL"
                    )
                mapOf(ruleName to link)
            } else {
                all
            }
            if (targets.isEmpty()) return@withContext RemoteUpdateResult()

            val updated = mutableListOf<String>()
            val failures = mutableListOf<String>()
            for ((name, link) in targets) {
                val normalized = normalizeRuleUrl(link)
                if (!isSupportedRuleUrl(normalized)) {
                    failures += "\"$name\": the saved link is not a valid http(s) URL"
                    continue
                }
                val download = RuleDownloader.download(normalized)
                if (!download.success) {
                    failures += "\"$name\": ${download.error ?: "download failed"}"
                    continue
                }
                val content = download.body.orEmpty()
                val contentError = remoteRuleContentError(content, download.contentType)
                if (contentError != null) {
                    failures += "\"$name\": $contentError"
                    continue
                }
                try {
                    val ext = remoteRuleExtension(content)
                    val normalizedContent = normalizeRuleContent(content, ext)
                    val validationError = validateRuleContent(normalizedContent, ext)
                    if (validationError != null) {
                        failures += "\"$name\": $validationError"
                    } else {
                        writeRuleAtomically(name, ext, normalizedContent)
                        sourceStore.put(name, normalized)
                        updated += name
                    }
                } catch (e: Exception) {
                    failures += "\"$name\": ${e.message ?: "update failed"}"
                }
            }

            var error: String? = null
            if (updated.isNotEmpty()) {
                ensureEngine()
                error = reloadRulesNow()?.let { "Rules updated, but were not reloaded: $it" }
            }
            RemoteUpdateResult(updated = updated, failures = failures, error = error)
        }

    suspend fun deleteRule(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = rulesDir
            var deleted = false
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || file.name == RULE_STATE_FILE) return@forEach
                val (base, ext) = splitRuleFileName(file.name)
                if (ext !in RULE_EXTENSIONS) return@forEach
                if (base == name || sanitizeRuleBaseName(base) == name) {
                    if (file.delete()) deleted = true
                }
            }
            
            sourceStore.remove(name)
            ensureEngine()
            if (!deleted) return@withContext "Rule file not found"
            reloadRulesNow()?.let { return@withContext "Rule deleted, but rules were not reloaded: $it" }
            null
        } catch (e: Exception) {
            e.message ?: "Delete failed"
        }
    }

    




    private fun ensureEngine() {
        if (engineInitialized) return
        synchronized(ENGINE_LOCK) {
            if (engineInitialized) return
            val dir = rulesDir.absolutePath
            runCatching { Mobile.initRules(dir) }.onSuccess { engineInitialized = true }
        }
    }

    private fun rememberReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val projected = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return projected?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private class PreparedRule(val baseName: String, val ext: String, val content: String)

    



    private fun prepareRuleContent(rawName: String, rawContent: String): PreparedRule {
        val baseName = ruleBaseName(rawName)
        var ext = detectRuleExtension(rawName, rawContent)
        var content = normalizeRuleContent(rawContent, ext)
        var validationError = validateRuleContent(content, ext)

        
        if (validationError != null && ext == ".json") {
            val first = rawContent.firstOrNull { !it.isWhitespace() }
            if (first != '{' && first != '[' && validateRuleContent(rawContent, ".txt") == null) {
                ext = ".txt"
                content = rawContent
                validationError = null
            }
        }
        validationError?.let { throw IllegalArgumentException(it) }
        return PreparedRule(baseName, ext, content)
    }

    



    private fun writeRuleAtomically(baseName: String, ext: String, content: String): File {
        val dir = rulesDir
        val dest = File(dir, baseName + ext)
        val tmp = File(dir, ".$baseName$ext.tmp")
        FileOutputStream(tmp).use { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        RULE_EXTENSIONS.filter { it != ext }.forEach { otherExt ->
            File(dir, baseName + otherExt).takeIf { it.exists() }?.delete()
        }
        return dest
    }

    private fun remoteRuleExtension(content: String): String {
        val first = content.firstOrNull { !it.isWhitespace() }
        return if (first == '{' || first == '[') ".json" else ".txt"
    }

    private fun existingRuleFile(name: String): File? =
        rulesDir.listFiles()?.firstOrNull { file ->
            if (!file.isFile || file.name == RULE_STATE_FILE) return@firstOrNull false
            val (base, ext) = splitRuleFileName(file.name)
            ext in RULE_EXTENSIONS && (base == name || sanitizeRuleBaseName(base) == name)
        }

    
    private fun normalizeRuleContent(content: String, ext: String): String {
        if (ext != ".json") return content
        val trimmed = content.trim()
        if (!trimmed.startsWith("[")) return content
        return runCatching {
            val source = JSONArray(trimmed)
            val cidrs = JSONArray()
            for (i in 0 until source.length()) {
                val value = source.optString(i, "").trim()
                if (value.isNotEmpty()) cidrs.put(value)
            }
            JSONObject().put("cidrs", cidrs).toString(2)
        }.getOrDefault(content)
    }

    private fun validateRuleContent(content: String, ext: String): String? {
        if (content.isBlank()) return "The selected file is empty"
        if (ext == ".json") {
            val count = runCatching {
                val trimmed = content.trim()
                if (trimmed.startsWith("{")) {
                    JSONObject(trimmed).optJSONArray("cidrs")?.length() ?: 0
                } else {
                    0
                }
            }.getOrDefault(0)
            if (count <= 0) {
                return "The JSON file must contain a non-empty \"cidrs\" array"
            }
            return null
        }
        val hasEntries = content.lineSequence().any { line ->
            val text = line.trim()
            text.isNotEmpty() && !text.startsWith("#")
        }
        if (!hasEntries) return "No CIDR ranges found in the file"
        return null
    }

    private fun ruleFileSize(name: String): Long = existingRuleFile(name)?.length() ?: 0L

    private fun parseRulesJson(json: String, links: Map<String, String>): List<RuleItem> {
        val list = mutableListOf<RuleItem>()
        try {
            val clean = json.trim()
            if (!clean.startsWith("[")) return list
            val jsonArray = JSONArray(clean)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("name", "")
                val enabled = obj.optBoolean("enabled", false)
                val cidrs = obj.optJSONArray("cidrs")
                val count = cidrs?.length() ?: 0
                if (name.isNotEmpty()) {
                    val sourceUrl = links[name]
                    list.add(
                        RuleItem(
                            name = name,
                            enabled = enabled,
                            cidrCount = count,
                            sizeBytes = ruleFileSize(name),
                            sourceUrl = sourceUrl,
                            sourceHost = sourceUrl?.let { ruleSourceHost(it) }
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    private companion object {
        private val ENGINE_LOCK = Any()

        @Volatile
        private var engineInitialized = false
    }
}
