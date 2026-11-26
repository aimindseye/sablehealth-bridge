package io.github.aimindseye.sablehealthbridge

import android.content.SharedPreferences
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SyncJournalState

private const val JOURNAL_PREFIX = "seen_"
private const val MAX_JOURNAL_ENTRIES = 5000

/** Durable reservations survive process death. Never discard history to make room for new writes. */
internal class SyncJournal(
    private val prefs: SharedPreferences,
) {
    fun state(session: SleepSession): SyncJournalState =
        when (prefs.getString(journalName(session), null)) {
            null -> SyncJournalState.UNSEEN
            SyncJournalState.PREVIOUSLY_CONFIRMED.name -> SyncJournalState.PREVIOUSLY_CONFIRMED
            else -> SyncJournalState.UNRESOLVED_ATTEMPT
        }

    fun reserve(session: SleepSession) {
        check(state(session) == SyncJournalState.UNSEEN)
        persist(session, SyncJournalState.UNRESOLVED_ATTEMPT)
    }

    fun confirm(session: SleepSession) {
        persist(session, SyncJournalState.PREVIOUSLY_CONFIRMED)
    }

    private fun persist(
        session: SleepSession,
        state: SyncJournalState,
    ) {
        val name = journalName(session)
        check(prefs.contains(name) || prefs.all.keys.count { it.startsWith(JOURNAL_PREFIX) } < MAX_JOURNAL_ENTRIES)
        val editor = prefs.edit()
        editor.putString(name, state.name)
        check(editor.commit())
    }
}

private fun journalName(session: SleepSession): String {
    require(session.key.matches(Regex("sb-r1-[a-f0-9]{64}")))
    return JOURNAL_PREFIX + session.key
}
