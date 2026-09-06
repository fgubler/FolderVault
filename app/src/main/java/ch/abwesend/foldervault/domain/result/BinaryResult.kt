package ch.abwesend.foldervault.domain.result

sealed interface BinaryResult<out TValue, out TError> {
    fun getValueOrNull(): TValue?
    fun getErrorOrNull(): TError?
}
