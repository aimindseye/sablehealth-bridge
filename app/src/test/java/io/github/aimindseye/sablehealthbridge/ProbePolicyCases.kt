package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import java.util.concurrent.CancellationException

internal object ProbePolicyCases {
    private const val SALT = "feaf7402-5d0c-43ab-a472-06ae3d3b820d"
    private const val OTHER_SALT = "feaf7402-5d0c-43ab-a472-06ae3d3b820e"
    private val binding = accountBinding("synthetic-user", SALT)

    fun identityValidation() {
        check(binding.matches(Regex("[a-f0-9]{64}")))
        check(binding == accountBinding("synthetic-user", SALT))
        check(binding != accountBinding("different-user", SALT))
        check(binding != accountBinding("synthetic-user", OTHER_SALT))
        check(runCatching { accountBinding("", SALT) }.isFailure)
        check(runCatching { accountBinding("invalid/id", SALT) }.isFailure)
        check(runCatching { accountBinding("synthetic-user", "bad") }.isFailure)
    }

    suspend fun passesWithoutWriteCapability() {
        val ports = FakePorts()
        check(runProbe(binding, SALT, ports) == ProbeOutcome.PASS_READ_ONLY)
        check(ports.permissionChecks == 2)
        check(ports.inspections == 1)
        check(ports.authCalls == 1)
        check(ports.identityCalls == 1)
        check(ports.visibilityChecks == 3)
    }

    suspend fun foregroundStops() {
        for (visibleAt in 1..3) {
            val ports = FakePorts(visibleAt = visibleAt)
            check(runProbe(binding, SALT, ports) == ProbeOutcome.NOT_BACKGROUND)
            check(ports.inspections == if (visibleAt == 3) 1 else 0)
            if (visibleAt == 1) check(ports.authCalls == 0)
        }
    }

    suspend fun permissionStops() {
        for (reason in listOf(ProbeOutcome.FEATURE_UNAVAILABLE, ProbeOutcome.PERMISSION_REQUIRED)) {
            for (at in 1..2) {
                val ports = FakePorts(permissionFailure = reason, failPermissionAt = at)
                check(runProbe(binding, SALT, ports) == reason)
                check(ports.inspections == 0)
                if (at == 1) check(ports.authCalls == 0)
            }
        }
    }

    suspend fun authorizationStops() {
        val rejected =
            listOf(
                ProbeAuthorization(false, emptyList(), "synthetic"),
                ProbeAuthorization(false, listOf(SLEEP_SCOPE, "extra"), "synthetic"),
                ProbeAuthorization(false, listOf(SLEEP_SCOPE), null),
                ProbeAuthorization(false, listOf(SLEEP_SCOPE), " "),
                ProbeAuthorization(false, listOf(SLEEP_SCOPE), "two words"),
            )
        rejected.forEach { grant ->
            val ports = FakePorts(grant = grant)
            check(runProbe(binding, SALT, ports) == ProbeOutcome.SCOPE_REJECTED)
            check(ports.identityCalls == 0 && ports.inspections == 0)
        }
        val ports = FakePorts(grant = ProbeAuthorization(true, listOf(SLEEP_SCOPE), "synthetic"))
        check(runProbe(binding, SALT, ports) == ProbeOutcome.AUTH_REQUIRED)
        check(ports.identityCalls == 0 && ports.inspections == 0)
    }

    suspend fun accountMismatchStops() {
        val ports = FakePorts(account = "different-user")
        check(runProbe(binding, SALT, ports) == ProbeOutcome.ACCOUNT_CHANGED)
        check(ports.inspections == 0)
    }

    suspend fun failureAndCancellation() {
        val failure = FakePorts(inspectionFailure = IllegalStateException("private detail"))
        check(runProbe(binding, SALT, failure) == ProbeOutcome.READ_NOT_CONFIRMED)
        val cancelled = FakePorts(inspectionFailure = CancellationException())
        check(runCatching { runProbe(binding, SALT, cancelled) }.exceptionOrNull() is CancellationException)
    }

    private class FakePorts(
        private val visibleAt: Int = 0,
        private val permissionFailure: ProbeOutcome? = null,
        private val failPermissionAt: Int = 0,
        private val grant: ProbeAuthorization = ProbeAuthorization(false, listOf(SLEEP_SCOPE), "synthetic"),
        private val account: String = "synthetic-user",
        private val inspectionFailure: Exception? = null,
    ) : ProbePorts {
        var visibilityChecks = 0
        var permissionChecks = 0
        var authCalls = 0
        var identityCalls = 0
        var inspections = 0

        override suspend fun visible(): Boolean = ++visibilityChecks == visibleAt

        override suspend fun checkPermissions() {
            permissionChecks++
            if (permissionChecks == failPermissionAt) throw ProbeStop(requireNotNull(permissionFailure))
        }

        override suspend fun authorize(): ProbeAuthorization {
            authCalls++
            return grant
        }

        override suspend fun identity(
            access: String,
            salt: String,
        ): String {
            check(access == "synthetic")
            identityCalls++
            return accountBinding(account, salt)
        }

        override suspend fun inspect(access: String) {
            check(access == "synthetic")
            inspections++
            inspectionFailure?.let { throw it }
        }
    }
}
