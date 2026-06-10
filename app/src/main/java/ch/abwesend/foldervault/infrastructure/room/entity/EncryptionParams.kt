package ch.abwesend.foldervault.infrastructure.room.entity

data class EncryptionParams(
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val salt: String,
    val cipherTransformation: String,
    val gcmTagBits: Int,
)
