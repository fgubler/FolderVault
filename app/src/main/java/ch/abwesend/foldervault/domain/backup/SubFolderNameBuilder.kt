package ch.abwesend.foldervault.domain.backup

import java.security.MessageDigest

/**
 * Builds the Drive sub-folder name for a backup config from the user's display name and the SAF
 * tree URI of the source folder. The hash suffix avoids collisions between two configs whose
 * display names happen to match but point at different local folders.
 *
 * Result shape: `<sanitized-displayName>_<6-hex-chars-of-SHA256(treeUri)>`.
 */
object SubFolderNameBuilder {

    private const val FALLBACK_DISPLAY_NAME = "backup"
    private const val MAX_DISPLAY_NAME_LENGTH = 80
    private const val HASH_HEX_CHARS = 6
    private const val NIBBLE_BITS = 4
    private const val LOW_NIBBLE_MASK = 0x0F
    private const val BYTE_MASK = 0xFF

    fun buildName(displayName: String, treeUri: String): String {
        val sanitized = sanitizeDisplayName(displayName)
        val hash = shortHash(treeUri)
        return "${sanitized}_$hash"
    }

    private fun sanitizeDisplayName(name: String): String {
        val cleaned = name
            .replace('/', '_')
            .replace(Regex("\\p{Cntrl}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val capped = if (cleaned.length > MAX_DISPLAY_NAME_LENGTH) {
            cleaned.substring(0, MAX_DISPLAY_NAME_LENGTH).trim()
        } else {
            cleaned
        }
        return capped.ifEmpty { FALLBACK_DISPLAY_NAME }
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(HASH_HEX_CHARS) {
            for (i in 0 until HASH_HEX_CHARS / 2) {
                val b = digest[i].toInt() and BYTE_MASK
                append(HEX_CHARS[b ushr NIBBLE_BITS])
                append(HEX_CHARS[b and LOW_NIBBLE_MASK])
            }
        }
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
