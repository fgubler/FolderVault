package ch.abwesend.foldervault.view.viewmodel

import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository

class OnboardingViewModel(
    private val settingsRepo: IAppSettingsRepository,
) : BaseViewModel() {

    fun markOnboardingComplete() = safeLaunch {
        settingsRepo.update { it.copy(showOnboarding = false) }
    }
}
