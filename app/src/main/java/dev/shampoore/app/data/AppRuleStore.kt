package dev.shampoore.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

internal const val APP_RULES_DIR = "app_rules"

internal const val APP_RULE_SOURCES_FILE = "app_rule_sources.json"

const val CUSTOM_APP_RULE_NAME = "Selected apps"

private val PACKAGE_NAME_REGEX = Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")

fun parsePackageList(raw: String): Set<String> {
    val packages = LinkedHashSet<String>()
    raw.removePrefix("\uFEFF").lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        if (trimmed.startsWith("#") || trimmed.startsWith("//")) return@forEach

        val hash = trimmed.indexOf('#')
        val slashes = trimmed.indexOf("//")
        val commentAt = listOf(hash, slashes).filter { it >= 0 }.minOrNull()
        val candidate = (commentAt?.let { trimmed.substring(0, it) } ?: trimmed).trim()
        if (candidate.isNotEmpty() && PACKAGE_NAME_REGEX.matches(candidate)) {
            packages.add(candidate)
        }
    }
    return packages
}

data class AppRuleItem(
    val name: String,
    val enabled: Boolean,
    val packages: Set<String>,
    val sizeBytes: Long = 0L,
    val sourceUrl: String? = null,
    val isCustomSelection: Boolean = false
) {
    val packageCount: Int get() = packages.size
    val sourceHost: String? get() = sourceUrl?.let { ruleSourceHost(it) }
}

