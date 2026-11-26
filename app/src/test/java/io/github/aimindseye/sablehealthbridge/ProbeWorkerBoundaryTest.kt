package io.github.aimindseye.sablehealthbridge

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProbeWorkerBoundaryTest {
    private val salt = "feaf7402-5d0c-43ab-a472-06ae3d3b820d"
    private val binding = accountBinding("synthetic-user", salt)
    private val valid = workDataOf(PROBE_SALT to salt, PROBE_BINDING to binding)

    @Test
    fun malformedInputCannotCreatePorts() =
        runBlocking {
            val invalid =
                listOf(
                    Data.EMPTY,
                    workDataOf(PROBE_BINDING to binding),
                    workDataOf(PROBE_SALT to salt, PROBE_BINDING to "invalid"),
                    workDataOf(PROBE_SALT to "invalid", PROBE_BINDING to binding),
                )
            invalid.forEach { data ->
                val result = executeProbeWork(data) { error("unexpected SDK access") }
                check(result == failed(ProbeOutcome.READ_NOT_CONFIRMED))
            }
        }

    @Test
    fun mapsRealPolicyToWorkManagerResults() =
        runBlocking {
            val success = WorkerProbePorts()
            val expected = ListenableWorker.Result.success(workDataOf(PROBE_RESULT to "PASS_READ_ONLY"))
            check(executeProbeWork(valid) { success } == expected)
            check(success.inspected == 1)
            for (reason in ProbeOutcome.entries.filter { it != ProbeOutcome.PASS_READ_ONLY }) {
                val ports = WorkerProbePorts(reason)
                check(executeProbeWork(valid) { ports } == failed(reason))
                check(ports.inspected == 0)
            }
            val cancelled = WorkerProbePorts(cancel = true)
            check(runCatching { executeProbeWork(valid) { cancelled } }.exceptionOrNull() is CancellationException)
            check(cancelled.inspected == 0)
        }

    @Test
    fun parsesOnlyHealthUserIdAndNeverStoresRawIdentity() {
        var called = false
        val result =
            readAccountBinding("fixture", salt) { access ->
                check(access == "fixture")
                called = true
                JSONObject().put("healthUserId", "synthetic-user").put("other", "ignored")
            }
        check(called && result == binding && !result.contains("synthetic-user"))
        for (json in listOf(JSONObject(), JSONObject().put("healthUserId", "invalid/id"))) {
            check(runCatching { readAccountBinding("fixture", salt) { json } }.isFailure)
        }
    }

    private fun failed(reason: ProbeOutcome): ListenableWorker.Result =
        ListenableWorker.Result.failure(workDataOf(PROBE_RESULT to reason.name))
}

private class WorkerProbePorts(
    private val outcome: ProbeOutcome? = null,
    private val cancel: Boolean = false,
) : ProbePorts {
    var inspected = 0

    override suspend fun visible(): Boolean = outcome == ProbeOutcome.NOT_BACKGROUND

    override suspend fun checkPermissions() {
        if (cancel) throw CancellationException()
        if (outcome == ProbeOutcome.FEATURE_UNAVAILABLE || outcome == ProbeOutcome.PERMISSION_REQUIRED) {
            throw ProbeStop(requireNotNull(outcome))
        }
    }

    override suspend fun authorize(): ProbeAuthorization {
        if (outcome == ProbeOutcome.READ_NOT_CONFIRMED) error("private detail")
        return ProbeAuthorization(
            outcome == ProbeOutcome.AUTH_REQUIRED,
            if (outcome == ProbeOutcome.SCOPE_REJECTED) emptyList() else listOf(SLEEP_SCOPE),
            "fixture",
        )
    }

    override suspend fun identity(
        access: String,
        salt: String,
    ): String = accountBinding(if (outcome == ProbeOutcome.ACCOUNT_CHANGED) "other-user" else "synthetic-user", salt)

    override suspend fun inspect(access: String) {
        inspected++
    }
}
