package io.github.aimindseye.sablehealthbridge

import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProbePolicyTest {
    @Test
    fun identityValidation() = ProbePolicyCases.identityValidation()

    @Test
    fun passesWithoutWriteCapability() = runBlocking { ProbePolicyCases.passesWithoutWriteCapability() }

    @Test
    fun foregroundStops() = runBlocking { ProbePolicyCases.foregroundStops() }

    @Test
    fun permissionStops() = runBlocking { ProbePolicyCases.permissionStops() }

    @Test
    fun authorizationStops() = runBlocking { ProbePolicyCases.authorizationStops() }

    @Test
    fun accountMismatchStops() = runBlocking { ProbePolicyCases.accountMismatchStops() }

    @Test
    fun failureAndCancellation() = runBlocking { ProbePolicyCases.failureAndCancellation() }
}
