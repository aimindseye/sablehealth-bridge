package io.github.aimindseye.sablehealthbridge

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import java.util.UUID

internal const val SYNC_PREFERENCES = "sbr_auto_sync"
internal const val DEFAULT_SYNC_MINUTES = 30
private const val MIN_SYNC_MINUTES = 15
private const val MAX_SYNC_MINUTES = 60
internal val syncIntervals = listOf(MIN_SYNC_MINUTES, DEFAULT_SYNC_MINUTES, MAX_SYNC_MINUTES)
private val settingsLock = Any()

internal fun syncPreferences(ctx: Context): SharedPreferences = ctx.getSharedPreferences(SYNC_PREFERENCES, MODE_PRIVATE)

/** Private, non-backed-up configuration and aggregate status. No tokens or raw records. */
internal class SyncSettings(
    private val prefs: SharedPreferences,
) {
    fun current(): SyncConsent? =
        synchronized(settingsLock) {
            val generation = prefs.getString("generation", null)
            val salt = prefs.getString("salt", null)
            val binding = prefs.getString("binding", null)
            val minutes = prefs.getInt("minutes", DEFAULT_SYNC_MINUTES)
            val validInterval = minutes in syncIntervals
            val validIdentity = !salt.isNullOrBlank() && !binding.isNullOrBlank()
            if (generation.isNullOrBlank() || !validIdentity || !validInterval) {
                null
            } else {
                SyncConsent(
                    generation,
                    requireNotNull(salt),
                    requireNotNull(binding),
                    minutes,
                    !prefs.getBoolean("initial_complete", false),
                )
            }
        }

    fun salt(): String = prefs.getString("salt", null) ?: UUID.randomUUID().toString()

    fun enable(
        salt: String,
        binding: String,
        minutes: Int,
    ): SyncConsent =
        synchronized(settingsLock) {
            require(salt.matches(Regex("[a-f0-9-]{36}")))
            require(binding.matches(Regex("[a-f0-9]{64}")))
            require(minutes in syncIntervals)
            val previous = prefs.getString("binding", null)
            if (previous != null && previous != binding) throw SyncHalt(SyncStatus.ACCOUNT_CHANGED)
            val editor = prefs.edit()
            editor.putString("generation", UUID.randomUUID().toString())
            editor.putString("salt", salt)
            editor.putString("binding", binding)
            editor.putInt("minutes", minutes)
            editor.putString("status", SyncStatus.READY.name)
            check(editor.commit())
            requireNotNull(current())
        }

    fun isCurrent(consent: SyncConsent): Boolean = current()?.generation == consent.generation

    fun stop() {
        synchronized(settingsLock) {
            val editor = prefs.edit()
            editor.remove("generation")
            editor.putString("status", SyncStatus.OFF.name)
            check(editor.commit())
        }
    }

    fun report(): SyncReport =
        synchronized(settingsLock) {
            val status = prefs.getString("status", null)
            SyncReport(
                SyncStatus.entries.firstOrNull { it.name == status } ?: SyncStatus.OFF,
                prefs.getInt("imported", 0),
                prefs.getInt("verified", 0),
                prefs.getInt("held", 0),
                prefs.getLong("finished", 0),
            )
        }

    fun history(): List<SyncReport> = parseSyncHistory(prefs.getString("history", "").orEmpty())

    fun record(
        consent: SyncConsent,
        report: SyncReport,
    ) {
        synchronized(settingsLock) {
            if (isCurrent(consent)) {
                val editor = prefs.edit()
                editor.putString("status", report.status.name)
                editor.putInt("imported", report.imported)
                editor.putInt("verified", report.verified)
                editor.putInt("held", report.held)
                editor.putLong("finished", report.finishedAt)
                if (report.status == SyncStatus.COMPLETE) editor.putBoolean("initial_complete", true)
                if (report.status in pauseStatuses) editor.remove("generation")
                if (report.finishedAt > 0 && report.status != SyncStatus.RUNNING) {
                    editor.putString("history", appendSyncHistory(history(), report))
                }
                check(editor.commit())
            }
        }
    }
}

private val pauseStatuses =
    setOf(
        SyncStatus.PAUSED,
        SyncStatus.AUTH_REQUIRED,
        SyncStatus.ACCOUNT_CHANGED,
        SyncStatus.PERMISSION_REQUIRED,
        SyncStatus.UNAVAILABLE,
    )
