package ch.abwesend.foldervault.infrastructure.restore

/** What one probe file said about the password. */
internal enum class ProbeOutcome {
    /** The probe decrypted cleanly — the password is right, no further probing needed. */
    CORRECT_PASSWORD,

    /**
     * The probe failed the GCM tag check. Suggests a wrong password — but a truncated or
     * otherwise corrupt file fails in exactly the same way, so one of these does not settle it.
     */
    WRONG_PASSWORD,

    /** The probe was unusable (unreadable header, unreadable stream) — it says nothing at all. */
    INCONCLUSIVE,
}

/**
 * Picks which encrypted files to verify the password against, and turns their outcomes into the
 * go / no-go decision for a whole-folder restore.
 *
 * The point of probing at all is to fail a wrong password *before* writing anything, so the
 * "no files were modified" promise holds. The old implementation probed exactly one file — the
 * smallest — which made the whole restore report "wrong password" whenever that single file
 * happened to be broken (a zero-byte leftover from an interrupted upload, an incomplete download
 * from Drive). Probing several files and requiring *all* of them to fail the tag check keeps the
 * fast wrong-password rejection while a few damaged files no longer block a correct password.
 *
 * Pure and Android-free on purpose, so the decision is unit-testable without SAF.
 */
internal object PasswordProbe {

    /**
     * How many files to probe at most. Every probe is a full decrypt-to-nowhere, so this trades
     * a bounded amount of work against the chance that *all* picked probes are damaged. Five
     * smallest-first files is negligible work next to the restore itself.
     */
    const val MAX_PROBES = 5

    /**
     * Chooses up to [MAX_PROBES] probe candidates, smallest first — a full GCM decrypt has to
     * read to the tag at the end of the file, so probing the smallest files keeps verification
     * from costing a multi-GB read.
     *
     * Empty files are dropped: they cannot carry a header, so they could only ever come back
     * `INCONCLUSIVE` while crowding out a usable candidate. If *every* candidate reports size 0
     * the filter is skipped instead — a provider that does not report sizes at all (`length()`
     * returns 0 for unknown, not just for empty) must not end up with nothing to probe.
     */
    fun <T> selectCandidates(candidates: List<T>, size: (T) -> Long): List<T> {
        val nonEmpty = candidates.filter { size(it) > 0L }
        val usable = nonEmpty.ifEmpty { candidates }
        return usable.sortedBy { size(it) }.take(MAX_PROBES)
    }

    /**
     * Decides whether the restore may proceed, given the outcomes of the probes actually run.
     *
     * - Any [ProbeOutcome.CORRECT_PASSWORD] → proceed; the password is proven right.
     * - Otherwise any [ProbeOutcome.WRONG_PASSWORD] → stop. Every usable probe failed its tag
     *   check, which several independent files do not do by coincidence.
     * - Otherwise (no probes at all, or only [ProbeOutcome.INCONCLUSIVE] ones) → proceed. Nothing
     *   was learned about the password, and refusing here would turn "this folder holds damaged
     *   files" into a false "wrong password". The per-file `failed` counter reports the damage.
     */
    fun shouldProceed(outcomes: List<ProbeOutcome>): Boolean = when {
        outcomes.contains(ProbeOutcome.CORRECT_PASSWORD) -> true
        outcomes.contains(ProbeOutcome.WRONG_PASSWORD) -> false
        else -> true
    }
}
