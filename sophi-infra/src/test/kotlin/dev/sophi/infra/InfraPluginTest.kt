package dev.sophi.infra

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class InfraPluginTest : FunSpec({
    test("PermissionGatePlugin allows tool in allowlist") {
        val plugin = PermissionGatePlugin(setOf("read", "write"))
        val hook = plugin.hooks().single()
        hook.invoke(HookContext("s1", toolName = "read"))
    }

    test("PermissionGatePlugin blocks tool not in allowlist with SecurityException") {
        val plugin = PermissionGatePlugin(setOf("read", "write"))
        val hook = plugin.hooks().single()
        shouldThrow<SecurityException> {
            hook.invoke(HookContext("s1", toolName = "delete"))
        }
    }

    test("MetricsPlugin increments started counter on BEFORE_TURN") {
        val meterRegistry = SimpleMeterRegistry()
        val plugin = MetricsPlugin(meterRegistry)
        val hook = plugin.hooks().first { it.point == HookPoint.BEFORE_TURN }
        hook.invoke(HookContext("s1"))
        meterRegistry.counter("sophi.turns.started", "session", "s1").count() shouldBe 1.0
    }

    test("MetricsPlugin increments errors counter on ON_ERROR") {
        val meterRegistry = SimpleMeterRegistry()
        val plugin = MetricsPlugin(meterRegistry)
        val hook = plugin.hooks().first { it.point == HookPoint.ON_ERROR }
        hook.invoke(HookContext("s1", error = RuntimeException("oops")))
        meterRegistry.counter("sophi.turns.errors", "session", "s1").count() shouldBe 1.0
    }
})
