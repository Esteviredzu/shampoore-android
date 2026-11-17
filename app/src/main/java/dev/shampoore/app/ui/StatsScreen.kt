package dev.shampoore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shampoore.app.stats.StatsData
import dev.shampoore.app.stats.SubflowStats
import dev.shampoore.app.stats.formatBytes
import dev.shampoore.app.stats.formatPercent
import dev.shampoore.app.stats.formatRate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    stats: StatsData?,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (stats == null || !stats.running) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (stats == null) "Waiting for data..." else "Not connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { OverallCard(stats) }

            if (stats.subflows.isNotEmpty()) {
                item {
                    Text(
                        text = if (stats.subflows.size > 1) "Transports" else "Transport",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
                items(stats.subflows, key = { it.name + "|" + it.type }) { sf ->
                    SubflowCard(sf)
                }
            }
        }
    }
}

@Composable
private fun OverallCard(stats: StatsData) {
    val eff = stats.rates.efficiency.coerceIn(0.0, 1.0)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Overall efficiency",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )

            Spacer(Modifier.height(20.dp))

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { eff.toFloat() },
                    modifier = Modifier.size(168.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                    strokeWidth = 16.dp,
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatPercent(eff),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "useful / wire",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                DirectionStat(
                    label = "Upload",
                    rate = formatRate(stats.rates.usefulSentBps),
                    efficiency = stats.rates.txEfficiency,
                    useful = formatBytes(stats.overall.usefulSent.toDouble()),
                    wire = formatBytes(stats.overall.networkSent.toDouble()),
                    modifier = Modifier.weight(1f)
                )
                VerticalRule(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                    height = 104.dp
                )
                DirectionStat(
                    label = "Download",
                    rate = formatRate(stats.rates.usefulRecvBps),
                    efficiency = stats.rates.rxEfficiency,
                    useful = formatBytes(stats.overall.usefulRecv.toDouble()),
                    wire = formatBytes(stats.overall.networkRecv.toDouble()),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DirectionStat(
    label: String,
    rate: String,
    efficiency: Double,
    useful: String,
    wire: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = rate,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${formatPercent(efficiency)} efficiency",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = useful,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "of $wire",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun VerticalRule(color: Color, height: Dp) {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .width(1.dp)
            .height(height)
            .background(color)
    )
}

@Composable
private fun SubflowCard(sf: SubflowStats) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sf.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (sf.type.isNotEmpty() && sf.type != sf.name) {
                        Text(
                            text = sf.type,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                EfficiencyBadge(sf.efficiency)
            }

            Spacer(Modifier.height(16.dp))

            ProgressSection(
                label = "Efficiency",
                fraction = sf.efficiency,
                valueText = formatPercent(sf.efficiency),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            ProgressSection(
                label = "Share",
                fraction = sf.share,
                valueText = formatPercent(sf.share),
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(Modifier.height(18.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                SubflowDirection(
                    label = "Upload",
                    rate = formatRate(sf.rates.usefulSentBps),
                    efficiency = sf.txEfficiency,
                    useful = formatBytes(sf.usefulSent.toDouble()),
                    wire = formatBytes(sf.wireSent.toDouble()),
                    modifier = Modifier.weight(1f)
                )
                VerticalRule(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    height = 88.dp
                )
                SubflowDirection(
                    label = "Download",
                    rate = formatRate(sf.rates.usefulRecvBps),
                    efficiency = sf.rxEfficiency,
                    useful = formatBytes(sf.usefulRecv.toDouble()),
                    wire = formatBytes(sf.wireRecv.toDouble()),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EfficiencyBadge(fraction: Double) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Text(
            text = formatPercent(fraction.coerceIn(0.0, 1.0)),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ProgressSection(
    label: String,
    fraction: Double,
    valueText: String,
    color: Color
) {
    val f = fraction.coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { f.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun SubflowDirection(
    label: String,
    rate: String,
    efficiency: Double,
    useful: String,
    wire: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = rate,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${formatPercent(efficiency)} efficiency",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = useful,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "of $wire",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}