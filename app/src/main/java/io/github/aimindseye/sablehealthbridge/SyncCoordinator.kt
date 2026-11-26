package io.github.aimindseye.sablehealthbridge

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SyncUiState(
    val enabled: Boolean = false,
    val minutes: Int = DEFAULT_SYNC_MINUTES,
    val report: SyncReport = SyncReport(),
    val busy: Boolean = false,
    val notice: SyncStatus? = null,
)

internal class SyncCoordinator(
    private val activity: ComponentActivity,
    private val scheduler: SyncScheduler = AndroidSyncScheduler(activity),
) {
    private val prefs by lazy { syncPreferences(activity) }
    private val settings by lazy { SyncSettings(prefs) }
    private val authorization = SyncAuthorization(activity, ::accept, ::fail)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    var state by mutableStateOf(SyncUiState())
        private set

    fun observe() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    fun unobserve() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun chooseInterval(minutes: Int) {
        if (!state.enabled && !state.busy && minutes in syncIntervals) {
            state = state.copy(minutes = minutes)
        }
    }

    fun enable() {
        if (!state.enabled && !state.busy) {
            state = state.copy(busy = true, notice = null)
            authorization.request()
        }
    }

    fun disable() {
        if (state.enabled) {
            runCatching { settings.stop() }
                .onSuccess {
                    refresh()
                    activity.lifecycleScope.launch {
                        try {
                            scheduler.cancel()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            fail(SyncStatus.FAILED)
                        }
                    }
                }.onFailure { fail(SyncStatus.FAILED) }
        }
    }

    fun syncNow(restore: Boolean = false) {
        val consent = settings.current()
        if (consent != null && !state.busy) {
            launchSchedule(consent, restore, replace = false)
        }
    }

    private fun accept(grant: ProbeAuthorization) {
        if (!state.busy || state.enabled) return
        activity.lifecycleScope.launch {
            try {
                val consent =
                    withContext(Dispatchers.IO) {
                        val access = silentAccess(grant)
                        val salt = settings.salt()
                        val binding = readAccountBinding(access, salt)
                        settings.enable(salt, binding, state.minutes)
                    }
                state = state.copy(busy = false)
                refresh()
                launchSchedule(consent, restore = true, replace = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stop: SyncHalt) {
                fail(stop.status)
            } catch (_: Exception) {
                fail(SyncStatus.AUTH_REQUIRED)
            }
        }
    }

    private fun launchSchedule(
        consent: SyncConsent,
        restore: Boolean,
        replace: Boolean,
    ) {
        activity.lifecycleScope.launch {
            try {
                if (restore && settings.isCurrent(consent)) scheduler.schedule(consent, replace)
                if (settings.isCurrent(consent)) scheduler.now(consent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                settings.record(consent, SyncReport(SyncStatus.PAUSED))
                fail(SyncStatus.FAILED)
            }
        }
    }

    private fun refresh() {
        val consent = settings.current()
        state =
            state.copy(
                enabled = consent != null,
                minutes = consent?.minutes ?: state.minutes,
                report = settings.report(),
            )
    }

    private fun fail(status: SyncStatus) {
        state = state.copy(busy = false, notice = status)
        refresh()
    }
}
