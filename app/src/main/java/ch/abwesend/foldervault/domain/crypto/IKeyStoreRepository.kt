package ch.abwesend.foldervault.domain.crypto

import javax.crypto.SecretKey

interface IKeyStoreRepository {
    fun getOrCreateKey(): SecretKey
    fun getKey(): SecretKey?
    fun deleteKey(): Boolean
}
