package ch.abwesend.foldervault.domain.logging

object FileNameRedactor {
    fun redact(name: String): String {
        if (name.isEmpty()) return name
        if (name.startsWith('.')) {
            val afterDot = name.drop(1)
            return if (afterDot.isEmpty()) name else ".***"
        }
        val lastDot = name.lastIndexOf('.')
        return if (lastDot <= 0) {
            "${name.first()}***"
        } else {
            "${name.first()}***${name.substring(lastDot)}"
        }
    }

    fun redactPath(relativePath: String): String {
        if (relativePath.isEmpty()) return relativePath
        return relativePath.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) segment else redact(segment)
        }
    }
}
