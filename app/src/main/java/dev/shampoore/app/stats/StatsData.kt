package dev.shampoore.app.stats

import org.json.JSONObject
import java.util.Locale

data class OverallStats(
    val networkSent: Long,
    val networkRecv: Long,
    val usefulSent: Long,
    val usefulRecv: Long
)

data class Rates(
    val wireSentBps: Double,
    val wireRecvBps: Double,
    val usefulSentBps: Double,
    val usefulRecvBps: Double,
    val efficiency: Double,
    val txEfficiency: Double,
    val rxEfficiency: Double
)

data class SubflowStats(
    val name: String,
    val type: String,
    val wireSent: Long,
    val wireRecv: Long,
    val usefulSent: Long,
    val usefulRecv: Long,
    val efficiency: Double,
    val txEfficiency: Double,
    val rxEfficiency: Double,
    val share: Double,
    val rates: Rates
) {
    val displayName: String
        get() = friendlyName(name.ifEmpty { type })
}

data class StatsData(
    val running: Boolean,
    val overall: OverallStats,
    val rates: Rates,
    val subflows: List<SubflowStats>
) {
    companion object {
        fun fromJson(raw: String): StatsData? {
            return try {
                val obj = JSONObject(raw)
                val running = obj.optBoolean("running", false)
                val overall = parseOverall(obj.optJSONObject("overall"))
                val rates = parseRates(obj.optJSONObject("rates"))
                val subflowsArr = obj.optJSONArray("subflows")
                val subflows = mutableListOf<SubflowStats>()
                if (subflowsArr != null) {
                    for (i in 0 until subflowsArr.length()) {
                        val s = subflowsArr.optJSONObject(i) ?: continue
                        subflows.add(parseSubflow(s))
                    }
                }
                StatsData(running, overall, rates, subflows)
            } catch (_: Throwable) {
                null
            }
        }

        private fun parseOverall(obj: JSONObject?): OverallStats {
            if (obj == null) return OverallStats(0, 0, 0, 0)
            return OverallStats(
                networkSent = obj.optLong("network_sent", 0),
                networkRecv = obj.optLong("network_recv", 0),
                usefulSent = obj.optLong("useful_sent", 0),
                usefulRecv = obj.optLong("useful_recv", 0)
            )
        }

        private fun parseRates(obj: JSONObject?): Rates {
            if (obj == null) return Rates(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            return Rates(
                wireSentBps = obj.optDouble("wire_sent_bps", 0.0),
                wireRecvBps = obj.optDouble("wire_recv_bps", 0.0),
                usefulSentBps = obj.optDouble("useful_sent_bps", 0.0),
                usefulRecvBps = obj.optDouble("useful_recv_bps", 0.0),
                efficiency = obj.optDouble("efficiency", 0.0),
                txEfficiency = obj.optDouble("tx_efficiency", 0.0),
                rxEfficiency = obj.optDouble("rx_efficiency", 0.0)
            )
        }

        private fun parseSubflow(obj: JSONObject): SubflowStats {
            return SubflowStats(
                name = obj.optString("name", ""),
                type = obj.optString("type", ""),
                wireSent = obj.optLong("wire_sent", 0),
                wireRecv = obj.optLong("wire_recv", 0),
                usefulSent = obj.optLong("useful_sent", 0),
                usefulRecv = obj.optLong("useful_recv", 0),
                efficiency = obj.optDouble("efficiency", 0.0),
                txEfficiency = obj.optDouble("tx_efficiency", 0.0),
                rxEfficiency = obj.optDouble("rx_efficiency", 0.0),
                share = obj.optDouble("share", 0.0),
                rates = parseRates(obj.optJSONObject("rates"))
            )
        }
    }
}

fun friendlyName(raw: String): String {
    val base = raw.substringAfter('-', raw)
    return when (base) {
        "yandex" -> "Yandex Board"
        "yandex_telemost_raw" -> "Telemost Raw"
        "yandex_telemost_squares" -> "Telemost Squares"
        "livekit_datachannel" -> "LiveKit"
        "livekit" -> "LiveKit"
        "multi" -> "MultiPipe"
        "reliable" -> "Reliable"
        "kcp" -> "KCP"
        "raw" -> "Raw VP8"
        "framing" -> "Framing"
        "iods" -> "Bond"
        else -> base.ifEmpty { "Unknown" }
    }
}

fun formatBytes(bytes: Double): String {
    if (bytes <= 0.0) return "0 B"
    if (bytes < 1024.0) return String.format(Locale.US, "%.0f B", bytes)
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatRate(bps: Double): String = formatBytes(bps) + "/s"

fun formatPercent(f: Double): String {
    val p = f.coerceIn(0.0, 1.0) * 100.0
    return String.format(Locale.US, "%.1f%%", p)
}