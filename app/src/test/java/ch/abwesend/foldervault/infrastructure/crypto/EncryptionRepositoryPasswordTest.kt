package ch.abwesend.foldervault.infrastructure.crypto

import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Covers the algorithm-confusion hardening in [EncryptionRepository.decryptPassword] (SEC-2):
 * the cipher is instantiated from constants the stored *version* maps to, and a tampered
 * algorithm / tagLength / version in the persisted blob is rejected rather than honored.
 *
 * Uses a hand-written fake [IKeyStoreRepository] backed by an in-memory AES key so the whole
 * round-trip runs on the plain JVM — no Android KeyStore, MockK or Robolectric required.
 */
class EncryptionRepositoryPasswordTest : StringSpec({

    /** Returns the same in-memory AES-256 key for both create and get, so encrypt/decrypt pair up. */
    class FakeKeyStoreRepository(private val key: SecretKey) : IKeyStoreRepository {
        override fun getOrCreateKey(): SecretKey = key
        override fun getKey(): SecretKey = key
        override fun deleteKey(): Boolean = true
    }

    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    beforeSpec {
        startKoin {
            modules(module { single<IKeyStoreRepository> { FakeKeyStoreRepository(key) } })
        }
    }

    afterSpec { stopKoin() }

    // Lazily constructed: EncryptionRepository resolves its KeyStore dependency from Koin at
    // construction time, so it must not be built until beforeSpec has started Koin.
    val repo by lazy { EncryptionRepository() }
    val secret = "correct-horse-battery-staple"

    /** Re-serializes the blob JSON with a single field replaced, to simulate a tampered payload. */
    fun tamper(blob: String, field: String, value: JsonElement): String {
        val mutated = Json.parseToJsonElement(blob).jsonObject.toMutableMap().apply { put(field, value) }
        return JsonObject(mutated).toString()
    }

    fun encryptedBlob(): String = (repo.encryptPassword(secret) as SuccessResult).value

    "encrypt then decrypt round-trips the stored password" {
        (repo.decryptPassword(encryptedBlob()) as SuccessResult).value shouldBe secret
    }

    "a tampered version is rejected even though its algorithm/tagLength are otherwise valid" {
        // This is the case the old algorithm-from-data path would have wrongly decrypted: the blob is
        // genuine AES-GCM-128, only the version marker is bumped to an unsupported value.
        val tampered = tamper(encryptedBlob(), "version", JsonPrimitive(2))
        repo.decryptPassword(tampered).shouldBeInstanceOf<ErrorResult<*>>()
    }

    "a tampered algorithm string is rejected instead of instantiating that cipher" {
        val tampered = tamper(encryptedBlob(), "algorithm", JsonPrimitive("AES/CBC/PKCS5Padding"))
        repo.decryptPassword(tampered).shouldBeInstanceOf<ErrorResult<*>>()
    }

    "a tampered tag length is rejected" {
        val tampered = tamper(encryptedBlob(), "tagLength", JsonPrimitive(32))
        repo.decryptPassword(tampered).shouldBeInstanceOf<ErrorResult<*>>()
    }
})
