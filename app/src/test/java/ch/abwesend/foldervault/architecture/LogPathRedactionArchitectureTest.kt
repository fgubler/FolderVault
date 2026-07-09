package ch.abwesend.foldervault.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * File names and paths must never reach the log verbatim: `CrashlyticsSink` forwards breadcrumb
 * messages unchanged (see BUG-3), so redaction has to happen at the call site. This test enforces
 * that any logger call interpolating a file/folder identifier wraps it with `FileNameRedactor`.
 *
 * A "sensitive identifier" is one of `relativePath`, `remoteName`, `displayName`, `fileName`, or a
 * `<receiver>.name` property access. A call is considered compliant if its text also mentions
 * `redact` (i.e. `FileNameRedactor.redact`/`redactPath`) — the guard is intentionally coarse: it
 * only asks that a redactor is applied somewhere in the same call, not exactly where.
 *
 * `key.name` is exempt: `key` is a DataStore `Preferences.Key` whose `name` is a static key
 * constant (e.g. `"cloud_roots"`), never user data. Any other receiver named `key` is likewise
 * treated as a preference key. Add further exemptions consciously — the default is to flag.
 */
class LogPathRedactionArchitectureTest : FunSpec({
    val callStart = Regex("""log(?:ger)?\.(?:debug|info|warning|error)\s*\(""")
    val explicitFields = Regex("""relativePath|remoteName|displayName|fileName""")
    val nameAccess = Regex("""(\w+)\.name\b""")
    val safeNameReceivers = setOf("key")

    test("logger calls interpolating a file name or path redact it at the call site") {
        val violations = Konsist.scopeFromProject()
            .files
            .filterNot { it.path.contains("/test/") || it.path.contains("/androidTest/") }
            .flatMap { file ->
                callStart.findAll(file.text).mapNotNull { match ->
                    val call = extractBalancedCall(file.text, match.range.last)
                    val leaksField = explicitFields.containsMatchIn(call)
                    val leaksName = nameAccess.findAll(call).any { it.groupValues[1] !in safeNameReceivers }
                    val leaks = (leaksField || leaksName) && !call.contains("redact")
                    if (leaks) "${file.path}: ${call.replace(Regex("\\s+"), " ").take(140)}" else null
                }
            }

        violations.shouldBeEmpty()
    }
})

/**
 * Returns the text of a call from its opening parenthesis at [openParenIndex] to the matching
 * closing parenthesis. Parentheses inside string literals are ignored, while those inside `${...}`
 * interpolations are counted, so a message like `"folder ($id)"` does not prematurely close the
 * call and `redactPath(path)` inside an interpolation is included.
 */
private fun extractBalancedCall(text: String, openParenIndex: Int): String {
    var depth = 0
    var index = openParenIndex
    var inString = false
    var escaped = false
    val braceStack = ArrayDeque<Char>()
    while (index < text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '$' && index + 1 < text.length && text[index + 1] == '{' -> {
                    inString = false
                    braceStack.addLast('s') // this '{' returns to string when it closes
                    index++
                }
                char == '"' -> inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParenIndex, index + 1)
                }
                '{' -> braceStack.addLast('c')
                '}' -> if (braceStack.removeLastOrNull() == 's') inString = true
            }
        }
        index++
    }
    return text.substring(openParenIndex)
}
