package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.ScenarioPlanPolicy
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationIntentContractTest {
    @Test
    fun automationIntegrationIdentifiersRemainStableAcrossLauncherRebranding() {
        assertEquals(
            "com.example.dpulayerlab.AutomationActivity",
            AutomationIntentContract.COMPONENT_CLASS_NAME,
        )
        assertEquals(
            "com.example.dpulayerlab.action.START",
            AutomationIntentContract.ACTION_START,
        )
        assertEquals(
            "com.example.dpulayerlab.action.STOP",
            AutomationIntentContract.ACTION_STOP,
        )
        assertEquals(
            "com.example.dpulayerlab.action.SHOW",
            AutomationIntentContract.ACTION_SHOW,
        )
    }

    @Test
    fun launcherAndUnrelatedActionsAreIgnored() {
        assertEquals(
            AutomationIntentParseResult.Ignored,
            parse(AutomationIntentInput(action = "android.intent.action.MAIN")),
        )
        assertEquals(
            AutomationIntentParseResult.Ignored,
            parse(AutomationIntentInput(action = "example.UNRELATED")),
        )
    }

    @Test
    fun showAndStopNeedNoScenarioExtras() {
        assertEquals(
            AutomationIntentParseResult.Accepted(AutomationCommand.Show),
            parse(AutomationIntentInput(action = AutomationIntentContract.ACTION_SHOW)),
        )
        assertEquals(
            AutomationIntentParseResult.Accepted(AutomationCommand.Stop),
            parse(AutomationIntentInput(action = AutomationIntentContract.ACTION_STOP)),
        )
    }

    @Test
    fun onlyStartUnmarshalsAutomationExtras() {
        assertTrue(automationActionNeedsStartExtras(AutomationIntentContract.ACTION_START))
        assertTrue(!automationActionNeedsStartExtras(AutomationIntentContract.ACTION_STOP))
        assertTrue(!automationActionNeedsStartExtras(AutomationIntentContract.ACTION_SHOW))
        assertTrue(!automationActionNeedsStartExtras("android.intent.action.MAIN"))
        assertTrue(!automationActionNeedsStartExtras(null))
    }

    @Test
    fun singularScenarioDefaultsToOneRun() {
        val result = acceptedStart(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdPresent = true,
                scenarioId = " baseline ",
            ),
        )

        assertEquals(listOf("baseline"), result.scenarioIds)
        assertEquals(1, result.repeatCount)
    }

    @Test
    fun commaSeparatedIdsPreserveOrderAndDuplicates() {
        val result = acceptedStart(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = "transform, baseline,transform",
                repeatCountPresent = true,
                repeatCount = "2",
            ),
        )

        assertEquals(listOf("transform", "baseline", "transform"), result.scenarioIds)
        assertEquals(2, result.repeatCount)
    }

    @Test
    fun stringArrayAndListFormsAreAccepted() {
        val arrayResult = acceptedStart(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = arrayOf("baseline", "transform"),
            ),
        )
        val listResult = acceptedStart(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = arrayListOf("transform", "video"),
            ),
        )

        assertEquals(listOf("baseline", "transform"), arrayResult.scenarioIds)
        assertEquals(listOf("transform", "video"), listResult.scenarioIds)
    }

    @Test
    fun missingAmbiguousAndEmptyIdsAreRejected() {
        assertRejected(
            AutomationIntentInput(action = AutomationIntentContract.ACTION_START),
            "필요",
        )
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdPresent = true,
                scenarioId = "baseline",
                scenarioIdsPresent = true,
                scenarioIds = "transform",
            ),
            "동시에",
        )
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = "baseline,,transform",
            ),
            "비어",
        )
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = emptyList<String>(),
            ),
            "비어",
        )
    }

    @Test
    fun unknownOrNonStringIdsAreRejected() {
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdPresent = true,
                scenarioId = "custom-arbitrary-workload",
            ),
            "알 수 없는",
        )
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = listOf("baseline", 7),
            ),
            "문자열",
        )
    }

    @Test
    fun malformedAndOutOfRangeRepeatCountsAreRejected() {
        listOf(0, -1, 1.5f, "2.5", null).forEach { repeat ->
            assertRejected(
                AutomationIntentInput(
                    action = AutomationIntentContract.ACTION_START,
                    scenarioIdPresent = true,
                    scenarioId = "baseline",
                    repeatCountPresent = true,
                    repeatCount = repeat,
                ),
                "repeat_count",
            )
        }
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdPresent = true,
                scenarioId = "baseline",
                repeatCountPresent = true,
                repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT + 1,
            ),
            "범위",
        )
    }

    @Test
    fun totalRunLimitUsesOverflowSafeLongMath() {
        val atLimit = acceptedStart(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = List(4) { "baseline" },
                repeatCountPresent = true,
                repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
            ),
        )
        assertEquals(
            ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
            atLimit.scenarioIds.size * atLimit.repeatCount,
        )

        val ids = List(5) { "baseline" }.joinToString(",")
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdsPresent = true,
                scenarioIds = ids,
                repeatCountPresent = true,
                repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
            ),
            "총 실행 수",
        )
        assertRejected(
            AutomationIntentInput(
                action = AutomationIntentContract.ACTION_START,
                scenarioIdPresent = true,
                scenarioId = "baseline",
                repeatCountPresent = true,
                repeatCount = Long.MAX_VALUE,
            ),
            "repeat_count",
        )
    }

    @Test
    fun unknownAutomationActionIsAUserError() {
        assertRejected(
            AutomationIntentInput(
                action = "com.example.dpulayerlab.action.START_TYPO",
            ),
            "지원하지 않는",
        )
    }

    @Test
    fun oversizedAutomationActionIsBounded() {
        assertRejected(
            AutomationIntentInput(
                action = "com.example.dpulayerlab.action." + "X".repeat(512),
            ),
            "너무 깁니다",
        )
        assertEquals(
            AutomationIntentParseResult.Ignored,
            parse(AutomationIntentInput(action = "unrelated." + "X".repeat(512))),
        )
    }

    @Test
    fun latestStopSupersedesAnOlderStopAndEveryPendingStart() {
        val stop = AutomationIntentParseResult.Accepted(AutomationCommand.Stop)
        val start = AutomationIntentParseResult.Accepted(
            AutomationCommand.Start(listOf("baseline"), repeatCount = 1),
        )
        val queue = ArrayDeque<AutomationIntentParseResult>()

        assertEquals(
            PendingAutomationEnqueueResult.STOP_SUPERSEDED,
            enqueuePendingAutomation(queue, stop, maxPendingCommands = 8),
        )
        assertEquals(
            PendingAutomationEnqueueResult.ENQUEUED,
            enqueuePendingAutomation(queue, start, maxPendingCommands = 8),
        )
        assertEquals(
            PendingAutomationEnqueueResult.STOP_SUPERSEDED,
            enqueuePendingAutomation(queue, stop, maxPendingCommands = 8),
        )

        assertEquals(listOf(stop), queue.toList())
    }

    @Test
    fun stopSupersedesAFullPendingQueue() {
        val queue = ArrayDeque<AutomationIntentParseResult>()
        repeat(2) { index ->
            queue.addLast(AutomationIntentParseResult.Rejected("invalid-$index"))
        }
        val stop = AutomationIntentParseResult.Accepted(AutomationCommand.Stop)

        assertEquals(
            PendingAutomationEnqueueResult.STOP_SUPERSEDED,
            enqueuePendingAutomation(queue, stop, maxPendingCommands = 2),
        )
        assertEquals(listOf(stop), queue.toList())
    }

    private fun parse(input: AutomationIntentInput): AutomationIntentParseResult =
        AutomationIntentContract.parse(input, KNOWN_IDS)

    private fun acceptedStart(input: AutomationIntentInput): AutomationCommand.Start {
        val result = parse(input)
        assertTrue("Expected accepted command, got $result", result is AutomationIntentParseResult.Accepted)
        return (result as AutomationIntentParseResult.Accepted).command as AutomationCommand.Start
    }

    private fun assertRejected(input: AutomationIntentInput, messagePart: String) {
        val result = parse(input)
        assertTrue("Expected rejection, got $result", result is AutomationIntentParseResult.Rejected)
        assertTrue(
            (result as AutomationIntentParseResult.Rejected).message,
            result.message.contains(messagePart),
        )
    }

    private companion object {
        val KNOWN_IDS = setOf("baseline", "transform", "video")
    }
}
