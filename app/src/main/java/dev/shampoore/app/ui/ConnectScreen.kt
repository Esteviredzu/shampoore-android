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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shampoore.app.data.SubscriptionRecord
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.data.toVpnConfigs
import dev.shampoore.app.viewmodel.MainViewModel
import dev.shampoore.app.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: MainViewModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenAdd: () -> Unit,
    onOpenEdit: (VpnConfig) -> Unit
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val selectedConfig by viewModel.selectedConfig.collectAsStateWithLifecycle()

    var showConfigSheet by remember { mutableStateOf(false) }
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<SubscriptionRecord?>(null) }

    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val subscriptionRefreshing by viewModel.subscriptionRefreshing.collectAsStateWithLifecycle()
    val subscriptionMessage by viewModel.subscriptionMessage.collectAsStateWithLifecycle()
    val subscriptionGroups = subscriptions.map { record -> record to record.toVpnConfigs() }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(subscriptionMessage) {
        subscriptionMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSubscriptionMessage()
        }
    }

    LaunchedEffect(showConfigSheet) {
        if (showConfigSheet) viewModel.refreshStaleSubscriptions()
    }

    LaunchedEffect(showConfigSheet, selectedConfig, subscriptions) {
        if (showConfigSheet) {
            val selectedId = selectedConfig?.subscriptionId
            if (selectedId != null && subscriptions.any { it.subscription.id == selectedId }) {
                expandedGroups = expandedGroups + selectedId
            }
        }
    }

    fun selectProfile(config: VpnConfig) {
        val different = config.source != selectedConfig?.source ||
            config.subscriptionId != selectedConfig?.subscriptionId
        if (different) {
            when (vpnState) {
                is VpnState.Connecting, is VpnState.Connected -> onDisconnect()
                else -> Unit
            }
        }
        viewModel.selectConfig(config)
        showConfigSheet = false
    }

    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting
    val isDisconnecting = vpnState is VpnState.Disconnecting
    val errorMessage = (vpnState as? VpnState.Error)?.message

    val buttonEnabled = when (vpnState) {
        is VpnState.Connected -> true
        is VpnState.Connecting -> true
        is VpnState.Disconnecting -> false
        is VpnState.Error -> selectedConfig != null
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shampoore", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenRules) {
                        Icon(Icons.Filled.List, contentDescription = "Rules")
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Statistics")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            
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
                    .size(220.dp)
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
                            is VpnState.Disconnected, is VpnState.Error ->
                                selectedConfig?.let { onConnect(it.source) }
                            else -> Unit
                        }
                    }
            ) {
                if (isConnecting || isDisconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(80.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.Shield else Icons.Filled.PowerSettingsNew,
                        contentDescription = "Connect",
                        modifier = Modifier.size(90.dp),
                        tint = iconTint
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            val statusText = when (vpnState) {
                is VpnState.Connected -> "Connected"
                is VpnState.Connecting -> "Connecting..."
                is VpnState.Disconnecting -> "Disconnecting..."
                is VpnState.Error -> "Connection error"
                is VpnState.Disconnected -> "Not connected"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            if (selectedConfig == null && !isConnected && !isConnecting) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select or add a configuration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onOpenAdd,
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = "Add configuration")
                    }
                }

                Spacer(Modifier.width(12.dp))

                Surface(
                    onClick = { showConfigSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = selectedConfig?.subscriptionName ?: "Configuration",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                            contentDescription = "Select",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
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

                    if (subscriptionGroups.isNotEmpty()) {
                        item { SectionTitle("Subscriptions") }
                        subscriptionGroups.forEach { (record, routes) ->
                            val groupId = record.subscription.id
                            item(key = "sub_$groupId") {
                                SubscriptionGroupCard(
                                    subscription = record.subscription,
                                    routes = routes,
                                    refreshing = groupId in subscriptionRefreshing,
                                    expanded = groupId in expandedGroups,
                                    selectedSource = selectedConfig?.source,
                                    selectedSubscriptionId = selectedConfig?.subscriptionId,
                                    onToggle = {
                                        expandedGroups = if (groupId in expandedGroups) {
                                            expandedGroups - groupId
                                        } else {
                                            expandedGroups + groupId
                                        }
                                    },
                                    onRouteSelect = { route -> selectProfile(route) },
                                    onRefresh = { viewModel.refreshSubscription(groupId) },
                                    onDelete = { pendingDelete = record }
                                )
                            }
                        }
                    }

                    item { SectionTitle("Local configurations") }
                    if (configs.isEmpty()) {
                        item {
                            Text(
                                text = "No local configurations",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(configs, key = { it.source }) { config ->
                            ConfigPill(
                                config = config,
                                selected = config.source == selectedConfig?.source &&
                                    selectedConfig?.subscriptionId == null,
                                onSelect = { selectProfile(config) },
                                onEdit = {
                                    showConfigSheet = false
                                    onOpenEdit(config)
                                }
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    if (record.subscription.isOfflineBundle) {
                        "Delete offline group?"
                    } else {
                        "Delete subscription?"
                    }
                )
            },
            text = {
                Text(
                    "\"${record.subscription.name}\" and its ${record.routes.size} route(s) " +
                        "will be removed. Local configurations are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSubscription(record.subscription.id)
                        pendingDelete = null
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
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
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
        MaterialTheme.colorScheme.surfaceContainerHighest
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