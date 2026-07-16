package ch.abwesend.foldervault.domain.crypto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

class Fvc1Header private constructor(
    val version: Int,
    val kdfId: Int,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    /**
     * The exact raw header bytes as they appeared on the wire (magic … iv, inclusive), captured so
     * they can be fed verbatim into GCM as additional authenticated data (AAD) for [VERSION_WITH_AAD]
     * files. This binds the header to the ciphertext — any tampering with magic/version/kdf/iterations/
     * salt/iv fails the GCM tag check on decrypt (SEC-3).
     */
    val headerBytes: ByteArray,
) {
    companion object {
        private val MAGIC = "FVC1".toByteArray(Charsets.US_ASCII)

        /** Filename suffix marking a file as FVC1-encrypted (`report.pdf` → `report.pdf.crypt`). */
        const val CRYPT_FILE_SUFFIX = ".crypt"

        /** Original format: header written in the clear, not bound to the ciphertext. */
        const val VERSION_WITHOUT_AAD = 1

        /** Current format: identical layout, but the header bytes are authenticated as GCM AAD (SEC-3). */
        const val VERSION_WITH_AAD = 2

        /** Highest FVC1 file-format version this build understands. */
        const val SUPPORTED_VERSION = VERSION_WITH_AAD

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
         *
         * The exact bytes consumed are recorded into [Fvc1Header.headerBytes] so the header can be
         * re-supplied as GCM AAD on decrypt (SEC-3).
         */
        fun readFrom(stream: InputStream): Fvc1Header {
            val recorder = ByteArrayOutputStream()
            val dis = DataInputStream(RecordingInputStream(stream, recorder))
            val magic = ByteArray(MAGIC.size).also { dis.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "Not a valid FVC1 file: magic bytes mismatch" }
            val version = dis.readUnsignedByte()
            require(version in VERSION_WITHOUT_AAD..SUPPORTED_VERSION) { "Unsupported FVC1 version: $version" }
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
            return Fvc1Header(version, kdfId, iterations, salt, iv, recorder.toByteArray())
        }
    }

    /**
     * Pass-through [InputStream] that mirrors every consumed byte into [recorder]. Wrapping the
     * source stream (rather than the [DataInputStream]) records exactly the header bytes, since
     * [DataInputStream] reads no further than each field requires and never buffers ahead.
     */
    private class RecordingInputStream(
        private val delegate: InputStream,
        private val recorder: ByteArrayOutputStream,
    ) : InputStream() {
        override fun read(): Int {
            val byte = delegate.read()
            if (byte != -1) {
                recorder.write(byte)
            }
            return byte
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(destination, offset, length)
            if (count > 0) {
                recorder.write(destination, offset, count)
            }
            return count
        }
    }
}
