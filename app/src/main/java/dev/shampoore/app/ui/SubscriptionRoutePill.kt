package dev.shampoore.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.data.routeTransportLabel
import kotlin.math.roundToInt


@Composable
fun SubscriptionRoutePill(
    config: VpnConfig,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val shapeProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "routeShape"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(220),
        label = "routeContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "routeContent"
    )
    val badge = remember(config.source) { routeTransportLabel(config.source) }

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(percent = (32f + 18f * shapeProgress).roundToInt()),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = config.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (config.isEncrypted) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Encrypted",
                    modifier = Modifier.size(14.dp),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TransportBadge(label = badge)
        }
    }
}

@Composable
internal fun TransportBadge(label: String) {
    val appearance = transportAppearance(label)
    Surface(
        shape = CircleShape,
        color = appearance.container,
        contentColor = appearance.content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = appearance.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = appearance.content,
                maxLines = 1
            )
        }
    }
}

private data class TransportAppearance(
    val container: Color,
    val content: Color,
    val icon: ImageVector
)

@Composable
private fun transportAppearance(label: String): TransportAppearance {
    val scheme = MaterialTheme.colorScheme
    return when (label) {
        "LiveKit" -> TransportAppearance(
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
            Icons.Rounded.LiveTv
        )
        "Silo" -> TransportAppearance(
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            Icons.Rounded.Cloud
        )
        "RawWS" -> TransportAppearance(
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
            Icons.Rounded.SwapHoriz
        )
        "Multi" -> TransportAppearance(
            scheme.tertiaryContainer.copy(alpha = 0.6f),
            scheme.onTertiaryContainer,
            Icons.Rounded.Hub
        )
        else -> TransportAppearance(
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
            Icons.Rounded.Router
        )
    }
}
