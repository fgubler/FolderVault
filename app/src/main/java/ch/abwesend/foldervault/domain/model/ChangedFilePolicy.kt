package ch.abwesend.foldervault.domain.model

enum class ChangedFilePolicy {
    DUPLICATE_WITH_TIMESTAMP,
    OVERWRITE,
    IGNORE,
}
