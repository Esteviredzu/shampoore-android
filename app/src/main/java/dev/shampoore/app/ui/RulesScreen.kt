package dev.shampoore.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shampoore.app.data.AppRuleItem
import dev.shampoore.app.data.CUSTOM_APP_RULE_NAME
import dev.shampoore.app.data.InstalledAppInfo
import dev.shampoore.app.data.RuleItem
import dev.shampoore.app.data.isSupportedRuleUrl
import dev.shampoore.app.data.normalizeRuleUrl
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    rules: List<RuleItem>,
    importing: Boolean,
    refreshing: Boolean,
    updatingRule: String?,
    message: String?,
    appRules: List<AppRuleItem>,
    appRulesImporting: Boolean,
    appRulesRefreshing: Boolean,
    appRulesUpdating: String?,
    installedApps: List<InstalledAppInfo>,
    installedAppsLoading: Boolean,
    onBack: () -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onImportFile: (Uri, String?) -> Unit,
    onImportUrl: (String, String?) -> Unit,
    onRefreshRule: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleAppRule: (String, Boolean) -> Unit,
    onDeleteAppRule: (String) -> Unit,
    onImportAppFile: (Uri) -> Unit,
    onImportAppUrl: (String, String?) -> Unit,
    onRefreshAppRule: (String) -> Unit,
    onRefreshAppRules: () -> Unit,
    onSaveSelectedApps: (Set<String>) -> Unit,
    onSetSelectedAppsEnabled: (Boolean) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onMessageShown: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(0) }
    val appsTab = selectedTab == 1

    var pendingDelete by remember { mutableStateOf<RuleItem?>(null) }
    var pendingAppDelete by remember { mutableStateOf<AppRuleItem?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showAppImportSheet by remember { mutableStateOf(false) }
    var showAppUrlDialog by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val customAppRule = remember(appRules) {
        appRules.firstOrNull { it.isCustomSelection }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportFile(uri, null)
    }

    val appFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportAppFile(uri)
    }

    fun launchFilePicker() {
        if (!importing) filePickerLauncher.launch(arrayOf("*/*"))
    }

    fun launchAppFilePicker() {
        if (!appRulesImporting) appFilePickerLauncher.launch(arrayOf("text/plain"))
    }

    fun openAppPicker() {
        onLoadInstalledApps()
        showAppPicker = true
    }

    LaunchedEffect(appsTab) {
        if (appsTab) onLoadInstalledApps()
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (appsTab) "Apps" else "Traffic rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val busy = if (appsTab) {
                        appRulesRefreshing || appRulesImporting
                    } else {
                        refreshing || importing
                    }
                    IconButton(
                        onClick = { if (appsTab) onRefreshAppRules() else onRefresh() },
                        enabled = !busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Update linked rules"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val busy = if (appsTab) appRulesImporting else importing
            ExtendedFloatingActionButton(
                onClick = {
                    if (!busy) {
                        if (appsTab) showAppImportSheet = true else showImportSheet = true
                    }
                },
                icon = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        when {
                            busy -> "Importing…"
                            appsTab -> "Add"
                            else -> "Import"
                        }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = !appsTab,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = !appsTab) }
                ) {
                    Text("GeoIP")
                }
                SegmentedButton(
                    selected = appsTab,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = appsTab) }
                ) {
                    Text("Bypass")
                }
            }

            if (appsTab) {
                AppsTabContent(
                    rules = appRules,
                    customRule = customAppRule,
                    updatingRule = appRulesUpdating,
                    modifier = Modifier.fillMaxSize(),
                    onToggleRule = onToggleAppRule,
                    onDeleteRule = { rule -> pendingAppDelete = rule },
                    onRefreshRule = onRefreshAppRule,
                    onPickApps = { openAppPicker() },
                    onSetSelectedAppsEnabled = onSetSelectedAppsEnabled
                )
            } else if (rules.isEmpty()) {
                EmptyRulesState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                RulesList(
                    rules = rules,
                    updatingRule = updatingRule,
                    modifier = Modifier.fillMaxSize(),
                    onToggleRule = onToggleRule,
                    onDeleteRule = { rule -> pendingDelete = rule },
                    onRefreshRule = onRefreshRule
                )
            }
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete rule set?") },
            text = {
                Text(
                    buildString {
                        append("\u201C${rule.name}\u201D will be removed from app storage")
                        if (rule.sourceUrl != null) append(" along with its saved download link")
                        append(". You can import the file again at any time.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteRule(rule.name)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Text(
                text = "Add traffic rules",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            ImportOption(
                icon = Icons.Outlined.Description,
                title = "Choose a file",
                subtitle = "Pick a .txt or .json file with CIDR ranges",
                onClick = {
                    showImportSheet = false
                    launchFilePicker()
                }
            )
            ImportOption(
                icon = Icons.Filled.Link,
                title = "Import from link",
                subtitle = "Download rules from a direct URL or a GitHub page",
                onClick = {
                    showImportSheet = false
                    showUrlDialog = true
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUrlDialog) {
        ImportFromUrlDialog(
            importing = importing,
            onDismiss = { showUrlDialog = false },
            onDownload = onImportUrl
        )
    }

    pendingAppDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingAppDelete = null },
            title = { Text("Delete app rule set?") },
            text = {
                Text(
                    buildString {
                        append("\u201C${rule.name}\u201D will be removed from app storage")
                        if (rule.sourceUrl != null) append(" along with its saved download link")
                        append(". You can import the file again at any time.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAppDelete = null
                        onDeleteAppRule(rule.name)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAppDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAppImportSheet) {
        ModalBottomSheet(onDismissRequest = { showAppImportSheet = false }) {
            Text(
                text = "Add app rules",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            ImportOption(
                icon = Icons.Outlined.Description,
                title = "Import .txt file",
                subtitle = "Pick a text file with one package name per line",
                onClick = {
                    showAppImportSheet = false
                    launchAppFilePicker()
                }
            )
            ImportOption(
                icon = Icons.Filled.Link,
                title = "Import from link",
                subtitle = "Download a package list from a direct URL or GitHub",
                onClick = {
                    showAppImportSheet = false
                    showAppUrlDialog = true
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAppUrlDialog) {
        ImportFromUrlDialog(
            importing = appRulesImporting,
            title = "Import apps from link",
            placeholder = "https://github.com/user/repo/blob/main/apps.txt",
            onDismiss = { showAppUrlDialog = false },
            onDownload = onImportAppUrl
        )
    }

    if (showAppPicker) {
        AppPickerSheet(
            installedApps = installedApps,
            loading = installedAppsLoading,
            initialSelection = customAppRule?.packages.orEmpty(),
            onDismiss = { showAppPicker = false },
            onSave = { selected ->
                showAppPicker = false
                onSaveSelectedApps(selected)
            }
        )
    }
}

@Composable
private fun ImportOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ImportFromUrlDialog(
    importing: Boolean,
    title: String = "Import rules from link",
    placeholder: String = "https://github.com/user/repo/blob/main/ru.txt",
    onDismiss: () -> Unit,
    onDownload: (String, String?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("URL") },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    enabled = !importing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name (optional)") },
                    placeholder = { Text("ru.txt") },
                    singleLine = true,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = error
                        ?: "GitHub /blob/ links are converted to raw text automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !importing && url.isNotBlank(),
                onClick = {
                    val trimmed = url.trim()
                    if (!isSupportedRuleUrl(normalizeRuleUrl(trimmed))) {
                        error = "Enter a valid http:// or https:// link"
                    } else {
                        onDownload(trimmed, name.trim().takeIf { it.isNotEmpty() })
                        onDismiss()
                    }
                }
            ) {
                Text(if (importing) "Downloading…" else "Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text("Cancel")
            }
        }
    )
}

private data class RuleCardAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun RuleItemCard(
    icon: ImageVector,
    title: String,
    meta: String,
    badge: String?,
    badgeIcon: ImageVector?,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    actions: List<RuleCardAction>
) {
    var menuOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        SourceBadge(label = badge, icon = badgeIcon)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                            enabled = action.enabled,
                            leadingIcon = {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(
    label: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RulesList(
    rules: List<RuleItem>,
    updatingRule: String?,
    modifier: Modifier = Modifier,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (RuleItem) -> Unit,
    onRefreshRule: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") { RulesSummary(rules) }

        items(rules, key = { it.name }) { rule ->
            RuleCard(
                rule = rule,
                updating = updatingRule == rule.name,
                modifier = Modifier.animateItem(),
                onToggle = { checked -> onToggleRule(rule.name, checked) },
                onDelete = { onDeleteRule(rule) },
                onRefresh = { onRefreshRule(rule.name) }
            )
        }

        item(key = "footer") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Rule files are copied into app storage on import. Rules imported " +
                            "from a link show their source and can be refreshed from the card menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryStats(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            stats.forEachIndexed { index, (value, label) ->
                if (index > 0) {
                    VerticalDivider(
                        modifier = Modifier.height(32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesSummary(rules: List<RuleItem>) {
    val enabled = rules.count { it.enabled }
    val totalCidrs = rules.sumOf { it.cidrCount.toLong() }
    val totalSize = rules.sumOf { it.sizeBytes }
    val numberFormat = remember { NumberFormat.getIntegerInstance() }

    SummaryStats(
        stats = listOf(
            numberFormat.format(enabled) to "Enabled",
            numberFormat.format(totalCidrs) to "CIDRs",
            formatSize(totalSize) to "Storage"
        )
    )
}

@Composable
private fun AppRulesSummary(rules: List<AppRuleItem>) {
    val active = rules.filter { it.enabled }
    val uniqueApps = active.flatMap { it.packages }.toSet().size
    val totalSize = rules.sumOf { it.sizeBytes }
    val numberFormat = remember { NumberFormat.getIntegerInstance() }

    SummaryStats(
        stats = listOf(
            numberFormat.format(uniqueApps) to "Apps",
            numberFormat.format(active.size) to "Active",
            formatSize(totalSize) to "Storage"
        )
    )
}

@Composable
private fun RuleCard(
    rule: RuleItem,
    updating: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val remote = rule.sourceUrl != null
    val tint = if (rule.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val meta = buildString {
        append(numberFormat.format(rule.cidrCount))
        append(if (rule.cidrCount == 1) " CIDR" else " CIDRs")
        if (rule.sizeBytes > 0) {
            append(" \u00B7 ")
            append(formatSize(rule.sizeBytes))
        }
    }
    val actions = buildList {
        if (remote) {
            add(
                RuleCardAction(
                    label = "Refresh",
                    icon = Icons.Filled.Refresh,
                    enabled = !updating,
                    onClick = onRefresh
                )
            )
        }
        add(RuleCardAction(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete))
    }

    RuleItemCard(
        icon = if (remote) Icons.Filled.Link else Icons.Outlined.Description,
        title = rule.name,
        meta = meta,
        badge = if (remote) rule.sourceHost ?: "Link" else "Local file",
        badgeIcon = if (remote) Icons.Filled.Link else Icons.Outlined.Description,
        enabled = rule.enabled,
        tint = tint,
        modifier = modifier,
        onClick = { onToggle(!rule.enabled) },
        onToggle = onToggle,
        actions = actions
    )
}

@Composable
private fun EmptyRulesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "No rule sets yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap Import to pick a .txt or .json file with CIDR ranges, or download " +
                    "a rule set from a link. The file is copied into app storage, so it stays " +
                    "available even if you delete the original.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "\u2014"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun AppsTabContent(
    rules: List<AppRuleItem>,
    customRule: AppRuleItem?,
    updatingRule: String?,
    modifier: Modifier = Modifier,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (AppRuleItem) -> Unit,
    onRefreshRule: (String) -> Unit,
    onPickApps: () -> Unit,
    onSetSelectedAppsEnabled: (Boolean) -> Unit
) {
    val imported = rules.filter { !it.isCustomSelection }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") { AppRulesSummary(rules) }

        item(key = "custom") {
            SelectedAppsCard(
                rule = customRule,
                onToggle = onSetSelectedAppsEnabled,
                onPickApps = onPickApps
            )
        }

        if (imported.isEmpty()) {
            item(key = "empty") { AppsEmptyState() }
        } else {
            items(imported, key = { it.name }) { rule ->
                AppRuleCard(
                    rule = rule,
                    updating = updatingRule == rule.name,
                    modifier = Modifier.animateItem(),
                    onToggle = { checked -> onToggleRule(rule.name, checked) },
                    onDelete = { onDeleteRule(rule) },
                    onRefresh = { onRefreshRule(rule.name) }
                )
            }
        }

        item(key = "footer") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Checked apps and every package from an enabled list below bypass " +
                            "the VPN. Imported lists are copied into app storage; lists from a " +
                            "link can be refreshed from the card menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SelectedAppsCard(
    rule: AppRuleItem?,
    onToggle: (Boolean) -> Unit,
    onPickApps: () -> Unit
) {
    val enabled = rule?.enabled ?: true
    val count = rule?.packageCount ?: 0
    val size = rule?.sizeBytes ?: 0L
    val meta = buildString {
        append(count)
        append(if (count == 1) " app" else " apps")
        if (size > 0) {
            append(" \u00B7 ")
            append(formatSize(size))
        }
    }

    RuleItemCard(
        icon = Icons.Outlined.Apps,
        title = rule?.name ?: CUSTOM_APP_RULE_NAME,
        meta = meta,
        badge = "Manual",
        badgeIcon = Icons.Outlined.Apps,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primary,
        onClick = onPickApps,
        onToggle = onToggle,
        actions = listOf(
            RuleCardAction(
                label = "Choose apps",
                icon = Icons.Outlined.Edit,
                onClick = onPickApps
            )
        )
    )
}

@Composable
private fun AppRuleCard(
    rule: AppRuleItem,
    updating: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    val remote = rule.sourceUrl != null
    val tint = if (rule.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val meta = buildString {
        append(rule.packageCount)
        append(if (rule.packageCount == 1) " app" else " apps")
        if (rule.sizeBytes > 0) {
            append(" \u00B7 ")
            append(formatSize(rule.sizeBytes))
        }
    }
    val actions = buildList {
        if (remote) {
            add(
                RuleCardAction(
                    label = "Refresh",
                    icon = Icons.Filled.Refresh,
                    enabled = !updating,
                    onClick = onRefresh
                )
            )
        }
        add(RuleCardAction(label = "Delete", icon = Icons.Filled.Delete, onClick = onDelete))
    }

    RuleItemCard(
        icon = if (remote) Icons.Filled.Link else Icons.Outlined.Description,
        title = rule.name,
        meta = meta,
        badge = if (remote) rule.sourceHost ?: "Link" else "Local file",
        badgeIcon = if (remote) Icons.Filled.Link else Icons.Outlined.Description,
        enabled = rule.enabled,
        tint = tint,
        modifier = modifier,
        onClick = { onToggle(!rule.enabled) },
        onToggle = onToggle,
        actions = actions
    )
}

@Composable
private fun AppsEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No imported app lists yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap Add to import a .txt package list or download one from a link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}