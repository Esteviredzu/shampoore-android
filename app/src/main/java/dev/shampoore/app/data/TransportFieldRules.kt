package dev.shampoore.app.data





val SERVER_ONLY_TRANSPORT_FIELDS: Set<String> = setOf(
    "multiuser_server",
    "user_id",
    "room_prefix",
    "tunnel_id",
    "exit_node_id"
)

fun isServerOnlyTransportField(key: String): Boolean =
    key in SERVER_ONLY_TRANSPORT_FIELDS

fun TransportField.isVisibleInManualEditor(): Boolean =
    !isServerOnlyTransportField(key)

fun isBooleanTransportField(field: TransportField, value: String): Boolean {
    return when (field.type.trim().lowercase()) {
        "bool", "boolean", "switch" -> true
        else -> value.equals("true", ignoreCase = true) ||
            value.equals("false", ignoreCase = true)
    }
}
