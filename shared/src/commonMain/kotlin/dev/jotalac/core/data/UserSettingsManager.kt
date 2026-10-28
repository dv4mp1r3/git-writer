package dev.jotalac.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jotalac.core.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserSettingsState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themeAccentColor: AppThemeAccentColor = AppThemeAccentColor.DEFAULT,
    val font: AppFontFamily = AppFontFamily.DEFAULT,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val gitConflictStrategy: GitConflictResolutionStrategy = GitConflictResolutionStrategy.MANUAL,
    val useDynamicColor: Boolean = false,
    val spellcheckLanguage: AppLanguage? = null,
    val editorFullWidth: Boolean = false,
    val autoSync: Boolean = true,
    val proxy: ProxyConfig = ProxyConfig(),
)

class UserSettingsManager(
    private val dataStore: DataStore<Preferences>
) : UserSettings {
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val THEME_ACCENT_COLOR_KEY = stringPreferencesKey("theme_accent_color")
        private val FONT_FAMILY_KEY = stringPreferencesKey("font_family")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val CONFLICT_STRATEGY_KEY = stringPreferencesKey("conflict_strategy")
        private val USE_DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        private val SPELLCHECK_LANGUAGE_KEY = stringPreferencesKey("spellcheck_language")
        private val EDITOR_FULL_WIDTH_KEY = booleanPreferencesKey("editor_full_width")
        private val AUTO_SYNC_KEY = booleanPreferencesKey("auto_sync")
        private val PROXY_ENABLED_KEY = booleanPreferencesKey("proxy_enabled")
        private val PROXY_PROTOCOL_KEY = stringPreferencesKey("proxy_protocol")
        private val PROXY_HOST_KEY = stringPreferencesKey("proxy_host")
        private val PROXY_PORT_KEY = intPreferencesKey("proxy_port")
        private val PROXY_USERNAME_KEY = stringPreferencesKey("proxy_username")
        private val PROXY_PASSWORD_KEY = stringPreferencesKey("proxy_password")
    }

    override val userSettingsStateFlow: Flow<UserSettingsState> = dataStore.data
        .map { preferences ->
            UserSettingsState(
                themeMode = preferences[THEME_MODE_KEY]?.let { name ->
                    AppThemeMode.entries.find { it.name == name }
                } ?: AppThemeMode.SYSTEM,
                font = preferences[FONT_FAMILY_KEY]?.let { name ->
                    AppFontFamily.entries.find { it.name == name }
                } ?: AppFontFamily.DEFAULT,
                themeAccentColor = preferences[THEME_ACCENT_COLOR_KEY]?.let { name ->
                    AppThemeAccentColor.entries.find { it.name == name }
                } ?: AppThemeAccentColor.DEFAULT,
                language = preferences[LANGUAGE_KEY]?.let { name ->
                    AppLanguage.entries.find { it.name == name }
                } ?: AppLanguage.ENGLISH,
                gitConflictStrategy = preferences[CONFLICT_STRATEGY_KEY]?.let { name ->
                    GitConflictResolutionStrategy.entries.find { it.name == name }
                } ?: GitConflictResolutionStrategy.MANUAL,
                useDynamicColor = preferences[USE_DYNAMIC_COLOR_KEY] ?: false,
                spellcheckLanguage = preferences[SPELLCHECK_LANGUAGE_KEY]?.let { name ->
                    AppLanguage.entries.find { it.name == name }
                },
                editorFullWidth = preferences[EDITOR_FULL_WIDTH_KEY] ?: false,
                autoSync = preferences[AUTO_SYNC_KEY] ?: true,
                proxy = ProxyConfig(
                    enabled = preferences[PROXY_ENABLED_KEY] ?: false,
                    protocol = preferences[PROXY_PROTOCOL_KEY]?.let { name ->
                        ProxyProtocol.entries.find { it.name == name }
                    } ?: ProxyProtocol.HTTP,
                    host = preferences[PROXY_HOST_KEY] ?: "",
                    port = preferences[PROXY_PORT_KEY] ?: 0,
                    username = preferences[PROXY_USERNAME_KEY] ?: "",
                    password = preferences[PROXY_PASSWORD_KEY] ?: "",
                ),
            )
        }

    override suspend fun setThemeMode(themeMode: AppThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.name
        }
    }

    override suspend fun setThemeAccentColor(accentColor: AppThemeAccentColor) {
        dataStore.edit { preferences ->
            preferences[THEME_ACCENT_COLOR_KEY] = accentColor.name
        }
    }

    override suspend fun setFont(font: AppFontFamily) {
        dataStore.edit { preferences ->
            preferences[FONT_FAMILY_KEY] = font.name
        }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language.name
        }
    }

    override suspend fun setGitConflictStrategy(strategy: GitConflictResolutionStrategy) {
        dataStore.edit { preferences ->
            preferences[CONFLICT_STRATEGY_KEY] = strategy.name
        }
    }

    override suspend fun setUseDynamicColor(useDynamicColor: Boolean) {
        dataStore.edit { preferences ->
            preferences[USE_DYNAMIC_COLOR_KEY] = useDynamicColor
        }
    }

    override suspend fun setSpellcheckLanguage(language: AppLanguage?) {
        dataStore.edit { preferences ->
            if (language == null) {
                preferences.remove(SPELLCHECK_LANGUAGE_KEY)
            } else {
                preferences[SPELLCHECK_LANGUAGE_KEY] = language.name
            }
        }
    }

    override suspend fun setEditorFullWidth(fullWidth: Boolean) {
       dataStore.edit { preferences ->
           preferences[EDITOR_FULL_WIDTH_KEY] = fullWidth
       }
    }

    override suspend fun setAutoSync(autoSync: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_SYNC_KEY] = autoSync
        }
    }

    // written as one edit so a config change is all-or-nothing
    override suspend fun setProxyConfig(config: ProxyConfig) {
        dataStore.edit { preferences ->
            preferences[PROXY_ENABLED_KEY] = config.enabled
            preferences[PROXY_PROTOCOL_KEY] = config.protocol.name
            preferences[PROXY_HOST_KEY] = config.host
            preferences[PROXY_PORT_KEY] = config.port
            preferences[PROXY_USERNAME_KEY] = config.username
            preferences[PROXY_PASSWORD_KEY] = config.password
        }
    }

    suspend fun clearSettings() {
        dataStore.edit { preferences ->
            preferences.remove(THEME_MODE_KEY)
            preferences.remove(THEME_ACCENT_COLOR_KEY)
            preferences.remove(FONT_FAMILY_KEY)
            preferences.remove(LANGUAGE_KEY)
            preferences.remove(CONFLICT_STRATEGY_KEY)
            preferences.remove(SPELLCHECK_LANGUAGE_KEY)
            preferences.remove(EDITOR_FULL_WIDTH_KEY)
            preferences.remove(AUTO_SYNC_KEY)
            preferences.remove(USE_DYNAMIC_COLOR_KEY)
            preferences.remove(PROXY_ENABLED_KEY)
            preferences.remove(PROXY_PROTOCOL_KEY)
            preferences.remove(PROXY_HOST_KEY)
            preferences.remove(PROXY_PORT_KEY)
            preferences.remove(PROXY_USERNAME_KEY)
            preferences.remove(PROXY_PASSWORD_KEY)
        }
    }
}