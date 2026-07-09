package ch.abwesend.foldervault.infrastructure.crypto

import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.mapValue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Fvc1CipherTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val cipher = Fvc1Cipher()
    val password = "correct-horse-battery-staple"
    val wrongPassword = "wrong-password-entirely"
    val plaintext = "Hello, FolderVault! This is test content with some reasonable length.".toByteArray()

    fun encryptBytes(salt: ByteArray, key: SecretKey, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.encryptFile(key, salt, ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    fun decryptBytes(key: SecretKey, ciphertext: ByteArray): BinaryResult<ByteArray, DecryptionError> {
        val out = ByteArrayOutputStream()
        return cipher.decryptFile(key, ByteArrayInputStream(ciphertext), out)
            .mapValue { out.toByteArray() }
    }

    /**
     * Assembles an FVC1 blob with fully controllable header fields so decrypt-side parameter
     * handling and header validation can be exercised independently of [Fvc1Cipher.encryptFile]
     * (which always writes the current defaults).
     */
    fun buildBlob(
        version: Int = 1,
        kdfId: Int = 1,
        iterations: Int = 310_000,
        salt: ByteArray = ByteArray(16),
        iv: ByteArray = ByteArray(12),
        body: ByteArray = ByteArray(16),
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            write("FVC1".toByteArray(Charsets.US_ASCII))
            writeByte(version)
            writeByte(kdfId)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            writeByte(iv.size)
            write(iv)
            write(body)
            flush()
        }
        return bos.toByteArray()
    }

    /** Builds a genuinely decryptable blob whose header records a chosen [iterations] count. */
    fun encryptWithIterations(salt: ByteArray, iv: ByteArray, iterations: Int, data: ByteArray): ByteArray {
        val key = cipher.deriveKey(password, salt, iterations)
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }.doFinal(data)
        return buildBlob(iterations = iterations, salt = salt, iv = iv, body = body)
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    "encrypt then decryptFile with correct key recovers plaintext" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key, plaintext)

        val result = decryptBytes(key, ciphertext)
        (result as SuccessResult).value shouldBe plaintext
    }

    "decryptFileWithPassword round-trips without pre-derived key" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key, plaintext)

        val out = ByteArrayOutputStream()
        val result = cipher.decryptFileWithPassword(password, ByteArrayInputStream(ciphertext), out)

        result.shouldBeInstanceOf<SuccessResult<*>>()
        out.toByteArray() shouldBe plaintext
    }

    "empty plaintext round-trips correctly" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key, ByteArray(0))

        val result = decryptBytes(key, ciphertext)
        (result as SuccessResult).value shouldBe ByteArray(0)
    }

    // ── Wrong password / key ──────────────────────────────────────────────────

    "decryptFile with wrong key returns INVALID_PASSWORD" {
        val salt = cipher.generateBackupSalt()
        val ciphertext = encryptBytes(salt, cipher.deriveKey(password, salt), plaintext)

        val result = decryptBytes(cipher.deriveKey(wrongPassword, salt), ciphertext)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    "decryptFileWithPassword with wrong password returns INVALID_PASSWORD" {
        val salt = cipher.generateBackupSalt()
        val ciphertext = encryptBytes(salt, cipher.deriveKey(password, salt), plaintext)

        val out = ByteArrayOutputStream()
        val result = cipher.decryptFileWithPassword(wrongPassword, ByteArrayInputStream(ciphertext), out)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    "corrupt last byte (GCM tag) returns INVALID_PASSWORD" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key, plaintext)
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0xFF).toByte()

        val result = decryptBytes(key, ciphertext)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    "garbage bytes (invalid magic) return INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        val garbage = ByteArray(64) { it.toByte() }
        val result = decryptBytes(key, garbage)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }

    // ── Key reuse / uniqueness ────────────────────────────────────────────────

    "same key encrypts same plaintext to different ciphertexts (random IVs)" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ct1 = encryptBytes(salt, key, plaintext)
        val ct2 = encryptBytes(salt, key, plaintext)
        ct1.contentEquals(ct2) shouldBe false
    }

    "key derived twice from same password+salt is interchangeable" {
        val salt = cipher.generateBackupSalt()
        val key1 = cipher.deriveKey(password, salt)
        val key2 = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key1, plaintext)

        val result = decryptBytes(key2, ciphertext)
        (result as SuccessResult).value shouldBe plaintext
    }

    // ── Header ────────────────────────────────────────────────────────────────

    "encrypted output has valid FVC1 header with correct params" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)
        val ciphertext = encryptBytes(salt, key, plaintext)

        val header = Fvc1Header.readFrom(ByteArrayInputStream(ciphertext))
        header.version shouldBe 1
        header.kdfId shouldBe 1
        header.iterations shouldBe 310_000
        header.salt.size shouldBe 16
        header.iv.size shouldBe 12
        header.salt.contentEquals(salt) shouldBe true
    }

    // ── KDF parameters honored on decrypt (BUG-5) ─────────────────────────────

    "decryptFileWithPassword honors a non-default iteration count from the header" {
        val salt = cipher.generateBackupSalt()
        val iv = ByteArray(12) { 7 }
        val blob = encryptWithIterations(salt, iv, iterations = 4096, data = plaintext)

        val out = ByteArrayOutputStream()
        val result = cipher.decryptFileWithPassword(password, ByteArrayInputStream(blob), out)

        result.shouldBeInstanceOf<SuccessResult<*>>()
        out.toByteArray() shouldBe plaintext
    }

    "a key derived at the default iteration count cannot decrypt a file written with fewer iterations" {
        // Proves the iteration count actually flows into derivation: the same password + salt but a
        // different iteration count yields a different key, so a default-derived key must fail.
        val salt = cipher.generateBackupSalt()
        val iv = ByteArray(12) { 3 }
        val blob = encryptWithIterations(salt, iv, iterations = 4096, data = plaintext)

        val result = decryptBytes(cipher.deriveKey(password, salt), blob)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    "deriveKey with different iteration counts produces different keys" {
        val salt = cipher.generateBackupSalt()
        val ciphertext = encryptBytes(salt, cipher.deriveKey(password, salt, 4096), plaintext)

        val result = decryptBytes(cipher.deriveKey(password, salt, 8192), ciphertext)
        (result as ErrorResult).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    // ── Header validation rejects malformed / unsupported fields (BUG-5) ──────

    "an unsupported header version is rejected as INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        (decryptBytes(key, buildBlob(version = 2)) as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }

    "an unknown KDF id is rejected as INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        (decryptBytes(key, buildBlob(kdfId = 2)) as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }

    "an out-of-range iteration count is rejected as INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        (decryptBytes(key, buildBlob(iterations = 0)) as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }

    "an out-of-range salt size is rejected as INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        val blob = buildBlob(salt = ByteArray(4))
        (decryptBytes(key, blob) as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }

    "an invalid IV size is rejected as INVALID_FILE" {
        val key = cipher.deriveKey(password, cipher.generateBackupSalt())
        val blob = buildBlob(iv = ByteArray(16))
        (decryptBytes(key, blob) as ErrorResult).error shouldBe DecryptionError.INVALID_FILE
    }
})
