package dev.shampoore.app.data

import org.json.JSONArray
import org.json.JSONObject

data class TransportField(
    val key: String,
    val label: String,
    val placeholder: String,
    val hint: String,
    val type: String,
    val required: Boolean
)

data class TransportSchema(
    val kind: String,
    val displayName: String,
    val description: String,
    val fields: List<TransportField>
) {
    companion object {
        fun parseList(raw: String): List<TransportSchema> {
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(parse(obj))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parse(obj: JSONObject): TransportSchema {
            val fieldsArr = obj.optJSONArray("fields")
            val fields = mutableListOf<TransportField>()
            if (fieldsArr != null) {
                for (i in 0 until fieldsArr.length()) {
                    val f = fieldsArr.optJSONObject(i) ?: continue
                    fields.add(
                        TransportField(
                            key = f.optString("key", ""),
                            label = f.optString("label", f.optString("key", "")),
                            placeholder = f.optString("placeholder", ""),
                            hint = f.optString("hint", ""),
                            type = f.optString("type", "text"),
                            required = f.optBoolean("required", false)
                        )
                    )
                }
            }
            return TransportSchema(
                kind = obj.optString("kind", ""),
                displayName = obj.optString("display_name", obj.optString("kind", "")),
                description = obj.optString("description", ""),
                fields = fields
            )
        }
    }
}