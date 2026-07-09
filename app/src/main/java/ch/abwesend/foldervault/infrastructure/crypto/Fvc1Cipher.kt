package ch.abwesend.foldervault.infrastructure.crypto

import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.crypto.classifyDecryptionError
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.mapError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import java.io.ByteArrayOutputStream
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

/**
 * FVC1 stream cipher: PBKDF2-derived AES-256-GCM with a per-file random 96-bit IV.
 *
 * **Key lifetime (SEC-4).** One AES key is derived per backup (per salt) and reused for every file,
 * with a fresh random IV per file. NIST SP 800-38D §8.3 caps random-IV GCM at 2³² invocations under
 * a single key before the birthday-bound IV-collision risk becomes non-negligible. At one IV per
 * file that is ~4.3 billion files per backup — unreachable for realistic archives even counting
 * every re-upload. Any future change that encrypts multiple segments under the same key with random
 * IVs (e.g. chunked/resumable uploads with a per-chunk IV) multiplies the invocation count and must
 * re-check this bound, switch to a deterministic/counter IV, or rotate the key.
 */
class Fvc1Cipher : IFvc1Cipher {

    companion object {
        private val MAGIC = "FVC1".toByteArray(Charsets.US_ASCII)

        /** New files are written in the AAD-authenticated format (SEC-3); version-1 files stay readable. */
        private const val FVC1_VERSION = Fvc1Header.VERSION_WITH_AAD
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

    override fun deriveKey(password: String, salt: ByteArray): SecretKey =
        deriveKey(password, salt, PBKDF2_ITERATIONS)

    override fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, AES_KEY_ALGORITHM)
    }

    override fun encryptFile(key: SecretKey, salt: ByteArray, input: InputStream, output: OutputStream) {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val headerBytes = writeHeader(output, salt, iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            // Bind the plaintext header to the ciphertext: tampering with any header field now
            // fails the GCM tag check on decrypt instead of being silently trusted (SEC-3).
            updateAAD(headerBytes)
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
            decryptBody(key, header, input, output)
        }.mapError { classifyDecryptionError(it) }

    override fun decryptFileWithPassword(
        password: String,
        input: InputStream,
        output: OutputStream,
    ): BinaryResult<Unit, DecryptionError> =
        runCatchingAsResult {
            val header = Fvc1Header.readFrom(input)
            val key = deriveKey(password, header.salt, header.iterations)
            decryptBody(key, header, input, output)
        }.mapError { classifyDecryptionError(it) }

    private fun decryptBody(key: SecretKey, header: Fvc1Header, input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, header.iv))
            // Version-2 files authenticate the header as AAD; version-1 files predate that and must
            // be decrypted without it, otherwise their (unbound) header would fail the tag check (SEC-3).
            if (header.version >= Fvc1Header.VERSION_WITH_AAD) {
                updateAAD(header.headerBytes)
            }
        }
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            cipher.update(buf, 0, n)?.let { output.write(it) }
        }
        output.write(cipher.doFinal())
    }

    /**
     * Writes the FVC1 header to [output] and returns the exact bytes written, so [encryptFile] can
     * feed them to the cipher as GCM AAD (SEC-3). The header is assembled in memory first to keep
     * the AAD and the on-disk bytes byte-for-byte identical.
     */
    private fun writeHeader(output: OutputStream, salt: ByteArray, iv: ByteArray): ByteArray {
        val headerBytes = ByteArrayOutputStream().apply {
            // DataOutputStream is a pass-through wrapper — flush() propagates; do NOT close it
            // (that would close the caller-owned output stream prematurely).
            val dos = DataOutputStream(this)
            dos.write(MAGIC)
            dos.writeByte(FVC1_VERSION)
            dos.writeByte(KDF_ID_PBKDF2_SHA256)
            dos.writeInt(PBKDF2_ITERATIONS)
            dos.writeByte(salt.size)
            dos.write(salt)
            dos.writeByte(iv.size)
            dos.write(iv)
            dos.flush()
        }.toByteArray()
        output.write(headerBytes)
        output.flush()
        return headerBytes
    }
}
