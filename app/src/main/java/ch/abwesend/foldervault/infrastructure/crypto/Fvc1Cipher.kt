package ch.abwesend.foldervault.infrastructure.crypto

import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.crypto.classifyDecryptionError
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.mapError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Fvc1Cipher : IFvc1Cipher {

    companion object {
        private val MAGIC = "FVC1".toByteArray(Charsets.US_ASCII)
        private const val FVC1_VERSION = 1
        private const val KDF_ID_PBKDF2_SHA256 = 1
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_ALGORITHM = "AES"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 310_000
        private const val PBKDF2_SALT_LENGTH_BYTES = 16
        private const val STREAM_BUFFER_SIZE = 8 * 1024
    }

    override fun generateBackupSalt(): ByteArray =
        ByteArray(PBKDF2_SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    override fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, AES_KEY_ALGORITHM)
    }

    override fun encryptFile(key: SecretKey, salt: ByteArray, input: InputStream, output: OutputStream) {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        writeHeader(output, salt, iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            cipher.update(buf, 0, n)?.let { output.write(it) }
        }
        output.write(cipher.doFinal())
    }

    override fun decryptFile(
        key: SecretKey,
        input: InputStream,
        output: OutputStream,
    ): BinaryResult<Unit, DecryptionError> =
        runCatchingAsResult {
            val header = Fvc1Header.readFrom(input)
            decryptBody(key, header.iv, input, output)
        }.mapError { toDecryptionError(it) }

    override fun decryptFileWithPassword(
        password: String,
        input: InputStream,
        output: OutputStream,
    ): BinaryResult<Unit, DecryptionError> =
        runCatchingAsResult {
            val header = Fvc1Header.readFrom(input)
            val key = deriveKey(password, header.salt)
            decryptBody(key, header.iv, input, output)
        }.mapError { toDecryptionError(it) }

    private fun decryptBody(key: SecretKey, iv: ByteArray, input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            cipher.update(buf, 0, n)?.let { output.write(it) }
        }
        output.write(cipher.doFinal())
    }

    private fun writeHeader(output: OutputStream, salt: ByteArray, iv: ByteArray) {
        // DataOutputStream is a pass-through wrapper — flush() propagates; do NOT close it
        // (that would close the caller-owned output stream prematurely).
        val dos = DataOutputStream(output)
        dos.write(MAGIC)
        dos.writeByte(FVC1_VERSION)
        dos.writeByte(KDF_ID_PBKDF2_SHA256)
        dos.writeInt(PBKDF2_ITERATIONS)
        dos.writeByte(salt.size)
        dos.write(salt)
        dos.writeByte(iv.size)
        dos.write(iv)
        dos.flush()
    }

    private fun toDecryptionError(e: Exception): DecryptionError = classifyDecryptionError(e)
}
