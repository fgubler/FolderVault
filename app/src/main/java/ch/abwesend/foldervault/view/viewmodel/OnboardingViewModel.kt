package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val settingsRepo: IAppSettingsRepository,
) : ViewModel() {

    fun markOnboardingComplete() {
        viewModelScope.launch {
            settingsRepo.update { it.copy(showOnboarding = false) }
        }
    }
}
