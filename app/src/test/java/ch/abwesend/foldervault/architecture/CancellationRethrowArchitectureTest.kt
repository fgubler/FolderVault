package ch.abwesend.foldervault.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every generic `catch (e: Exception)` in main source must honor coroutine cancellation:
 * either call `e.rethrowCancellation()` (defined in `ResultExtensions.kt`), unconditionally
 * `throw e`, or have a preceding `catch (X: CancellationException)` clause that rethrows.
 *
 * Why: swallowing [kotlinx.coroutines.CancellationException] and routing it through `logger.error`
 * surfaces worker / coroutine cancellation as a Crashlytics fatal — see the original `BackupRunner`
 * incident.
 *
 * The check is text-based: Konsist 0.17 doesn't expose try/catch as structured nodes.
 */
class CancellationRethrowArchitectureTest : FunSpec({

    test("every catch (X: Exception) rethrows CancellationException") {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.path.contains("/main/") && !it.path.contains("/test/") }
            // The file that defines `rethrowCancellation()` keeps a KDoc example showing the
            // canonical usage; excluding it keeps the rule from chasing its own tail.
            .filterNot { it.path.endsWith("/domain/result/ResultExtensions.kt") }
            .flatMap { file -> findViolations(file.path, file.text) }

        violations.shouldBeEmpty()
    }
})

private val genericCatchPattern = Regex(
    """catch\s*\(\s*(\w+)\s*:\s*(?:kotlin\.)?(?:java\.lang\.)?Exception\s*\)\s*\{"""
)
private val cancellationCatchPattern = Regex(
    """catch\s*\(\s*\w+\s*:\s*CancellationException\s*\)"""
)
private val precedingTryPattern = Regex("""\btry\s*\{""")

private fun findViolations(path: String, source: String): List<String> =
    genericCatchPattern.findAll(source).toList().mapNotNull { match ->
        val varName = match.groupValues[1]
        val openBracePos = match.range.last
        val closeBracePos = matchingClose(source, openBracePos)
        val body = if (closeBracePos > openBracePos) {
            source.substring(openBracePos + 1, closeBracePos)
        } else {
            return@mapNotNull null // malformed; skip rather than crash
        }
        val bodyRethrows = body.contains("$varName.rethrowCancellation()") ||
            Regex("""\bthrow\s+$varName\b""").containsMatchIn(body)
        val precededByCancellationCatch = hasPrecedingCancellationCatch(source, match.range.first)
        if (bodyRethrows || precededByCancellationCatch) {
            null
        } else {
            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
            "$path:$line — catch ($varName: Exception) does not rethrow CancellationException " +
                "(use $varName.rethrowCancellation() or a preceding catch (X: CancellationException))"
        }
    }

private fun matchingClose(source: String, openBracePos: Int): Int {
    var depth = 1
    var i = openBracePos + 1
    while (i < source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

private fun hasPrecedingCancellationCatch(source: String, catchKeywordPos: Int): Boolean {
    val tryStart = precedingTryPattern.findAll(source.substring(0, catchKeywordPos))
        .lastOrNull()?.range?.first ?: return false
    val between = source.substring(tryStart, catchKeywordPos)
    return cancellationCatchPattern.containsMatchIn(between)
}
