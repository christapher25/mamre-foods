package com.mamre.billing.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Runs a test method [times] times, each with its own @Before and @After. For proving that a test is not flaky. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Repeat(val times: Int)

/** Put `@get:Rule val repeat = RepeatRule()` in the test class; every failure is reported with its repetition number. */
class RepeatRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        val times = description.getAnnotation(Repeat::class.java)?.times ?: return base
        return object : Statement() {
            override fun evaluate() {
                for (i in 1..times) {
                    try {
                        base.evaluate()
                    } catch (e: Throwable) {
                        throw AssertionError("repetition $i of $times failed: ${e::class.simpleName}: ${e.message}", e)
                    }
                }
            }
        }
    }
}
