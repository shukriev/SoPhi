package dev.sophi.cli.goal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GoalArgsTest : FunSpec({
    test("no args fails with usage") {
        GoalArgs.parse(null).isFailure shouldBe true
        GoalArgs.parse("").isFailure shouldBe true
        GoalArgs.parse("   ").isFailure shouldBe true
    }

    test("plain task with no --check") {
        val args = GoalArgs.parse("make the suite green").getOrThrow()
        args.task shouldBe "make the suite green"
        args.check shouldBe null
    }

    test("--check with an unquoted single-token command") {
        val args = GoalArgs.parse("--check ./run-tests.sh make the suite green").getOrThrow()
        args.check shouldBe "./run-tests.sh"
        args.task shouldBe "make the suite green"
    }

    test("--check with a quoted multi-word command") {
        val args = GoalArgs.parse("--check \"mvn -q test\" fix the flaky retry test").getOrThrow()
        args.check shouldBe "mvn -q test"
        args.task shouldBe "fix the flaky retry test"
    }

    test("--check with no task after it fails with usage") {
        GoalArgs.parse("--check ./x.sh").isFailure shouldBe true
    }

    test("--check with an unterminated quote fails with usage") {
        GoalArgs.parse("--check \"mvn test fix it").isFailure shouldBe true
    }

    test("a task that happens to start with the literal word --checkmate is not treated as the --check flag") {
        val args = GoalArgs.parse("--checkmate the king in three moves").getOrThrow()
        args.task shouldBe "--checkmate the king in three moves"
        args.check shouldBe null
    }
})
