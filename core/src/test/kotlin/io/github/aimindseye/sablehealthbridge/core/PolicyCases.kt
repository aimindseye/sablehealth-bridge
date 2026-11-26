package io.github.aimindseye.sablehealthbridge.core

import java.time.Instant

object PolicyCases {
    private val start = Instant.parse("2025-01-01T01:00:00Z")
    private val end = start.plusSeconds(3600)
    private val stages =
        listOf(
            Stage(start, start.plusSeconds(600), StageType.AWAKE),
            Stage(start.plusSeconds(600), end, StageType.DEEP),
        )
    private val session = SleepSession("fixture", start, end, null, null, stages)
    private val own = ExistingSleep("own", BRIDGE_PACKAGE, session.key, 1, start, end, stages)
    private val other = own.copy(id = "other", origin = "example.other", clientId = null)

    fun cases(): Map<String, () -> Unit> {
        val validation = validationCases() + authorizationCases()
        return validation + reconciliationCases() + cleanupCases()
    }

    private fun validationCases(): Map<String, () -> Unit> =
        linkedMapOf(
            "complete" to {
                check(validSession(session, end))
            },
            "future" to {
                check(!validSession(session, start))
            },
            "empty" to {
                check(validSession(session.copy(stages = emptyList()), end))
            },
            "gap" to {
                check(validSession(session.copy(stages = stages.drop(1)), end))
            },
            "overlap" to {
                val overlapping = listOf(stages[0], stages[1].copy(start = start))
                check(!validSession(session.copy(stages = overlapping), end))
            },
            "zero" to {
                check(!validSession(session.copy(end = start), end))
            },
            "negative stage" to {
                val invalid = listOf(stages[0].copy(end = start), stages[1])
                check(!validSession(session.copy(stages = invalid), end))
            },
            "wrong final bound" to {
                check(validSession(session.copy(end = end.plusSeconds(1)), end.plusSeconds(1)))
            },
            "long session" to {
                check(!validSession(session.copy(end = end.plusSeconds(86400)), end.plusSeconds(86400)))
            },
            "asleep" to {
                check(session.asleepMinutes() == 50L)
            },
            "stage totals" to {
                check(session.minutes(StageType.AWAKE) == 10L && session.minutes(StageType.REM) == 0L)
            },
        )

    private fun authorizationCases(): Map<String, () -> Unit> =
        linkedMapOf(
            "scope only" to {
                check(scopeAccepted(listOf(SLEEP_SCOPE), "opaque"))
            },
            "excess scope" to {
                check(!scopeAccepted(listOf(SLEEP_SCOPE, "extra"), "opaque"))
            },
            "missing scope" to {
                check(!scopeAccepted(emptyList(), "opaque"))
            },
            "blank token" to {
                check(!scopeAccepted(listOf(SLEEP_SCOPE), ""))
            },
            "null token" to {
                check(!scopeAccepted(listOf(SLEEP_SCOPE), null))
            },
            "whitespace token" to {
                check(!scopeAccepted(listOf(SLEEP_SCOPE), "a b"))
            },
            "identity stable" to {
                check(sessionKey("a", start, end) == sessionKey("a", start.plusSeconds(1), end))
            },
            "identity distinct" to {
                check(sessionKey("a", start, end) != sessionKey("b", start, end))
            },
            "identity fallback" to {
                check(sessionKey("", start, end) != sessionKey("", start, end.plusSeconds(1)))
            },
        )

    private fun reconciliationCases(): Map<String, () -> Unit> =
        linkedMapOf(
            "insert" to {
                check(decide(session, emptyList()) == Decision(Action.INSERT))
            },
            "repeat" to {
                check(decide(session, listOf(own)).action == Action.ALREADY_IMPORTED)
            },
            "external skip" to {
                check(decide(session, listOf(other)).action == Action.EXTERNAL_MATCH)
            },
            "external mismatch" to {
                check(decide(session, listOf(other.copy(stages = null))).action == Action.CONFLICT)
            },
            "overlap conflict" to {
                val shifted = other.copy(start = start.minusSeconds(1))
                check(decide(session, listOf(shifted)).action == Action.CONFLICT)
            },
            "touching not overlap" to {
                val touching = other.copy(start = end, end = end.plusSeconds(10))
                check(decide(session, listOf(touching)).action == Action.INSERT)
            },
            "own update" to {
                val previous = own.copy(stages = emptyList())
                check(decide(session, listOf(previous)) == Decision(Action.UPDATE, "own", 2))
            },
            "no destructive stage downgrade" to {
                check(decide(session.copy(stages = emptyList()), listOf(own)).action == Action.CONFLICT)
            },
            "own shifted" to {
                val shifted = own.copy(start = start.minusSeconds(1))
                check(decide(session, listOf(shifted)).action == Action.CONFLICT)
            },
            "own duplicate" to {
                check(decide(session, listOf(own, own.copy(id = "another"))).action == Action.CONFLICT)
            },
        )

    private fun cleanupCases(): Map<String, () -> Unit> =
        linkedMapOf(
            "own overflow" to {
                check(
                    decide(session, listOf(own.copy(version = Long.MAX_VALUE, stages = emptyList()))).action ==
                        Action.CONFLICT,
                )
            },
            "own negative" to {
                val invalidVersion = own.copy(version = -1, stages = emptyList())
                check(decide(session, listOf(invalidVersion)).action == Action.CONFLICT)
            },
            "exact cleanup" to {
                check(decide(session, listOf(own, other)) == Decision(Action.REMOVE_OWN_DUPLICATE, "own", 1))
            },
            "cleanup mismatch" to {
                check(decide(session, listOf(own.copy(stages = null), other)).action == Action.CONFLICT)
            },
            "cleanup multiple foreign" to {
                check(decide(session, listOf(own, other, other.copy(id = "third"))).action == Action.CONFLICT)
            },
            "cleanup bridge origin refused" to {
                check(decide(session, listOf(own, other.copy(origin = BRIDGE_PACKAGE))).action == Action.CONFLICT)
            },
            "preview same" to {
                check(confirmedDecision(Decision(Action.INSERT), Decision(Action.INSERT)))
            },
            "preview changed" to {
                check(!confirmedDecision(Decision(Action.INSERT), Decision(Action.EXTERNAL_MATCH)))
            },
            "preview no write" to {
                check(!confirmedDecision(Decision(Action.CONFLICT), Decision(Action.CONFLICT)))
            },
        )
}
