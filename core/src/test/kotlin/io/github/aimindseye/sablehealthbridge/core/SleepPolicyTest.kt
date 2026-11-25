package io.github.aimindseye.sablehealthbridge.core

import org.junit.Test

class SleepPolicyTest {
    @Test
    fun policyCases() {
        PolicyCases.cases().forEach { (name, test) ->
            try {
                test()
            } catch (failure: AssertionError) {
                throw AssertionError(name, failure)
            }
        }
    }
}
