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

    /**
     * Redacts only the path-like tokens of a free-form message, leaving ordinary words intact.
     *
     * A token counts as path-like when it contains a `/`. That reliably catches this app's
     * relative file paths (e.g. `photos/2024/img.jpg`) while never touching prose or
     * fully-qualified class names (which use `.` but no `/`) — so, unlike calling [redactPath]
     * on a whole sentence, the surrounding message stays readable. Each matching token is
     * reduced via [redactPath], with trailing sentence punctuation preserved.
     *
     * This is the fallback for text we do NOT author, such as sanitized exception messages.
     * For our own log statements, wrap the path/name argument with [redactPath]/[redact] at the
     * call site so every word is guaranteed to survive — a standalone file name without a slash
     * is deliberately NOT caught here.
     */
    fun redactPathsIn(message: String): String =
        NON_WHITESPACE.replace(message) { match ->
            val token = match.value
            if (token.contains('/')) {
                val trailing = TRAILING_PUNCTUATION.find(token)?.value.orEmpty()
                redactPath(token.dropLast(trailing.length)) + trailing
            } else {
                token
            }
        }

    private val NON_WHITESPACE = Regex("""\S+""")
    private val TRAILING_PUNCTUATION = Regex("""[.,:;)\]}]+$""")
}
