package io.github.aimindseye.sablehealthbridge.core

import java.time.Instant

/** The journal is account-scoped and stores opaque identities, never raw sleep records. */
enum class SyncJournalState { UNSEEN, PREVIOUSLY_CONFIRMED, UNRESOLVED_ATTEMPT }

enum class AutomaticSleepAction {
    INSERT_NEW,
    SKIP_VERIFIED,
    HOLD_SOURCE,
    HOLD_EXISTING_CHANGE,
    HOLD_CONFLICT,
    HOLD_PREVIOUSLY_SEEN,
    HOLD_UNRESOLVED_ATTEMPT,
}

/** Only new, completed records are written automatically; revisions and removals are manual. */
fun automaticSleepAction(
    session: SleepSession,
    now: Instant,
    decision: Decision,
    journal: SyncJournalState,
): AutomaticSleepAction =
    when {
        !automaticSourceReady(session, now) -> AutomaticSleepAction.HOLD_SOURCE
        decision.action == Action.ALREADY_IMPORTED || decision.action == Action.EXTERNAL_MATCH ->
            AutomaticSleepAction.SKIP_VERIFIED
        journal == SyncJournalState.UNRESOLVED_ATTEMPT -> AutomaticSleepAction.HOLD_UNRESOLVED_ATTEMPT
        decision.action == Action.CONFLICT || decision.action == Action.REMOVE_OWN_DUPLICATE ->
            AutomaticSleepAction.HOLD_CONFLICT
        decision.action == Action.UPDATE -> AutomaticSleepAction.HOLD_EXISTING_CHANGE
        journal == SyncJournalState.PREVIOUSLY_CONFIRMED -> AutomaticSleepAction.HOLD_PREVIOUSLY_SEEN
        else -> AutomaticSleepAction.INSERT_NEW
    }

private fun automaticSourceReady(
    session: SleepSession,
    now: Instant,
): Boolean {
    val source = session.sourceInfo ?: return false
    return validSession(session, now) &&
        source.processed != false &&
        source.stagesStatus == "SUCCEEDED" &&
        session.stages.isNotEmpty() &&
        session.asleepMinutes() > 0
}
