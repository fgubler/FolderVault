package ch.abwesend.foldervault.domain.logging

object FileNameRedactor {
    fun redact(name: String): String = when {
        name.isEmpty() -> name
        name.startsWith('.') -> if (name.length == 1) name else ".***"
        else -> {
            val lastDot = name.lastIndexOf('.')
            if (lastDot <= 0) "${name.first()}***" else "${name.first()}***${name.substring(lastDot)}"
        }
    }

    fun redactPath(relativePath: String): String =
        relativePath.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) segment else redact(segment)
        }
}
