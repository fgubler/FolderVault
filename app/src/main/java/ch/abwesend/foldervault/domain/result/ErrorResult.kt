package ch.abwesend.foldervault.domain.result

data class ErrorResult<TError>(val error: TError) : BinaryResult<Nothing, TError> {
    override fun getValueOrNull(): Nothing? = null
    override fun getErrorOrNull(): TError = error
}
