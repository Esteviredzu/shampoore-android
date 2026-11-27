package dev.shampoore.app

import dev.shampoore.app.data.SERVER_ONLY_TRANSPORT_FIELDS
import dev.shampoore.app.data.TransportField
import dev.shampoore.app.data.isBooleanTransportField
import dev.shampoore.app.data.isVisibleInManualEditor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFieldRulesTest {

    private fun field(key: String, type: String = "text") = TransportField(
        key = key,
        label = key,
        placeholder = "",
        hint = "",
        type = type,
        required = false
    )

    @Test
    fun hidesServerOnlyFields() {
        val hidden = listOf(
            "multiuser_server",
            "user_id",
            "room_prefix",
            "tunnel_id",
            "exit_node_id"
        )
        hidden.forEach { key ->
            assertTrue(key, key in SERVER_ONLY_TRANSPORT_FIELDS)
            assertFalse(key, field(key).isVisibleInManualEditor())
        }
        assertTrue(field("board").isVisibleInManualEditor())
        assertTrue(field("use_ssl", "bool").isVisibleInManualEditor())
    }

    @Test
    fun detectsBooleanBySchemaType() {
        assertTrue(isBooleanTransportField(field("use_ssl", "bool"), "false"))
        assertTrue(isBooleanTransportField(field("use_ssl", "bool"), ""))
        assertTrue(isBooleanTransportField(field("allow_insecure_ws", "boolean"), ""))
        assertTrue(isBooleanTransportField(field("allow_insecure_ws", "switch"), ""))
        assertFalse(isBooleanTransportField(field("board", "text"), "main"))
    }

    @Test
    fun detectsBooleanByValue() {
        assertTrue(isBooleanTransportField(field("flag"), "true"))
        assertTrue(isBooleanTransportField(field("flag"), "TRUE"))
        assertTrue(isBooleanTransportField(field("flag"), "false"))
        assertTrue(isBooleanTransportField(field("flag"), "False"))
        assertFalse(isBooleanTransportField(field("flag"), ""))
        assertFalse(isBooleanTransportField(field("flag"), "1"))
    }
}
