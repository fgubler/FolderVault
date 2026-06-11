package ch.abwesend.foldervault.domain.model

sealed class RetentionPolicy {
    data object KeepAll : RetentionPolicy()
    data class KeepLastN(val count: Int) : RetentionPolicy() {
        init { require(count >= 1) { "KeepLastN.count must be >= 1" } }
    }
    data class KeepNewerThan(val days: Int) : RetentionPolicy()
}
