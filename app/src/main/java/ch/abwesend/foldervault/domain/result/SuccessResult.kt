package ch.abwesend.foldervault.domain.result

data class SuccessResult<TValue>(val value: TValue) : BinaryResult<TValue, Nothing> {
    override fun getValueOrNull(): TValue = value
    override fun getErrorOrNull(): Nothing? = null
}
