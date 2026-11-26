package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.scopeAccepted
import kotlinx.coroutines.CoroutineScope

internal interface BridgeServices {
    fun authorize()

    fun healthAvailable(): Boolean

    fun requestPermissions()

    suspend fun batch(access: String): SleepBatch

    suspend fun inspect(session: SleepSession): Decision

    suspend fun apply(
        session: SleepSession,
        decision: Decision,
    ): String
}

internal class BridgeController(
    scope: CoroutineScope,
    private val services: BridgeServices,
) {
    private val operations = BridgeOperations(scope)
    var screen: ScreenState
        get() = operations.screen
        private set(value) {
            operations.screen = value
        }
    private var token: String? = null
    private var sessions = emptyList<SleepSession>()

    fun authorize() {
        if (screen.busy) return
        forget()
        screen = screen.copy(busy = true, message = "Authorizing sleep read access…")
        runCatching { services.authorize() }.onFailure { authorizationFailed() }
    }

    fun acceptAuthorization(
        scopes: Collection<String>,
        access: String?,
    ) {
        if (!scopeAccepted(scopes, access)) {
            token = null
            clearBatch()
            screen = screen.copy(connected = false)
            operations.fail("Sleep-only scope was not confirmed")
        } else {
            token = access
            screen = screen.copy(connected = true, busy = false, message = "Google sleep access authorized")
        }
    }

    fun authorizationFailed(message: String = "Google authorization failed; verify Android OAuth registration") {
        token = null
        clearBatch()
        screen = screen.copy(connected = false)
        operations.fail(message)
    }

    fun requestHealth() {
        if (screen.busy) return
        screen = screen.copy(preview = null, permissions = false, busy = true)
        runCatching {
            if (services.healthAvailable()) {
                services.requestPermissions()
            } else {
                operations.fail("Health Connect unavailable or needs update")
            }
        }.onFailure { operations.fail("Unable to open Health Connect permissions") }
    }

    fun permissionsResult(granted: Boolean) {
        screen =
            screen.copy(
                permissions = granted,
                busy = false,
                preview = null,
                message = if (granted) "Sleep access granted" else "Sleep access not granted",
            )
    }

    fun preview() {
        if (screen.busy) return
        val access = token ?: return operations.fail("Connect Google Health first")
        clearBatch()
        operations.execute("Reading recent sleep sessions…") {
            val batch = services.batch(access)
            sessions = batch.sessions
            screen =
                screen.copy(
                    sessionCount = sessions.size,
                    heldCount = batch.heldCount,
                    diagnostics = batch.diagnostics,
                )
            if (sessions.isEmpty()) {
                screen = screen.copy(message = "No eligible completed sleep; review diagnostics")
            } else {
                operations.inspectAt(sessions[0], 0, services)
            }
        }
    }

    fun selectRelative(step: Int) {
        if (screen.busy || sessions.size < 2) return
        val index = (screen.sessionIndex + step).mod(sessions.size)
        operations.execute("Checking selected sleep…") { operations.inspectAt(sessions[index], index, services) }
    }

    private fun clearBatch() {
        sessions = emptyList()
        screen = screen.copy(sessionCount = 0, sessionIndex = 0, heldCount = 0, diagnostics = "")
    }

    fun applyPreview() {
        val candidate = screen.preview?.takeIf { it.decision.canWrite }
        val access = token
        if (screen.busy || candidate == null || access == null) return
        operations.execute("Rechecking both sources…") {
            val batch = services.batch(access)
            val fresh = batch.sessions.singleOrNull { it.key == candidate.session.key }
            if (fresh != candidate.session) throw Hold("Google sleep changed; preview again")
            val outcome = services.apply(requireNotNull(fresh), candidate.decision)
            screen = screen.copy(message = outcome)
        }
    }

    fun forget(force: Boolean = false) {
        if (screen.busy && !force) return
        token = null
        clearBatch()
        screen = screen.copy(connected = false, preview = null, busy = false, message = "Local session cleared")
    }
}
