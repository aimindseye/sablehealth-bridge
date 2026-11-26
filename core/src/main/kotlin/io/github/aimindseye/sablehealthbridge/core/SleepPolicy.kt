package io.github.aimindseye.sablehealthbridge.core

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

const val BRIDGE_PACKAGE = "io.github.aimindseye.sablehealthbridge"
const val SLEEP_SCOPE = "https://www.googleapis.com/auth/googlehealth.sleep.readonly"

private const val BYTE_MASK = 255
private const val MAX_SESSION_HOURS = 24L
internal const val MAX_STAGES = 20000

enum class StageType { AWAKE, LIGHT, DEEP, REM, OUT_OF_BED }

data class Stage(
    val start: Instant,
    val end: Instant,
    val type: StageType,
)

data class OverlayInfo(
    val shortAwakenings: Int,
    val outOfBedSegments: Int,
    val primaryAsleepMinutes: Long,
    val reportedAsleepMinutes: Long?,
    val reportedAwakeMinutes: Long?,
)

data class SleepSession(
    val key: String,
    val start: Instant,
    val end: Instant,
    val startOffset: ZoneOffset?,
    val endOffset: ZoneOffset?,
    val stages: List<Stage>,
    val overlayInfo: OverlayInfo? = null,
) {
    fun minutes(type: StageType): Long = totalMinutes(stages.filter { it.type == type })

    fun asleepMinutes(): Long = totalMinutes(
        stages.filter { it.type != StageType.AWAKE && it.type != StageType.OUT_OF_BED },
    )
}

private fun totalMinutes(stages: List<Stage>): Long =
    stages
        .fold(Duration.ZERO) { total, stage -> total.plus(Duration.between(stage.start, stage.end)) }
        .toMinutes()

fun sessionKey(
    sourceName: String,
    start: Instant,
    end: Instant,
): String {
    val identity = sourceName.ifBlank { "$start/$end" }
    val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return "sb-r1-" + bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}

fun validSession(
    session: SleepSession,
    now: Instant,
): Boolean =
    session.start < session.end &&
        session.end <= now &&
        Duration.between(session.start, session.end) <= Duration.ofHours(MAX_SESSION_HOURS) &&
        session.stages.isNotEmpty() &&
        session.stages.size <= MAX_STAGES &&
        session.stages.first().start == session.start &&
        session.stages.last().end == session.end &&
        session.stages.all { it.start < it.end } &&
        session.stages.zipWithNext().all { (a, b) -> a.end == b.start }

fun scopeAccepted(
    scopes: Collection<String>,
    token: String?,
): Boolean {
    val correctScope = scopes.toSet() == setOf(SLEEP_SCOPE)
    return correctScope && !token.isNullOrBlank() && token.none(Char::isWhitespace)
}

data class ExistingSleep(
    val id: String,
    val origin: String,
    val clientId: String?,
    val version: Long,
    val start: Instant,
    val end: Instant,
    val stages: List<Stage>?,
) {
    fun matches(session: SleepSession): Boolean {
        val sameBounds = start == session.start && end == session.end
        return sameBounds && stages == session.stages
    }

    fun isOwned(session: SleepSession): Boolean {
        val bridgeOrigin = origin == BRIDGE_PACKAGE
        return bridgeOrigin && clientId == session.key
    }

    fun overlaps(session: SleepSession): Boolean = start < session.end && end > session.start
}

enum class Action {
    INSERT,
    UPDATE,
    ALREADY_IMPORTED,
    EXTERNAL_MATCH,
    REMOVE_OWN_DUPLICATE,
    CONFLICT,
}

data class Decision(
    val action: Action,
    val ownId: String? = null,
    val version: Long = 0,
) {
    val canWrite: Boolean
        get() =
            when (action) {
                Action.INSERT, Action.UPDATE, Action.REMOVE_OWN_DUPLICATE -> true
                else -> false
            }
}

/** Conservative: never overwrite other writers; never remove an unmatched copy. */
fun decide(
    session: SleepSession,
    records: List<ExistingSleep>,
): Decision {
    val own = records.filter { it.isOwned(session) }
    val foreign = records.filter { !it.isOwned(session) && it.overlaps(session) }
    val previous = own.singleOrNull()
    val missingOwned = if (foreign.isEmpty()) Action.INSERT else Action.EXTERNAL_MATCH
    return when {
        own.size > 1 || foreign.any { !it.matches(session) } -> Decision(Action.CONFLICT)
        previous == null -> Decision(missingOwned)
        else -> decideOwned(session, previous, foreign)
    }
}

private fun decideOwned(
    session: SleepSession,
    previous: ExistingSleep,
    foreign: List<ExistingSleep>,
): Decision =
    when {
        previous.start != session.start || previous.end != session.end -> Decision(Action.CONFLICT)
        foreign.isNotEmpty() -> decideDuplicate(session, previous, foreign)
        previous.matches(session) -> Decision(Action.ALREADY_IMPORTED)
        previous.version < 0 || previous.version == Long.MAX_VALUE -> Decision(Action.CONFLICT)
        else -> Decision(Action.UPDATE, previous.id, previous.version + 1)
    }

private fun decideDuplicate(
    session: SleepSession,
    previous: ExistingSleep,
    foreign: List<ExistingSleep>,
): Decision {
    val matchingExternal = foreign.singleOrNull()?.origin?.let { it != BRIDGE_PACKAGE } == true
    return if (previous.matches(session) && matchingExternal) {
        Decision(Action.REMOVE_OWN_DUPLICATE, previous.id, previous.version)
    } else {
        Decision(Action.CONFLICT)
    }
}

/** Refuse a stale UI action even if the new action would also write. */
fun confirmedDecision(
    preview: Decision,
    fresh: Decision,
): Boolean {
    val unchanged = preview == fresh
    return preview.canWrite && unchanged
}
