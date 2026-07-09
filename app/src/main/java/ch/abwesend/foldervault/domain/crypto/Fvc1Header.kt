package ch.abwesend.foldervault.domain.crypto

import java.io.DataInputStream
import java.io.InputStream

class Fvc1Header private constructor(
    val version: Int,
    val kdfId: Int,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
) {
    companion object {
        private val MAGIC = "FVC1".toByteArray(Charsets.US_ASCII)

        /** Highest FVC1 file-format version this build understands. */
        const val SUPPORTED_VERSION = 1

        /** PBKDF2-with-HMAC-SHA256 — the only KDF defined so far. */
        const val KDF_ID_PBKDF2_SHA256 = 1

        private const val MIN_ITERATIONS = 1
        private const val MAX_ITERATIONS = 10_000_000
        private const val MIN_SALT_SIZE = 8
        private const val MAX_SALT_SIZE = 64
        private const val GCM_IV_SIZE = 12

        /**
         * Parse and validate an FVC1 header from [stream], leaving the stream positioned at the
         * first ciphertext byte.
         *
         * Every field is bounds-checked so that a corrupted length byte fails fast with an
         * [IllegalArgumentException] (classified as `INVALID_FILE`) instead of silently allocating
         * a wrong-size buffer or feeding out-of-range KDF parameters into key derivation (BUG-5).
         */
        fun readFrom(stream: InputStream): Fvc1Header {
            val dis = DataInputStream(stream)
            val magic = ByteArray(MAGIC.size).also { dis.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "Not a valid FVC1 file: magic bytes mismatch" }
            val version = dis.readUnsignedByte()
            require(version == SUPPORTED_VERSION) { "Unsupported FVC1 version: $version" }
            val kdfId = dis.readUnsignedByte()
            require(kdfId == KDF_ID_PBKDF2_SHA256) { "Unsupported FVC1 KDF id: $kdfId" }
            val iterations = dis.readInt()
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "FVC1 iterations out of range: $iterations" }
            val saltSize = dis.readUnsignedByte()
            require(saltSize in MIN_SALT_SIZE..MAX_SALT_SIZE) { "FVC1 salt size out of range: $saltSize" }
            val salt = ByteArray(saltSize).also { dis.readFully(it) }
            val ivSize = dis.readUnsignedByte()
            require(ivSize == GCM_IV_SIZE) { "FVC1 IV size invalid: $ivSize" }
            val iv = ByteArray(ivSize).also { dis.readFully(it) }
            return Fvc1Header(version, kdfId, iterations, salt, iv)
        }
    }
}
