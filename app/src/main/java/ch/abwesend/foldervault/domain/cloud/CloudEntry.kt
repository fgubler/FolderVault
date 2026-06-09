package ch.abwesend.foldervault.domain.cloud

sealed interface CloudEntry {
    val id: String
    val name: String
}

data class CloudFile(override val id: String, override val name: String) : CloudEntry

data class CloudFolder(override val id: String, override val name: String) : CloudEntry
