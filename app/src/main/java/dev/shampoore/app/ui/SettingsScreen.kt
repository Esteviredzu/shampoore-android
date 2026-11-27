package dev.shampoore.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shampoore.app.BuildConfig
import dev.shampoore.app.data.SettingsStore
import dev.shampoore.app.data.UpdateCheckResult
import dev.shampoore.app.data.UpdateChecker
import kotlinx.coroutines.launch

private const val GITHUB_CORE_URL = "https://github.com/Esteviredzu/shampoore"
private const val GITHUB_ANDROID_URL = "https://github.com/Esteviredzu/shampoore-android"

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val version: String, val downloadUrl: String) : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: Int,
    dynamicColor: Boolean,
    activeRules: Int,
    onThemeModeChange: (Int) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenRules: () -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun openLink(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun checkForUpdates() {
        if (updateState == UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.Available ->
                    UpdateUiState.Available(result.version, result.downloadUrl)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.version)
                is UpdateCheckResult.Failed -> UpdateUiState.Failed(result.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { SettingsSectionHeader("Appearance") }
            item {
                ThemeModeSelector(
                    selectedMode = themeMode,
                    onSelect = onThemeModeChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Palette,
                    title = "Wallpaper colors",
                    subtitle = if (dynamicColorSupported) null else "Android 12+",
                    onClick = if (dynamicColorSupported) {
                        { onDynamicColorChange(!dynamicColor) }
                    } else {
                        null
                    },
                    trailing = {
                        Switch(
                            checked = dynamicColor && dynamicColorSupported,
                            onCheckedChange = if (dynamicColorSupported) {
                                onDynamicColorChange
                            } else {
                                null
                            },
                            enabled = dynamicColorSupported
                        )
                    }
                )
            }

            item { SettingsSectionHeader("Network") }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Shield,
                    title = "GeoIP rules",
                    subtitle = when (activeRules) {
                        0 -> "No active rules"
                        1 -> "1 active rule"
                        else -> "$activeRules active rules"
                    },
                    onClick = onOpenRules
                )
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.QueryStats,
                    title = "Statistics",
                    onClick = onOpenStats
                )
            }

            item { SettingsSectionHeader("GitHub") }
            item {
                GitHubLinksRow(
                    onCore = { openLink(GITHUB_CORE_URL) },
                    onAndroid = { openLink(GITHUB_ANDROID_URL) }
                )
            }

            item { SettingsSectionHeader("Updates") }
            item {
                UpdateSection(
                    state = updateState,
                    onCheck = ::checkForUpdates,
                    onDownload = { url -> openLink(url) }
                )
            }

            item {
                AppVersionFooter(
                    versionName = BuildConfig.VERSION_NAME,
                    modifier = Modifier.padding(top = 28.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AppVersionFooter(versionName: String, modifier: Modifier = Modifier) {
    Text(
        text = "Version $versionName",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    )
}

@Composable
private fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRoundIcon(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.size(40.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = if (subtitle != null) {
            {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            null
        },
        leadingContent = { SettingsRoundIcon(icon = icon) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    )
}

@Composable
private fun GitHubLinksRow(
    onCore: () -> Unit,
    onAndroid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LinkPill(
            icon = Icons.Rounded.Code,
            label = "shampoore",
            modifier = Modifier.weight(1f),
            onClick = onCore
        )
        LinkPill(
            icon = Icons.Rounded.PhoneAndroid,
            label = "shampoore-android",
            modifier = Modifier.weight(1f),
            onClick = onAndroid
        )
    }
}

@Composable
private fun LinkPill(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UpdateSection(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        when (state) {
            UpdateUiState.Idle -> UpdateActionButton(
                label = "Check for updates",
                icon = Icons.Rounded.Refresh,
                enabled = true,
                onClick = onCheck
            )

            UpdateUiState.Checking -> UpdateActionButton(
                label = "Checking…",
                icon = null,
                enabled = false,
                loading = true,
                onClick = onCheck
            )

            is UpdateUiState.UpToDate -> {
                UpdateStatusCard(
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "You're up to date",
                    subtitle = "Latest version: ${state.version}"
                )
                TextButton(
                    onClick = onCheck,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Check again")
                }
            }

            is UpdateUiState.Available -> {
                UpdateStatusCard(
                    icon = Icons.Rounded.SystemUpdate,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "New version ${state.version} available",
                    subtitle = "Download the latest release from GitHub"
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { onDownload(state.downloadUrl) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Download",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onCheck) {
                        Text("Check again")
                    }
                }
            }

            is UpdateUiState.Failed -> {
                UpdateStatusCard(
                    icon = Icons.Rounded.ErrorOutline,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "Update check failed",
                    subtitle = state.message
                )
                TextButton(
                    onClick = onCheck,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun UpdateActionButton(
    label: String,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(50)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        if (loading || icon != null) Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun UpdateStatusCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThemeModePill(
                icon = Icons.Rounded.BrightnessAuto,
                label = "System",
                selected = selectedMode == SettingsStore.THEME_MODE_SYSTEM,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(SettingsStore.THEME_MODE_SYSTEM) }
            )
            ThemeModePill(
                icon = Icons.Rounded.LightMode,
                label = "Light",
                selected = selectedMode == SettingsStore.THEME_MODE_LIGHT,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(SettingsStore.THEME_MODE_LIGHT) }
            )
            ThemeModePill(
                icon = Icons.Rounded.DarkMode,
                label = "Dark",
                selected = selectedMode == SettingsStore.THEME_MODE_DARK,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(SettingsStore.THEME_MODE_DARK) }
            )
        }
    }
}


@Composable
private fun ThemeModePill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "themePillContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(220),
        label = "themePillContent"
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

