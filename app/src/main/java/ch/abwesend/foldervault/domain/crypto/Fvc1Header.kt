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

        fun readFrom(stream: InputStream): Fvc1Header {
            val dis = DataInputStream(stream)
            val magic = ByteArray(MAGIC.size).also { dis.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "Not a valid FVC1 file: magic bytes mismatch" }
            val version = dis.readUnsignedByte()
            val kdfId = dis.readUnsignedByte()
            val iterations = dis.readInt()
            val salt = ByteArray(dis.readUnsignedByte()).also { dis.readFully(it) }
            val iv = ByteArray(dis.readUnsignedByte()).also { dis.readFully(it) }
            return Fvc1Header(version, kdfId, iterations, salt, iv)
        }
    }
}