data class AppRuleImportResult(
    val ruleName: String? = null,
    val sourceUrl: String? = null,
    val packageCount: Int = 0,
    val error: String? = null
) {
    val success: Boolean get() = error == null
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

internal data class AppRuleMeta(
    val name: String,
    val enabled: Boolean = true,
    val sourceUrl: String? = null,
    val isCustomSelection: Boolean = false
)

internal fun encodeAppRuleMeta(rules: List<AppRuleMeta>): String {
    if (rules.isEmpty()) return "[]\n"
    return buildString {
        append("[\n")
        rules.forEachIndexed { index, rule ->
            append("  {")
            append("\"name\": ").append(jsonQuote(rule.name))
            append(", \"enabled\": ").append(rule.enabled)
            if (rule.sourceUrl != null) {
                append(", \"sourceUrl\": ").append(jsonQuote(rule.sourceUrl))
            }
            append(", \"isCustomSelection\": ").append(rule.isCustomSelection)
            append('}')
            if (index != rules.lastIndex) append(',')
            append('\n')
        }
        append("]\n")
    }
}

internal fun decodeAppRuleMeta(raw: String): List<AppRuleMeta> {
    val document = parseJson(raw) as? JsonValue.Arr ?: return emptyList()
    val result = mutableListOf<AppRuleMeta>()
    for (node in document.values) {
        val obj = node as? JsonValue.Obj ?: continue
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: continue
        val enabled = (obj.values["enabled"] as? JsonValue.Bool)?.value ?: true
        val sourceUrl = obj.string("sourceUrl")?.takeIf { it.isNotBlank() }
        val custom = (obj.values["isCustomSelection"] as? JsonValue.Bool)?.value ?: false
        if (result.none { it.name == name }) {
            result.add(AppRuleMeta(name, enabled, sourceUrl, custom))
        }
    }
    return result
}









class AppRuleStore private constructor(
    private val rulesDir: File,
    private val metaFile: File,
    private val context: Context?
) {
    constructor(context: Context) : this(
        rulesDir = File(context.filesDir, APP_RULES_DIR),
        metaFile = File(context.filesDir, APP_RULE_SOURCES_FILE),
        context = context
    )

    constructor(
        rulesDir: File,
        metaFile: File = File(rulesDir.parentFile ?: rulesDir, APP_RULE_SOURCES_FILE)
    ) : this(rulesDir, metaFile, null)

    private val lock = Any()

    suspend fun getAppRules(): List<AppRuleItem> = withContext(Dispatchers.IO) {
        synchronized(lock) { loadRulesLocked() }
    }

    
    suspend fun getActiveBypassPackages(): Set<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val active = LinkedHashSet<String>()
            loadRulesLocked().filter { it.enabled }.forEach { active.addAll(it.packages) }
            active
        }
    }

    suspend fun importFromFile(uri: Uri): AppRuleImportResult = withContext(Dispatchers.IO) {
        val ctx = context
            ?: return@withContext AppRuleImportResult(error = "File access is unavailable")
        try {
            rememberReadPermission(ctx, uri)
            val rawName = resolveDisplayName(ctx, uri)
                ?: "imported_${System.currentTimeMillis()}.txt"
            val rawContent = ctx.contentResolver.openInputStream(uri)
                ?.use { input -> input.bufferedReader().readText() }
                ?: return@withContext AppRuleImportResult(error = "Cannot open the selected file")
            importText(rawName, rawContent, sourceUrl = null, isCustomSelection = false)
        } catch (e: Exception) {
            AppRuleImportResult(error = e.message ?: "Import failed")
        }
    }

    



    suspend fun importFromUrl(url: String, customName: String? = null): AppRuleImportResult =
        withContext(Dispatchers.IO) {
            try {
                val normalized = normalizeRuleUrl(url)
                if (!isSupportedRuleUrl(normalized)) {
                    return@withContext AppRuleImportResult(
                        error = "Enter a valid http:// or https:// link"
                    )
                }
                val download = RuleDownloader.download(normalized)
                if (!download.success) {
                    return@withContext AppRuleImportResult(
                        error = download.error ?: "Download failed"
                    )
                }
                val content = download.body.orEmpty()
                remoteRuleContentError(content, download.contentType)?.let { error ->
                    return@withContext AppRuleImportResult(error = error)
                }
                val rawName = customName?.takeIf { it.isNotBlank() }
                    ?: ruleFileNameFromUrl(download.finalUrl.ifBlank { normalized })
                    ?: "imported_${System.currentTimeMillis()}.txt"
                importText(rawName, content, normalized, isCustomSelection = false)
            } catch (e: Exception) {
                AppRuleImportResult(error = e.message ?: "Import failed")
            }
        }


    




    suspend fun refreshRemoteRules(ruleName: String? = null): RemoteUpdateResult =
        withContext(Dispatchers.IO) {
            val all = synchronized(lock) { readMetaLocked() }
            val targets: List<AppRuleMeta> = if (ruleName != null) {
                val meta = all.firstOrNull { it.name == ruleName }
                    ?: return@withContext RemoteUpdateResult(error = "\"$ruleName\" not found")
                if (meta.sourceUrl.isNullOrBlank()) {
                    return@withContext RemoteUpdateResult(
                        error = "\"$ruleName\" is not linked to a source URL"
                    )
                }
                listOf(meta)
            } else {
                all.filter { !it.sourceUrl.isNullOrBlank() }
            }
            if (targets.isEmpty()) return@withContext RemoteUpdateResult()

            val updated = mutableListOf<String>()
            val failures = mutableListOf<String>()
            for (meta in targets) {
                val link = meta.sourceUrl ?: continue
                val normalized = normalizeRuleUrl(link)
                if (!isSupportedRuleUrl(normalized)) {
                    failures += "\"${meta.name}\": the saved link is not a valid http(s) URL"
                    continue
                }
                val download = RuleDownloader.download(normalized)
                if (!download.success) {
                    failures += "\"${meta.name}\": ${download.error ?: "download failed"}"
                    continue
                }
                val content = download.body.orEmpty()
                val contentError = remoteRuleContentError(content, download.contentType)
                if (contentError != null) {
                    failures += "\"${meta.name}\": $contentError"
                    continue
                }
                val packages = parsePackageList(content)
                if (packages.isEmpty()) {
                    failures += "\"${meta.name}\": no package names found"
                    continue
                }
                try {
                    synchronized(lock) {
                        writePackagesLocked(meta.name, packages)
                        val current = readMetaLocked().toMutableList()
                        val index = current.indexOfFirst { it.name == meta.name }
                        
                        val entry = current.getOrNull(index)?.copy(sourceUrl = normalized)
                            ?: AppRuleMeta(meta.name, enabled = true, sourceUrl = normalized)
                        if (index >= 0) current[index] = entry else current += entry
                        writeMetaLocked(current)
                    }
                    updated += meta.name
                } catch (e: Exception) {
                    failures += "\"${meta.name}\": ${e.message ?: "update failed"}"
                }
            }
            RemoteUpdateResult(updated = updated, failures = failures)
        }


    




    suspend fun saveCustomSelectedPackages(
        packages: Set<String>,
        enabled: Boolean? = null
    ): AppRuleImportResult = withContext(Dispatchers.IO) {
        val valid = packages.filter { PACKAGE_NAME_REGEX.matches(it) }.toSet()
        synchronized(lock) {
            writePackagesLocked(CUSTOM_APP_RULE_NAME, valid)
            val meta = readMetaLocked().toMutableList()
            val index = meta.indexOfFirst { it.name == CUSTOM_APP_RULE_NAME }
            val currentEnabled = enabled ?: meta.getOrNull(index)?.enabled ?: true
            val entry = AppRuleMeta(
                name = CUSTOM_APP_RULE_NAME,
                enabled = currentEnabled,
                sourceUrl = null,
                isCustomSelection = true
            )
            if (index >= 0) meta[index] = entry else meta += entry
            writeMetaLocked(meta)
        }
        AppRuleImportResult(ruleName = CUSTOM_APP_RULE_NAME, packageCount = valid.size)
    }

    suspend fun setRuleEnabled(name: String, enabled: Boolean): String? =
        withContext(Dispatchers.IO) {
            var error: String? = null
            synchronized(lock) {
                val meta = readMetaLocked().toMutableList()
                val index = meta.indexOfFirst { it.name == name }
                if (index >= 0) {
                    if (meta[index].enabled != enabled) {
                        meta[index] = meta[index].copy(enabled = enabled)
                        writeMetaLocked(meta)
                    }
                } else if (name == CUSTOM_APP_RULE_NAME) {
                    
                    meta += AppRuleMeta(name, enabled, null, isCustomSelection = true)
                    writeMetaLocked(meta)
                } else {
                    error = "Rule \"$name\" not found"
                }
            }
            error
        }

    suspend fun deleteRule(name: String): String? = withContext(Dispatchers.IO) {
        var error: String? = null
        synchronized(lock) {
            val file = ruleFile(name)
            val meta = readMetaLocked()
            val hadMeta = meta.any { it.name == name }
            val deletedFile = file.exists() && file.delete()
            if (hadMeta || deletedFile) {
                writeMetaLocked(meta.filterNot { it.name == name })
            } else {
                error = "Rule \"$name\" not found"
            }
        }
        error
    }


    private fun importText(
        rawName: String,
        rawContent: String,
        sourceUrl: String?,
        isCustomSelection: Boolean
    ): AppRuleImportResult {
        val name = ruleBaseName(rawName)
        val packages = parsePackageList(rawContent)
        if (packages.isEmpty()) {
            return AppRuleImportResult(
                ruleName = name,
                sourceUrl = sourceUrl,
                error = "No package names found in the file"
            )
        }
        synchronized(lock) {
            writePackagesLocked(name, packages)
            val meta = readMetaLocked().toMutableList()
            val index = meta.indexOfFirst { it.name == name }
            val entry = AppRuleMeta(
                name = name,
                enabled = meta.getOrNull(index)?.enabled ?: true,
                sourceUrl = sourceUrl,
                isCustomSelection = isCustomSelection || name == CUSTOM_APP_RULE_NAME
            )
            if (index >= 0) meta[index] = entry else meta += entry
            writeMetaLocked(meta)
        }
        return AppRuleImportResult(
            ruleName = name,
            sourceUrl = sourceUrl,
            packageCount = packages.size
        )
    }

    private fun loadRulesLocked(): List<AppRuleItem> {
        val items = LinkedHashMap<String, AppRuleItem>()
        readMetaLocked().forEach { meta ->
            items[meta.name] = AppRuleItem(
                name = meta.name,
                enabled = meta.enabled,
                packages = readPackagesLocked(meta.name),
                sizeBytes = ruleFileSize(meta.name),
                sourceUrl = meta.sourceUrl,
                isCustomSelection = meta.isCustomSelection
            )
        }
        ruleFilesLocked().forEach { file ->
            val name = ruleBaseName(file.name)
            if (items.containsKey(name)) return@forEach
            items[name] = AppRuleItem(
                name = name,
                enabled = true,
                packages = parsePackageList(file.readText(Charsets.UTF_8)),
                sizeBytes = file.length(),
                isCustomSelection = name == CUSTOM_APP_RULE_NAME
            )
        }
        return items.values.sortedWith(
            compareByDescending<AppRuleItem> { it.isCustomSelection }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun ruleFilesLocked(): List<File> =
        ensureRulesDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()

    private fun readPackagesLocked(name: String): Set<String> {
        val file = ruleFile(name)
        if (!file.exists()) return emptySet()
        return runCatching { parsePackageList(file.readText(Charsets.UTF_8)) }
            .getOrDefault(emptySet())
    }

    private fun writePackagesLocked(name: String, packages: Set<String>) {
        val dir = ensureRulesDir()
        val base = sanitizeRuleBaseName(name)
        val dest = File(dir, "$base.txt")
        val tmp = File(dir, ".$base.txt.tmp")
        val content = if (packages.isEmpty()) {
            ""
        } else {
            packages.sorted().joinToString("\n", postfix = "\n")
        }
        FileOutputStream(tmp).use { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun ruleFile(name: String): File =
        File(ensureRulesDir(), "${sanitizeRuleBaseName(name)}.txt")

    private fun ruleFileSize(name: String): Long = ruleFile(name).length()

    private fun ensureRulesDir(): File = rulesDir.apply { if (!exists()) mkdirs() }

    private fun readMetaLocked(): List<AppRuleMeta> {
        if (!metaFile.exists()) return emptyList()
        return runCatching { decodeAppRuleMeta(metaFile.readText(Charsets.UTF_8)) }
            .getOrDefault(emptyList())
    }

    private fun writeMetaLocked(rules: List<AppRuleMeta>) {
        val parent = metaFile.absoluteFile.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, metaFile.name + ".tmp")
        try {
            tmp.writeText(encodeAppRuleMeta(rules), Charsets.UTF_8)
            if (!tmp.renameTo(metaFile)) {
                tmp.copyTo(metaFile, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun rememberReadPermission(context: Context, uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
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
}

