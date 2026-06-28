package ch.abwesend.foldervault.infrastructure.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeyStoreRepository : IKeyStoreRepository {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_KEY_ALIAS = "FolderVaultBackupKey"
        private const val AES_KEY_SIZE_BITS = 256
    }

    override fun getOrCreateKey(): SecretKey =
        withKeyStore { it.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey } ?: generateKey()

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(AES_KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun getKey(): SecretKey? = withKeyStore { it.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey }

    override fun deleteKey(): Boolean = try {
        withKeyStore { keyStore ->
            if (keyStore.containsAlias(KEYSTORE_KEY_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_KEY_ALIAS)
                logger.debug("Deleted KeyStore key")
            }
        }
        true
    } catch (e: Exception) {
        e.rethrowCancellation()
        logger.warning("Failed to delete KeyStore key", e)
        false
    }

    private fun <T> withKeyStore(block: (KeyStore) -> T): T =
        KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }.let(block)
}
