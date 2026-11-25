import io.github.aimindseye.sablehealthbridge.core.PolicyCases

fun main() {
    val cases = PolicyCases.cases()
    cases.forEach { (name, run) ->
        run()
        println("PASS: $name")
    }
    println("RESULT=PASS_CORE_POLICY_CASES COUNT=${cases.size}")
}
