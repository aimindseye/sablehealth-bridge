package io.github.aimindseye.sablehealthbridge

private const val MAX_SYNC_HISTORY = 5
private const val HISTORY_FIELDS = 5

/** Aggregate run metadata only, without sleep times, durations, account names or record IDs. */
internal fun appendSyncHistory(
    previous: List<SyncReport>,
    report: SyncReport,
): String =
    (listOf(report) + previous)
        .take(MAX_SYNC_HISTORY)
        .joinToString("\n") {
            listOf(it.status.name, it.finishedAt, it.imported, it.verified, it.held).joinToString("|")
        }

internal fun parseSyncHistory(raw: String): List<SyncReport> =
    raw
        .lineSequence()
        .take(MAX_SYNC_HISTORY)
        .mapNotNull(::parseSyncReport)
        .toList()

private fun parseSyncReport(line: String): SyncReport? =
    runCatching {
        val fields = line.split('|')
        require(fields.size == HISTORY_FIELDS)
        val status = SyncStatus.valueOf(fields[0])
        val finished = fields[1].toLong()
        val counts = fields.drop(2).map(String::toInt)
        require(finished > 0 && counts.all { it >= 0 })
        SyncReport(status, counts[0], counts[1], counts[2], finished)
    }.getOrNull()
