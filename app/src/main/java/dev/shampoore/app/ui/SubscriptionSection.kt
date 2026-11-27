package dev.shampoore.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shampoore.app.data.Subscription
import dev.shampoore.app.data.VpnConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val updatedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM. HH:mm", Locale.ENGLISH)





@Composable
fun SubscriptionGroupCard(
    subscription: Subscription,
    routes: List<VpnConfig>,
    refreshing: Boolean,
    expanded: Boolean,
    selectedSource: String?,
    selectedSubscriptionId: String?,
    onToggle: () -> Unit,
    onRouteSelect: (VpnConfig) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "subscriptionChevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (subscription.isOfflineBundle) {
                                Icons.Rounded.Folder
                            } else {
                                Icons.Rounded.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subscriptionUpdatedLabel(subscription),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (subscription.isNetworkSubscription) {
                        RefreshButton(refreshing = refreshing, onRefresh = onRefresh)
                    }
                    DeleteButton(onDelete = onDelete)
                    ExpandButton(
                        expanded = expanded,
                        rotation = chevronRotation,
                        onToggle = onToggle
                    )
                }
            }

            subscription.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 78.dp, end = 18.dp, bottom = 8.dp)
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 2.dp,
                        bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (routes.isEmpty()) {
                        Text(
                            text = "No routes in this subscription",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                    routes.forEach { route ->
                        SubscriptionRoutePill(
                            config = route,
                            selected = route.source == selectedSource &&
                                route.subscriptionId == selectedSubscriptionId,
                            onSelect = { onRouteSelect(route) }
                        )
                    }
                }
            }
        }
    }
}

internal fun subscriptionUpdatedLabel(subscription: Subscription): String {
    if (subscription.isOfflineBundle) return "Offline bundle"
    if (subscription.updatedAt <= 0L) return "Not updated yet"
    val time = updatedAtFormatter.format(
        Instant.ofEpochMilli(subscription.updatedAt).atZone(ZoneId.systemDefault())
    )
    return "upd: " + time.lowercase(Locale.ENGLISH)
}

@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    val spin = if (refreshing) {
        val transition = rememberInfiniteTransition(label = "subscriptionRefresh")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "subscriptionRefreshAngle"
        ).value
    } else {
        0f
    }
    FilledTonalIconButton(
        onClick = onRefresh,
        enabled = !refreshing,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = "Refresh",
            modifier = Modifier
                .size(18.dp)
                .rotate(spin)
        )
    }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    FilledTonalIconButton(
        onClick = onDelete,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = "Delete",
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, rotation: Float, onToggle: () -> Unit) {
    FilledTonalIconButton(
        onClick = onToggle,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation)
        )
    }
}

