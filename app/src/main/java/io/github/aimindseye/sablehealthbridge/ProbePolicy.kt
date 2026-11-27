package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.scopeAccepted
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal enum class ProbeOutcome {
    PASS_READ_ONLY,
    NOT_BACKGROUND,
    FEATURE_UNAVAILABLE,
    PERMISSION_REQUIRED,
    AUTH_REQUIRED,
    SCOPE_REJECTED,
    ACCOUNT_CHANGED,
    READ_NOT_CONFIRMED,
}

internal class ProbeStop(
    val outcome: ProbeOutcome,
) : Exception()

// Not a data class: avoid automatically generated token-bearing toString().
internal class ProbeAuthorization(
    val needsResolution: Boolean,
    val scopes: Collection<String>,
    val access: String?,
)

internal interface ProbePorts {
    suspend fun visible(): Boolean

    suspend fun checkPermissions()

    suspend fun authorize(): ProbeAuthorization

    suspend fun identity(
        access: String,
        salt: String,
    ): String

    suspend fun inspect(access: String)
}

internal fun accountBinding(
    identifier: String,
    salt: String,
): String {
    require(identifier.matches(Regex("[A-Za-z0-9-]{1,63}")))
    require(salt.matches(Regex("[a-f0-9-]{36}")))
    return MessageDigest
        .getInstance("SHA-256")
        .digest("$salt:$identifier".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun silentAccess(grant: ProbeAuthorization): String {
    if (grant.needsResolution) throw ProbeStop(ProbeOutcome.AUTH_REQUIRED)
    if (!scopeAccepted(grant.scopes, grant.access)) throw ProbeStop(ProbeOutcome.SCOPE_REJECTED)
    return requireNotNull(grant.access)
}

private suspend fun requireBackground(ports: ProbePorts) {
    if (ports.visible()) throw ProbeStop(ProbeOutcome.NOT_BACKGROUND)
}

internal suspend fun runProbe(
    expected: String,
    salt: String,
    ports: ProbePorts,
): ProbeOutcome =
    try {
        requireBackground(ports)
        ports.checkPermissions()
        val access = silentAccess(ports.authorize())
        if (ports.identity(access, salt) != expected) throw ProbeStop(ProbeOutcome.ACCOUNT_CHANGED)
        requireBackground(ports)
        ports.checkPermissions()
        ports.inspect(access)
        requireBackground(ports)
        ProbeOutcome.PASS_READ_ONLY
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (stop: ProbeStop) {
        stop.outcome
    } catch (_: Exception) {
        ProbeOutcome.READ_NOT_CONFIRMED
    }
