package dev.jotalac

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jotalac.core.data.UserSettings
import dev.jotalac.core.data.UserSettingsState
import dev.jotalac.feature.vault.data.VaultKeystoreMigration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AppViewModel(
    userSettingsManager: UserSettings,
    keystoreMigration: VaultKeystoreMigration,
) : ViewModel() {
    val userSettingsState = userSettingsManager.userSettingsStateFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserSettingsState()
        )

    init {
        keystoreMigration.start()
    }
}
