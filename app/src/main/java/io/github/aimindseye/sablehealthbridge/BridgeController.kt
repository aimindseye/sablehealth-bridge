package io.github.aimindseye.sablehealthbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.scopeAccepted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal interface BridgeServices {
    fun authorize()

    fun healthAvailable(): Boolean

    fun requestPermissions()

    suspend fun latest(access: String): SleepSession

    suspend fun inspect(session: SleepSession): Decision

    suspend fun apply(
        session: SleepSession,
        decision: Decision,
    ): String
}

internal class BridgeController(
    private val scope: CoroutineScope,
    private val services: BridgeServices,
) {
    var screen by mutableStateOf(ScreenState())
        private set
    private var token: String? = null

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
            screen = screen.copy(connected = false)
            fail("Sleep-only scope was not confirmed")
        } else {
            token = access
            screen = screen.copy(connected = true, busy = false, message = "Google sleep access authorized")
        }
    }

    fun authorizationFailed(message: String = "Google authorization failed; verify Android OAuth registration") {
        token = null
        screen = screen.copy(connected = false)
        fail(message)
    }

    fun requestHealth() {
        if (screen.busy) return
        screen = screen.copy(preview = null, permissions = false, busy = true)
        runCatching {
            if (services.healthAvailable()) {
                services.requestPermissions()
            } else {
                fail("Health Connect unavailable or needs update")
            }
        }.onFailure { fail("Unable to open Health Connect permissions") }
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
        val access = token ?: return fail("Connect Google Health first")
        execute("Reading latest main sleep…") {
            val session = services.latest(access)
            val decision = services.inspect(session)
            val preview = Preview(session, decision)
            screen = screen.copy(preview = preview, permissions = true, message = "Review before applying")
        }
    }

    fun applyPreview() {
        val candidate = screen.preview?.takeIf { it.decision.canWrite }
        val access = token
        if (screen.busy || candidate == null || access == null) return
        execute("Rechecking both sources…") {
            val fresh = services.latest(access)
            if (fresh != candidate.session) throw Hold("Google sleep changed; preview again")
            val outcome = services.apply(fresh, candidate.decision)
            screen = screen.copy(message = outcome)
        }
    }

    private fun execute(
        label: String,
        block: suspend () -> Unit,
    ) {
        screen = screen.copy(busy = true, preview = null, message = label)
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (hold: Hold) {
                fail(hold.label)
            } catch (_: Exception) {
                fail("Operation not confirmed; review permissions and records before retrying")
            } finally {
                screen = screen.copy(busy = false)
            }
        }
    }

    private fun fail(message: String) {
        screen = screen.copy(busy = false, preview = null, message = message)
    }

    fun forget(force: Boolean = false) {
        if (screen.busy && !force) return
        token = null
        screen = screen.copy(connected = false, preview = null, busy = false, message = "Local session cleared")
    }
}
