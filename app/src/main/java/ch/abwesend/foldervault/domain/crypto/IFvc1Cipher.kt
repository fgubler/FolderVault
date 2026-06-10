package ch.abwesend.foldervault.domain.crypto

import ch.abwesend.foldervault.domain.result.BinaryResult
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

interface IFvc1Cipher {
    /** Generate a fresh random per-backup salt. Call once at backup creation. */
    fun generateBackupSalt(): ByteArray

    /**
     * Derive the per-backup AES key. Call once per run and reuse across all files —
     * PBKDF2 at 310k iterations is too slow to run per-file.
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey

    /**
     * Write a FVC1 header + AES-256-GCM ciphertext into [output].
     * A fresh random IV is generated per call. [salt] is the per-backup salt (same for every file).
     * Does NOT close either stream.
     *
     * NOTE: same plaintext encrypts differently on each call (random IV). Never use a remote
     * content hash for change-detection; use (mtime, size) from UploadedFileIndex instead.
     */
    fun encryptFile(key: SecretKey, salt: ByteArray, input: InputStream, output: OutputStream)

    /**
     * Read the FVC1 header from [input], decrypt the body using [key] + the per-file IV from
     * the header. Does NOT close either stream.
     */
    fun decryptFile(key: SecretKey, input: InputStream, output: OutputStream): BinaryResult<Unit, DecryptionError>

    /**
     * Self-contained decrypt: read FVC1 header, derive key from [password] + header's salt,
     * then decrypt. Used for restore-after-reinstall when no local Room key cache exists.
     * Does NOT close either stream.
     */
    fun decryptFileWithPassword(
        password: String,
        input: InputStream,
        output: OutputStream,
    ): BinaryResult<Unit, DecryptionError>
}
