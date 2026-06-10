package ch.abwesend.foldervault.domain.model

sealed class RetentionPolicy {
    data object KeepAll : RetentionPolicy()
    data class KeepLastN(val count: Int) : RetentionPolicy()
    data class KeepNewerThan(val days: Int) : RetentionPolicy()
}
