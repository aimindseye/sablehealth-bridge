package io.github.aimindseye.sablehealthbridge.core

import org.junit.Test
import java.time.Instant

class AutomaticSleepPolicyTest {
    private val start = Instant.parse("2026-11-26T05:00:00Z")
    private val end = start.plusSeconds(3600)
    private val stages = listOf(Stage(start, end, StageType.LIGHT))
    private val source =
        SourceSleepInfo(
            "STAGES",
            "SUCCEEDED",
            true,
            true,
            false,
            false,
            start,
            end,
            null,
            stages,
            listOf("LIGHT"),
        )
    private val session =
        SleepSession(
            sessionKey("fixture/sleep", start, end),
            start,
            end,
            null,
            null,
            stages,
            sourceInfo = source,
        )

    @Test
    fun exhaustiveDecisionsAcrossJournalStates() {
        for (journal in SyncJournalState.entries) {
            for (action in Action.entries) {
                check(automaticSleepAction(session, end, Decision(action), journal) == expected(action, journal))
            }
        }
    }

    @Test
    fun doesNotResurrectPreviouslyConfirmedOrUncertainWrites() {
        val missing = Decision(Action.INSERT)
        check(
            automaticSleepAction(session, end, missing, SyncJournalState.PREVIOUSLY_CONFIRMED) ==
                AutomaticSleepAction.HOLD_PREVIOUSLY_SEEN,
        )
        check(
            automaticSleepAction(session, end, missing, SyncJournalState.UNRESOLVED_ATTEMPT) ==
                AutomaticSleepAction.HOLD_UNRESOLVED_ATTEMPT,
        )
        val exact = Decision(Action.ALREADY_IMPORTED)
        check(
            automaticSleepAction(session, end, exact, SyncJournalState.UNRESOLVED_ATTEMPT) ==
                AutomaticSleepAction.SKIP_VERIFIED,
        )
    }

    @Test
    fun malformedOrUnfinishedSourcesNeverWrite() {
        val cases =
            listOf(
                session.copy(sourceInfo = null),
                session.copy(sourceInfo = source.copy(processed = false)),
                session.copy(sourceInfo = source.copy(stagesStatus = "OTHER")),
                session.copy(stages = emptyList()),
                session.copy(stages = listOf(Stage(start, end, StageType.UNKNOWN))),
                session.copy(end = start),
                session.copy(end = end.plusSeconds(1)),
            )
        cases.forEach { invalid ->
            check(
                automaticSleepAction(invalid, end, Decision(Action.INSERT), SyncJournalState.UNSEEN) ==
                    AutomaticSleepAction.HOLD_SOURCE,
            )
        }
    }

    @Test
    fun acceptsCompleteWearableSessionWithOptionalProcessedFlag() {
        val complete = session.copy(sourceInfo = source.copy(processed = null))
        check(
            automaticSleepAction(complete, end, Decision(Action.INSERT), SyncJournalState.UNSEEN) ==
                AutomaticSleepAction.INSERT_NEW,
        )
    }

    private fun expected(
        action: Action,
        journal: SyncJournalState,
    ): AutomaticSleepAction {
        if (action in setOf(Action.ALREADY_IMPORTED, Action.EXTERNAL_MATCH)) return AutomaticSleepAction.SKIP_VERIFIED
        return when (journal) {
            SyncJournalState.UNRESOLVED_ATTEMPT -> AutomaticSleepAction.HOLD_UNRESOLVED_ATTEMPT
            SyncJournalState.PREVIOUSLY_CONFIRMED, SyncJournalState.UNSEEN ->
                when (action) {
                    Action.CONFLICT, Action.REMOVE_OWN_DUPLICATE -> AutomaticSleepAction.HOLD_CONFLICT
                    Action.UPDATE -> AutomaticSleepAction.HOLD_EXISTING_CHANGE
                    else ->
                        if (journal == SyncJournalState.UNSEEN) {
                            AutomaticSleepAction.INSERT_NEW
                        } else {
                            AutomaticSleepAction.HOLD_PREVIOUSLY_SEEN
                        }
                }
        }
    }
}
