package ch.abwesend.foldervault.domain.crypto

import javax.crypto.AEADBadTagException

enum class DecryptionError {
    INVALID_PASSWORD,
    INVALID_FILE,
    UNKNOWN,
}

fun classifyDecryptionError(e: Exception): DecryptionError = when {
    e is AEADBadTagException -> DecryptionError.INVALID_PASSWORD
    e.cause is AEADBadTagException -> DecryptionError.INVALID_PASSWORD
    e is IllegalArgumentException -> DecryptionError.INVALID_FILE
    else -> DecryptionError.UNKNOWN
}
