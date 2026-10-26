package dev.shampoore.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.viewmodel.MainViewModel
import dev.shampoore.app.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: MainViewModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val selectedConfig by viewModel.selectedConfig.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<VpnConfig?>(null) }
    var deletingConfig by remember { mutableStateOf<VpnConfig?>(null) }

    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting
    val isDisconnecting = vpnState is VpnState.Disconnecting

    val buttonEnabled = when (vpnState) {
        is VpnState.Connected -> true
        is VpnState.Connecting -> true
        is VpnState.Disconnecting -> false
        is VpnState.Disconnected -> selectedConfig != null
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val gradientColors = when {
            isConnected -> listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary
            )
            !buttonEnabled -> listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            )
            else -> listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surface
            )
        }
        val iconTint = when {
            isConnected -> MaterialTheme.colorScheme.onPrimary
            !buttonEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(240.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(brush = Brush.linearGradient(colors = gradientColors))
                .clickable(
                    enabled = buttonEnabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    when (vpnState) {
                        is VpnState.Connected -> onDisconnect()
                        is VpnState.Connecting -> onDisconnect()
                        is VpnState.Disconnected ->
                            selectedConfig?.let { onConnect(it.source) }
                        else -> Unit
                    }
                }
        ) {
            if (isConnecting || isDisconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(100.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 6.dp
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Filled.Shield else Icons.Filled.PowerSettingsNew,
                    contentDescription = "Connect",
                    modifier = Modifier.size(100.dp),
                    tint = iconTint
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val statusText = when (vpnState) {
            is VpnState.Connected -> "Connected"
            is VpnState.Connecting -> "Connecting..."
            is VpnState.Disconnecting -> "Disconnecting..."
            is VpnState.Disconnected -> "Not connected"
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        if (selectedConfig == null && !isConnected && !isConnecting) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select or add a configuration",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add configuration"
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                onClick = { showConfigSheet = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Configuration",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selectedConfig?.name ?: "Select config",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Select config",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showConfigSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConfigSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
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
                ConfigList(
                    configs = configs,
                    selectedSource = selectedConfig?.source,
                    onSelect = { config ->
                        val isDifferent = config.source != selectedConfig?.source
                        if (isDifferent) {
                            when (vpnState) {
                                is VpnState.Connecting, is VpnState.Connected -> onDisconnect()
                                else -> Unit
                            }
                        }
                        viewModel.selectConfig(config)
                        showConfigSheet = false
                    },
                    onEdit = { config ->
                        editingConfig = config
                        showConfigSheet = false
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        ConfigEditorDialog(
            isEdit = false,
            initialSource = "",
            onDismiss = { showAddDialog = false },
            onSave = { src ->
                viewModel.addConfig(src)
                showAddDialog = false
            }
        )
    }

    editingConfig?.let { cfg ->
        ConfigEditorDialog(
            isEdit = true,
            initialSource = cfg.source,
            onDismiss = { editingConfig = null },
            onSave = { src ->
                viewModel.updateConfig(cfg, src)
                editingConfig = null
            },
            onDelete = {
                editingConfig = null
                deletingConfig = cfg
            }
        )
    }

    deletingConfig?.let { cfg ->
        ConfirmDeleteDialog(
            configName = cfg.name,
            onDismiss = { deletingConfig = null },
            onConfirm = {
                viewModel.deleteConfig(cfg)
                deletingConfig = null
            }
        )
    }
}

@Composable
fun ConfigList(
    configs: List<VpnConfig>,
    selectedSource: String?,
    onSelect: (VpnConfig) -> Unit,
    onEdit: (VpnConfig) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Configurations",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        if (configs.isEmpty()) {
            item {
                Text(
                    text = "No saved configurations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(configs, key = { it.source }) { config ->
                ConfigPill(
                    config = config,
                    selected = config.source == selectedSource,
                    onSelect = { onSelect(config) },
                    onEdit = { onEdit(config) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun ConfigPill(
    config: VpnConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val contentColor = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (selected) 4.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = config.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
        }
    }
}

@Composable
fun SoftDialog(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    leadingButton: (@Composable RowScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                content()
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingButton != null) {
                        leadingButton()
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = buttons
                    )
                }
            }
        }
    }
}

@Composable
fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        shape = RoundedCornerShape(20.dp),
        minLines = minLines,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        )
    )
}

@Composable
fun ConfigEditorDialog(
    isEdit: Boolean,
    initialSource: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var source by remember { mutableStateOf(initialSource) }
    val title = if (isEdit) "Edit" else "New"
    val canSave = source.isNotBlank() && source.trim() != initialSource.trim()

    SoftDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            SoftTextField(
                value = source,
                onValueChange = { source = it },
                label = "shampoore:// link or JSON",
                minLines = 5
            )
        },
        leadingButton = if (onDelete != null) {
            {
                TextButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            }
        } else null,
        buttons = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50)
            ) {
                Text("Cancel")
            }
            FilledTonalButton(
                onClick = { if (canSave) onSave(source.trim()) },
                enabled = canSave,
                shape = RoundedCornerShape(50)
            ) {
                Text(if (isEdit) "Save" else "Add")
            }
        }
    )
}

@Composable
fun ConfirmDeleteDialog(
    configName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SoftDialog(
        onDismissRequest = onDismiss,
        title = "Delete?",
        content = {
            Text(
                text = "\"$configName\" will be deleted. This action cannot be undone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50)
            ) {
                Text("Cancel")
            }
            FilledTonalButton(
                onClick = onConfirm,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Delete")
            }
        }
    )
}