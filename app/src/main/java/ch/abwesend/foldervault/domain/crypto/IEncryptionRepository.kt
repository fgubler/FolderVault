package ch.abwesend.foldervault.domain.crypto

import ch.abwesend.foldervault.domain.result.BinaryResult

interface IEncryptionRepository {
    fun encryptPassword(password: String): BinaryResult<String, Exception>
    fun decryptPassword(encryptedPassword: String): BinaryResult<String, Exception>
    fun deleteKeyStoreKey(): Boolean

    // Implemented in §14.6 alongside the FVC1 streaming container.
    fun verifyPassword(
        headerBytes: ByteArray,
        firstCiphertextBlock: ByteArray,
        password: String,
    ): BinaryResult<Unit, DecryptionError>
}
