package io.github.aimindseye.sablehealthbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Holds observable UI state and owns the lifecycle of foreground operations. */
internal class BridgeOperations(
    private val scope: CoroutineScope,
) {
    var screen by mutableStateOf(ScreenState())

    suspend fun inspectAt(
        session: SleepSession,
        index: Int,
        services: BridgeServices,
    ) {
        val decision = services.inspect(session)
        screen =
            screen.copy(
                preview = Preview(session, decision),
                permissions = true,
                sessionIndex = index,
                message = "Review before applying",
            )
    }

    fun execute(
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

    fun fail(message: String) {
        screen = screen.copy(busy = false, preview = null, message = message)
    }
}
