package ch.abwesend.foldervault.infrastructure.crypto

import android.security.keystore.KeyProperties
import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.domain.crypto.classifyDecryptionError
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ifError
import ch.abwesend.foldervault.domain.result.mapError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import ch.abwesend.foldervault.domain.util.injectAnywhere
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptionRepository : IEncryptionRepository {
    private val keyStoreRepository: IKeyStoreRepository by injectAnywhere()

    companion object {
        internal const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        internal const val AES_KEY_SIZE_BITS = 256
        internal const val GCM_TAG_LENGTH_BITS = 128
        internal const val GCM_IV_LENGTH_BYTES = 12
        internal const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        internal const val PBKDF2_ITERATIONS = 310_000
        internal const val PBKDF2_SALT_LENGTH_BYTES = 16
        private const val JSON_VERSION = 1
    }

    // ---- Password storage (KeyStore-backed AES-256-GCM) ----

    override fun encryptPassword(password: String): BinaryResult<String, Exception> = runCatchingAsResult {
        val key = keyStoreRepository.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        val payload = EncryptedPasswordPayload(
            version = JSON_VERSION,
            algorithm = AES_GCM_TRANSFORMATION,
            tagLength = GCM_TAG_LENGTH_BITS,
            iv = encoder.encodeToString(cipher.iv),
            ciphertext = encoder.encodeToString(cipherText),
        )
        Json.encodeToString(payload)
    }.ifError { logger.error("Password encryption failed", it) }

    override fun decryptPassword(encryptedPassword: String): BinaryResult<String, Exception> = runCatchingAsResult {
        val key = keyStoreRepository.getKey() ?: error("No KeyStore key available")
        val payload = Json.decodeFromString<EncryptedPasswordPayload>(encryptedPassword)
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(payload.algorithm).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(payload.tagLength, decoder.decode(payload.iv)))
        }
        cipher.doFinal(decoder.decode(payload.ciphertext)).toString(Charsets.UTF_8)
    }.ifError { logger.error("Password decryption failed", it) }

    override fun deleteKeyStoreKey(): Boolean = keyStoreRepository.deleteKey()

    // ---- FVC1 password verification ----

    /**
     * Derives the key from [password] + the salt embedded in [headerBytes], then attempts
     * a full GCM decrypt of [firstCiphertextBlock] (which must include the 16-byte GCM tag).
     * An incorrect password causes AEADBadTagException → DecryptionError.INVALID_PASSWORD.
     *
     * Callers should pass the complete ciphertext of any FVC1 file from the backup so the
     * GCM tag is included and authentication can complete.
     */
    override fun verifyPassword(
        headerBytes: ByteArray,
        firstCiphertextBlock: ByteArray,
        password: String,
    ): BinaryResult<Unit, DecryptionError> = runCatchingAsResult {
        val header = Fvc1Header.readFrom(ByteArrayInputStream(headerBytes))
        val key = deriveKey(password, header.salt, header.iterations, AES_KEY_SIZE_BITS)
        Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, header.iv))
        }.doFinal(firstCiphertextBlock)
        Unit
    }.mapError { e -> classifyDecryptionError(e) }

    // ---- Internal key derivation (used by §14.6 FVC1 extension) ----

    internal fun deriveKey(password: String, salt: ByteArray, iterations: Int, keySize: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keySize)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, KeyProperties.KEY_ALGORITHM_AES)
    }

    internal fun generateRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}

@Serializable
private data class EncryptedPasswordPayload(
    val version: Int,
    val algorithm: String,
    val tagLength: Int,
    val iv: String,
    val ciphertext: String,
)
