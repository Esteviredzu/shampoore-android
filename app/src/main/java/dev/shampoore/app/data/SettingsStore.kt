package dev.shampoore.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "shampoore_settings")

class SettingsStore(private val context: Context) {
    private val themeModeKey = intPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    val themeMode: Flow<Int> = context.settingsDataStore.data
        .map { prefs -> prefs[themeModeKey] ?: THEME_MODE_SYSTEM }

    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[dynamicColorKey] ?: true }

    suspend fun setThemeMode(mode: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[themeModeKey] = mode.coerceIn(THEME_MODE_SYSTEM, THEME_MODE_DARK)
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[dynamicColorKey] = enabled
        }
    }

    companion object {
        const val THEME_MODE_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
    }
}
