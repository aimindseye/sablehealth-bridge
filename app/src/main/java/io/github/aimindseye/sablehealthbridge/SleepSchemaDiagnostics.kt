package io.github.aimindseye.sablehealthbridge

import org.json.JSONObject

private val sleepFields =
    setOf(
        "interval",
        "type",
        "stages",
        "shortAwakenings",
        "outOfBedSegments",
        "metadata",
        "summary",
        "createTime",
        "updateTime",
    )
private val intervalFields =
    setOf("startTime", "endTime", "startUtcOffset", "endUtcOffset", "civilStartTime", "civilEndTime")
private val metaFields = setOf("processed", "mainSleep", "nap", "manuallyEdited", "stagesStatus", "externalId")
private val summaryFields =
    setOf(
        "minutesAsleep",
        "minutesAwake",
        "minutesInSleepPeriod",
        "minutesToFallAsleep",
        "minutesAfterWakeUp",
        "stagesSummary",
    )
private val stageFields =
    setOf("startTime", "endTime", "startUtcOffset", "endUtcOffset", "type", "createTime", "updateTime")

/** Fixed field names and enum categories only. No arbitrary keys, strings, counts, IDs or times. */
internal fun schemaReport(points: List<JSONObject>): String {
    val sleeps = points.mapNotNull { it.optJSONObject("sleep") }
    val metadata = sleeps.map { it.optJSONObject("metadata") }
    val stages = objects(sleeps, "stages")
    val awake = objects(sleeps, "shortAwakenings")
    val outOfBed = objects(sleeps, "outOfBedSegments")
    return buildString {
        appendLine("Google Health v4 sleep schema (sanitized)")
        appendLine(fieldReport("Sleep fields", sleeps, sleepFields))
        appendLine(fieldReport("Interval fields", sleeps.mapNotNull { it.optJSONObject("interval") }, intervalFields))
        appendLine(fieldReport("Metadata fields", metadata.filterNotNull(), metaFields))
        appendLine(fieldReport("Summary fields", sleeps.mapNotNull { it.optJSONObject("summary") }, summaryFields))
        appendLine(fieldReport("Primary stage fields", stages, stageFields))
        appendLine(fieldReport("Awakening fields", awake, stageFields))
        appendLine(fieldReport("Out of bed fields", outOfBed, stageFields))
        appendLine(enumReport("Sleep types", sleeps, "type", sleepTypes))
        appendLine(enumReport("Processing states", metadata, "stagesStatus", processingStates))
        appendLine(enumReport("Primary types", stages, "type", sourceStageTypes))
        append(enumReport("Awakening types", awake, "type", sourceStageTypes))
    }
}

private fun objects(
    parents: List<JSONObject>,
    field: String,
): List<JSONObject> =
    parents.flatMap { parent ->
        val raw = parent.optJSONArray(field)
        if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
    }

private fun fieldReport(
    title: String,
    items: List<JSONObject>,
    fields: Set<String>,
): String {
    val values = fields.sorted().filter { field -> items.any { it.has(field) } }
    return "$title: ${values.joinToString().ifBlank { "NONE" }}"
}

private fun enumReport(
    title: String,
    items: List<JSONObject?>,
    field: String,
    known: Set<String>,
): String {
    val labels = items.map { knownLabel(it, field, known) }.toSortedSet()
    return "$title: ${labels.joinToString().ifBlank { "NONE" }}"
}
