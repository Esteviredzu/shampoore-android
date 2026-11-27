package dev.shampoore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.shampoore.app.data.TransportField
import dev.shampoore.app.data.TransportSchema
import dev.shampoore.app.data.isBooleanTransportField
import dev.shampoore.app.data.isVisibleInManualEditor
import kotlinx.coroutines.delay
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

private data class SubflowState(
    val kind: String,
    val fieldValues: Map<String, String>,
    val rateLimitKb: String,
    val advancedJson: String = "",
    val boolKeys: Set<String> = emptySet()
)

private data class ParsedConfig(
    val name: String,
    val kind: String,
    val password: String,
    val fields: Map<String, String>,
    val multiStrategy: String,
    val multiSubflows: List<SubflowState>,
    val advancedJson: String,
    val baseJson: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    isEdit: Boolean,
    initialSource: String?,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val schemas = remember {
        try {
            TransportSchema.parseList(Mobile.getTransportSchemas())
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val flatSchemas = schemas.filter { it.kind != "multi" }

    var name by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(flatSchemas.firstOrNull()?.kind ?: "yandex") }
    var fieldValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var password by remember { mutableStateOf("") }
    var advancedJson by remember { mutableStateOf("") }
    var baseJson by remember { mutableStateOf("") }

    var multiStrategy by remember { mutableStateOf("flowmux") }
    var multiSubflows by remember { mutableStateOf<List<SubflowState>>(emptyList()) }

    var errorText by remember { mutableStateOf<String?>(null) }

    val currentSchema = schemas.find { it.kind == selectedKind }
    val isMulti = selectedKind == "multi"

    fun applyParsed(parsed: ParsedConfig) {
        name = parsed.name
        if (schemas.any { it.kind == parsed.kind }) {
            selectedKind = parsed.kind
        }
        password = parsed.password
        fieldValues = parsed.fields
        advancedJson = parsed.advancedJson
        baseJson = parsed.baseJson
        if (parsed.kind == "multi") {
            multiStrategy = parsed.multiStrategy
            multiSubflows = parsed.multiSubflows
        }
    }

    LaunchedEffect(initialSource) {
        if (initialSource != null) {
            try {
                applyParsed(parseQrPayload(initialSource, schemas))
            } catch (e: Exception) {
                errorText = e.message ?: "Failed to parse source"
            }
        }
    }

    LaunchedEffect(errorText) {
        if (errorText != null) {
            delay(4000)
            errorText = null
        }
    }

    val saveEnabled = when {
        isMulti -> multiSubflows.isNotEmpty()
        currentSchema == null -> false
        else -> {
            val visibleFields = currentSchema.fields.filter { it.isVisibleInManualEditor() }
            visibleFields.isEmpty() || visibleFields
                .filter { it.required && !isBooleanTransportField(it, fieldValues[it.key] ?: "") }
                .all { fieldValues[it.key]?.isNotBlank() == true }
        }
    }

    fun buildSource(): String {
        val root = if (baseJson.isNotBlank()) {
            try {
                JSONObject(baseJson)
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        root.remove("name")
        if (name.isNotBlank()) root.put("name", name)
        root.put("type", selectedKind)

        root.remove("transport")
        if (isMulti) {
            val transportsArr = JSONArray()
            multiSubflows.forEach { sub ->
                val subObj = JSONObject()
                subObj.put("type", sub.kind)
                val subSchema = flatSchemas.find { it.kind == sub.kind }
                val subTransport = JSONObject()
                sub.fieldValues.forEach { (k, v) ->
                    if (v.isNotBlank()) {
                        subTransport.putFieldValue(
                            key = k,
                            value = v,
                            field = subSchema?.fields?.find { it.key == k },
                            forceBoolean = k in sub.boolKeys
                        )
                    }
                }
                subObj.put("transport", subTransport)
                if (sub.advancedJson.isNotBlank()) {
                    try {
                        subObj.put("advanced", JSONObject(sub.advancedJson))
                    } catch (_: Exception) {
                    }
                }
                val rate = sub.rateLimitKb.toIntOrNull() ?: 0
                if (rate > 0) subObj.put("rate_limit_kb", rate)
                transportsArr.put(subObj)
            }
            root.put("transport", JSONObject().apply {
                put("strategy", multiStrategy)
                put("transports", transportsArr)
            })
        } else {
            val baseTransport = try {
                JSONObject(baseJson).optJSONObject("transport")
            } catch (_: Exception) {
                null
            }
            val transport = JSONObject()
            fieldValues.forEach { (k, v) ->
                if (v.isNotBlank()) {
                    transport.putFieldValue(
                        key = k,
                        value = v,
                        field = currentSchema?.fields?.find { it.key == k },
                        forceBoolean = baseTransport?.opt(k) is Boolean
                    )
                }
            }
            root.put("transport", transport)
            root.remove("advanced")
            if (advancedJson.isNotBlank()) {
                try {
                    root.put("advanced", JSONObject(advancedJson))
                } catch (_: Exception) {
                }
            }
        }
        root.remove("encryption_password")
        if (password.isNotBlank()) root.put("encryption_password", password)
        return root.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Config" else "New Config") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val src = buildSource()
                            if (src.isNotBlank()) onSave(src)
                        },
                        enabled = saveEnabled
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Save",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (errorText != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorText!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            FormContent(
                name = name,
                onNameChange = { name = it },
                password = password,
                onPasswordChange = { password = it },
                schemas = schemas,
                selectedKind = selectedKind,
                onKindChange = { newKind ->
                    if (newKind != selectedKind) {
                        selectedKind = newKind
                        fieldValues = emptyMap()
                        advancedJson = ""
                        if (newKind != "multi") {
                            multiStrategy = "flowmux"
                            multiSubflows = emptyList()
                        }
                    }
                },
                currentSchema = currentSchema,
                fieldValues = fieldValues,
                onFieldChange = { key, value ->
                    fieldValues = fieldValues + (key to value)
                },
                isMulti = isMulti,
                flatSchemas = flatSchemas,
                multiStrategy = multiStrategy,
                onMultiStrategyChange = { multiStrategy = it },
                multiSubflows = multiSubflows,
                onMultiSubflowsChange = { multiSubflows = it }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormContent(
    name: String,
    onNameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    schemas: List<TransportSchema>,
    selectedKind: String,
    onKindChange: (String) -> Unit,
    currentSchema: TransportSchema?,
    fieldValues: Map<String, String>,
    onFieldChange: (String, String) -> Unit,
    isMulti: Boolean,
    flatSchemas: List<TransportSchema>,
    multiStrategy: String,
    onMultiStrategyChange: (String) -> Unit,
    multiSubflows: List<SubflowState>,
    onMultiSubflowsChange: (List<SubflowState>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            placeholder = { Text("my-config") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        TransportPicker(
            schemas = schemas,
            selectedKind = selectedKind,
            onSelect = onKindChange
        )

        if (isMulti) {
            MultiEditor(
                flatSchemas = flatSchemas,
                strategy = multiStrategy,
                onStrategyChange = onMultiStrategyChange,
                subflows = multiSubflows,
                onSubflowsChange = onMultiSubflowsChange
            )
        } else if (currentSchema != null &&
            currentSchema.fields.any { it.isVisibleInManualEditor() }
        ) {
            Text(
                "Transport parameters",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            currentSchema.fields
                .filter { it.isVisibleInManualEditor() }
                .forEach { field ->
                    TransportFieldEditor(
                        field = field,
                        value = fieldValues[field.key] ?: "",
                        onValueChange = { onFieldChange(field.key, it) }
                    )
                }
        }

        Text(
            "Security",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Encryption Password (optional)") },
            supportingText = { Text("Shared secret for XChaCha20-Poly1305") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TransportFieldEditor(
    field: TransportField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp)
) {
    val labelText = if (field.required) "${field.label} *" else field.label
    if (isBooleanTransportField(field, value)) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 10.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (field.hint.isNotEmpty()) {
                        Text(
                            text = field.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = value.equals("true", ignoreCase = true),
                    onCheckedChange = { checked ->
                        onValueChange(if (checked) "true" else "false")
                    }
                )
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(labelText) },
            placeholder = if (field.placeholder.isNotEmpty()) {
                { Text(field.placeholder) }
            } else null,
            supportingText = if (field.hint.isNotEmpty()) {
                { Text(field.hint) }
            } else null,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            singleLine = true,
            visualTransformation = if (field.type == "password") {
                PasswordVisualTransformation()
            } else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.type == "password") KeyboardType.Password
                else KeyboardType.Text
            )
        )
    }
}

private fun JSONObject.putFieldValue(
    key: String,
    value: String,
    field: TransportField?,
    forceBoolean: Boolean
) {
    val asBoolean = forceBoolean || (field != null && isBooleanTransportField(field, value))
    put(key, if (asBoolean) value.equals("true", ignoreCase = true) else value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportPicker(
    schemas: List<TransportSchema>,
    selectedKind: String,
    onSelect: (String) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val current = schemas.find { it.kind == selectedKind }
    val currentDisplay = current?.displayName ?: selectedKind

    Surface(
        onClick = { showSheet = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transport",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentDisplay,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                Text(
                    text = "Choose transport",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                schemas.forEach { schema ->
                    val selected = schema.kind == selectedKind
                    Surface(
                        onClick = {
                            onSelect(schema.kind)
                            showSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(50),
                        color = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        tonalElevation = if (selected) 4.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                            Text(
                                text = schema.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MultiEditor(
    flatSchemas: List<TransportSchema>,
    strategy: String,
    onStrategyChange: (String) -> Unit,
    subflows: List<SubflowState>,
    onSubflowsChange: (List<SubflowState>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Strategy",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = strategy == "flowmux",
                onClick = { onStrategyChange("flowmux") },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) {
                Text("flowmux")
            }
            SegmentedButton(
                selected = strategy == "bond",
                onClick = { onStrategyChange("bond") },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) {
                Text("bond")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Transports",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        subflows.forEachIndexed { index, subflow ->
            SubflowCard(
                index = index,
                subflow = subflow,
                flatSchemas = flatSchemas,
                onChange = { updated ->
                    onSubflowsChange(
                        subflows.toMutableList().apply { set(index, updated) }
                    )
                },
                onDelete = {
                    onSubflowsChange(
                        subflows.toMutableList().apply { removeAt(index) }
                    )
                }
            )
        }

        OutlinedButton(
            onClick = {
                val first = flatSchemas.firstOrNull() ?: return@OutlinedButton
                onSubflowsChange(
                    subflows + SubflowState(
                        kind = first.kind,
                        fieldValues = emptyMap(),
                        rateLimitKb = "0"
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add transport")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubflowCard(
    index: Int,
    subflow: SubflowState,
    flatSchemas: List<TransportSchema>,
    onChange: (SubflowState) -> Unit,
    onDelete: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val schema = flatSchemas.find { it.kind == subflow.kind }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )
                Surface(
                    onClick = { showPicker = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = schema?.displayName ?: subflow.kind,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            schema?.fields
                ?.filter { it.isVisibleInManualEditor() }
                ?.forEach { field ->
                    TransportFieldEditor(
                        field = field,
                        value = subflow.fieldValues[field.key] ?: "",
                        onValueChange = { newVal ->
                            onChange(
                                subflow.copy(
                                    fieldValues = subflow.fieldValues + (field.key to newVal)
                                )
                            )
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

            OutlinedTextField(
                value = subflow.rateLimitKb,
                onValueChange = { newVal ->
                    onChange(subflow.copy(rateLimitKb = newVal.filter { it.isDigit() }))
                },
                label = { Text("Rate limit (KB/s, 0 = unlimited)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                Text(
                    text = "Transport type",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                flatSchemas.forEach { s ->
                    val selected = s.kind == subflow.kind
                    Surface(
                        onClick = {
                            onChange(
                                SubflowState(
                                    kind = s.kind,
                                    fieldValues = emptyMap(),
                                    rateLimitKb = subflow.rateLimitKb,
                                    advancedJson = ""
                                )
                            )
                            showPicker = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(50),
                        color = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                            Text(
                                text = s.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun parseQrPayload(raw: String, schemas: List<TransportSchema>): ParsedConfig {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("Source is empty")

    var name = ""
    var kind = ""
    var password = ""
    var fields: Map<String, String> = emptyMap()
    var strategy = "flowmux"
    var subflows: List<SubflowState> = emptyList()
    var advancedJson = ""
    var baseJson = ""

    when {
        trimmed.startsWith("{") -> {
            val obj = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("Source contains invalid JSON")
            }
            baseJson = obj.toString()
            name = obj.optString("name", "")
            kind = obj.optString("type", "")
            password = obj.optString("encryption_password", "")

            obj.optJSONObject("advanced")?.let {
                advancedJson = it.toString()
            }

            val transport = obj.optJSONObject("transport")
            if (kind == "multi") {
                if (transport != null) {
                    strategy = transport.optString("strategy", "flowmux")
                    val arr = transport.optJSONArray("transports")
                    val list = mutableListOf<SubflowState>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val sub = arr.optJSONObject(i) ?: continue
                            val subTransport = sub.optJSONObject("transport")
                            val subFields = mutableMapOf<String, String>()
                            val boolKeys = mutableSetOf<String>()
                            subTransport?.keys()?.forEach { k ->
                                subFields[k] = subTransport.optString(k, "")
                                if (subTransport.opt(k) is Boolean) boolKeys.add(k)
                            }
                            val subAdv = sub.optJSONObject("advanced")?.toString() ?: ""
                            list.add(
                                SubflowState(
                                    kind = sub.optString("type", ""),
                                    fieldValues = subFields,
                                    rateLimitKb = sub.optInt("rate_limit_kb", 0).toString(),
                                    advancedJson = subAdv,
                                    boolKeys = boolKeys
                                )
                            )
                        }
                    }
                    subflows = list
                }
            } else if (transport != null) {
                val m = mutableMapOf<String, String>()
                transport.keys().forEach { k ->
                    m[k] = transport.optString(k, "")
                }
                fields = m
            }
        }
        trimmed.startsWith("shampoore://") -> {
            val u = try {
                java.net.URI(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("Source contains invalid URL")
            }
            val query = u.rawQuery ?: ""
            val params = mutableMapOf<String, String>()
            if (query.isNotEmpty()) {
                for (pair in query.split("&")) {
                    val idx = pair.indexOf('=')
                    if (idx <= 0) continue
                    try {
                        val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        params[k] = v
                    } catch (_: Exception) {
                    }
                }
            }
            kind = params["type"] ?: u.host ?: ""
            password = params["pw"] ?: ""
            val f = mutableMapOf<String, String>()
            val advMap = mutableMapOf<String, String>()
            for ((k, v) in params) {
                when {
                    k == "type" || k == "pw" -> {
                    }
                    k.startsWith("a.") -> {
                        val realKey = k.removePrefix("a.")
                        if (realKey.isNotEmpty()) advMap[realKey] = v
                    }
                    else -> f[k] = v
                }
            }
            fields = f
            if (advMap.isNotEmpty()) {
                val adv = JSONObject()
                advMap.forEach { (k, v) -> adv.put(k, v) }
                advancedJson = adv.toString()
            }
            val frag = u.fragment
            if (!frag.isNullOrEmpty()) {
                try {
                    name = URLDecoder.decode(frag, "UTF-8")
                } catch (_: Exception) {
                    name = frag
                }
            }
        }
        else -> throw IllegalArgumentException("Unrecognized source")
    }

    if (kind.isEmpty()) throw IllegalArgumentException("Missing transport type")

    if (schemas.isNotEmpty() && schemas.none { it.kind == kind }) {
        throw IllegalArgumentException("Unknown transport: $kind")
    }

    return ParsedConfig(
        name = name,
        kind = kind,
        password = password,
        fields = fields,
        multiStrategy = strategy,
        multiSubflows = subflows,
        advancedJson = advancedJson,
        baseJson = baseJson
    )
}