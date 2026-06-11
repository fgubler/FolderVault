package ch.abwesend.foldervault.view.viewmodel

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed class UiText {
    data class Resource(@StringRes val id: Int) : UiText()
    data class ResourceWithArg(@StringRes val id: Int, val arg: String) : UiText()
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id)
    is UiText.ResourceWithArg -> stringResource(id, arg)
}
