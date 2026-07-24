package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.ScenarioPlanPolicy
import java.util.ArrayDeque

/**
 * Explicit-component automation contract.
 *
 * Callers must target the permission-protected AutomationActivity alias with `-n`. The manifest
 * declares these actions so Android's safer-intent matching accepts them, but intentionally omits
 * CATEGORY_DEFAULT so they are not launchable as general implicit Activity intents.
 */
object AutomationIntentContract {
    /** Stable explicit component used by adb/lab harnesses; launcher rebranding must not alter it. */
    const val COMPONENT_CLASS_NAME = "com.example.dpulayerlab.AutomationActivity"
    const val ACTION_START = "com.example.dpulayerlab.action.START"
    const val ACTION_STOP = "com.example.dpulayerlab.action.STOP"
    const val ACTION_SHOW = "com.example.dpulayerlab.action.SHOW"

    const val EXTRA_SCENARIO_ID = "scenario_id"
    const val EXTRA_SCENARIO_IDS = "scenario_ids"
    const val EXTRA_REPEAT_COUNT = "repeat_count"

    fun parse(
        input: AutomationIntentInput,
        knownScenarioIds: Set<String>,
    ): AutomationIntentParseResult {
        val action = input.action
        if (action != null && action.length > MAX_ACTION_CHARS) {
            return if (action.startsWith(ACTION_PREFIX)) {
                AutomationIntentParseResult.Rejected("automation action이 너무 깁니다.")
            } else {
                AutomationIntentParseResult.Ignored
            }
        }
        return when (action) {
            ACTION_SHOW -> AutomationIntentParseResult.Accepted(AutomationCommand.Show)
            ACTION_STOP -> AutomationIntentParseResult.Accepted(AutomationCommand.Stop)
            ACTION_START -> parseStart(input, knownScenarioIds)
            null,
            "android.intent.action.MAIN",
            -> AutomationIntentParseResult.Ignored
            else -> {
                if (action.startsWith(ACTION_PREFIX)) {
                    AutomationIntentParseResult.Rejected(
                        "지원하지 않는 automation action입니다: $action",
                    )
                } else {
                    AutomationIntentParseResult.Ignored
                }
            }
        }
    }

    private fun parseStart(
        input: AutomationIntentInput,
        knownScenarioIds: Set<String>,
    ): AutomationIntentParseResult {
        if (input.scenarioIdPresent && input.scenarioIdsPresent) {
            return AutomationIntentParseResult.Rejected(
                "scenario_id와 scenario_ids는 동시에 지정할 수 없습니다.",
            )
        }
        if (!input.scenarioIdPresent && !input.scenarioIdsPresent) {
            return AutomationIntentParseResult.Rejected(
                "START에는 scenario_id 또는 scenario_ids가 필요합니다.",
            )
        }

        val rawIds = if (input.scenarioIdPresent) {
            val value = input.scenarioId
            if (value !is CharSequence) {
                return AutomationIntentParseResult.Rejected(
                    "scenario_id는 문자열이어야 합니다.",
                )
            }
            if (value.length > MAX_SCENARIO_ID_CHARS) {
                return AutomationIntentParseResult.Rejected(
                    "scenario_id가 너무 깁니다.",
                )
            }
            listOf(value.toString())
        } else {
            when (val value = input.scenarioIds) {
                is CharSequence -> {
                    if (value.length > MAX_SCENARIO_IDS_TEXT_CHARS) {
                        return AutomationIntentParseResult.Rejected(
                            "scenario_ids 문자열이 너무 깁니다.",
                        )
                    }
                    splitCommaPreservingEmpty(value.toString())
                }
                is Array<*> -> {
                    if (value.size > ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS) {
                        return tooManyScenarioIds(value.size)
                    }
                    value.mapTo(ArrayList(value.size)) { item ->
                        if (item !is CharSequence) {
                            return AutomationIntentParseResult.Rejected(
                                "scenario_ids 배열에는 문자열만 사용할 수 있습니다.",
                            )
                        }
                        item.toString()
                    }
                }
                is List<*> -> {
                    if (value.size > ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS) {
                        return tooManyScenarioIds(value.size)
                    }
                    value.mapTo(ArrayList(value.size)) { item ->
                        if (item !is CharSequence) {
                            return AutomationIntentParseResult.Rejected(
                                "scenario_ids 목록에는 문자열만 사용할 수 있습니다.",
                            )
                        }
                        item.toString()
                    }
                }
                else -> {
                    return AutomationIntentParseResult.Rejected(
                        "scenario_ids는 문자열, 문자열 배열 또는 문자열 목록이어야 합니다.",
                    )
                }
            }
        }

        if (rawIds.size > ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS) {
            return tooManyScenarioIds(rawIds.size)
        }
        val scenarioIds = rawIds.map(String::trim)
        if (scenarioIds.isEmpty() || scenarioIds.any(String::isEmpty)) {
            return AutomationIntentParseResult.Rejected(
                "시나리오 ID는 비어 있을 수 없습니다.",
            )
        }
        if (scenarioIds.any { it.length > MAX_SCENARIO_ID_CHARS }) {
            return AutomationIntentParseResult.Rejected(
                "시나리오 ID가 너무 깁니다.",
            )
        }
        val unknownId = scenarioIds.firstOrNull { it !in knownScenarioIds }
        if (unknownId != null) {
            return AutomationIntentParseResult.Rejected(
                "알 수 없는 catalog scenario_id입니다: $unknownId",
            )
        }

        val repeatCount = parseRepeatCount(input)
            ?: return AutomationIntentParseResult.Rejected(
                "repeat_count는 1..${ScenarioPlanPolicy.MAX_REPEAT_COUNT} 정수여야 합니다.",
            )
        if (repeatCount !in 1..ScenarioPlanPolicy.MAX_REPEAT_COUNT) {
            return AutomationIntentParseResult.Rejected(
                "repeat_count는 1..${ScenarioPlanPolicy.MAX_REPEAT_COUNT} 범위여야 합니다.",
            )
        }
        val totalRuns = scenarioIds.size.toLong() * repeatCount.toLong()
        if (totalRuns > ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS) {
            return AutomationIntentParseResult.Rejected(
                "총 실행 수 $totalRuns 회는 최대 " +
                    "${ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS} 회를 초과합니다.",
            )
        }

        return AutomationIntentParseResult.Accepted(
            AutomationCommand.Start(
                scenarioIds = scenarioIds,
                repeatCount = repeatCount,
            ),
        )
    }

    private fun tooManyScenarioIds(count: Int): AutomationIntentParseResult.Rejected =
        AutomationIntentParseResult.Rejected(
            "scenario_ids 항목 수 $count 개는 최대 " +
                "${ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS} 개를 초과합니다.",
        )

    private fun parseRepeatCount(input: AutomationIntentInput): Int? {
        if (!input.repeatCountPresent) return 1
        return when (val value = input.repeatCount) {
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Int -> value
            is Long -> value.takeIf {
                it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            }?.toInt()
            is CharSequence -> value.toString()
                .trim()
                .takeIf { it.length <= MAX_REPEAT_TEXT_CHARS }
                ?.toIntOrNull()
            else -> null
        }
    }

    private fun splitCommaPreservingEmpty(value: String): List<String> {
        val result = ArrayList<String>()
        var tokenStart = 0
        value.forEachIndexed { index, character ->
            if (character == ',') {
                result += value.substring(tokenStart, index)
                tokenStart = index + 1
            }
        }
        result += value.substring(tokenStart)
        return result
    }

    private const val MAX_SCENARIO_ID_CHARS = 128
    private const val MAX_SCENARIO_IDS_TEXT_CHARS = 4_096
    private const val MAX_REPEAT_TEXT_CHARS = 11
    private const val MAX_ACTION_CHARS = 256
    private const val ACTION_PREFIX = "com.example.dpulayerlab.action."
}

data class AutomationIntentInput(
    val action: String?,
    val scenarioIdPresent: Boolean = false,
    val scenarioId: Any? = null,
    val scenarioIdsPresent: Boolean = false,
    val scenarioIds: Any? = null,
    val repeatCountPresent: Boolean = false,
    val repeatCount: Any? = null,
)

sealed interface AutomationIntentParseResult {
    data object Ignored : AutomationIntentParseResult
    data class Accepted(val command: AutomationCommand) : AutomationIntentParseResult
    data class Rejected(val message: String) : AutomationIntentParseResult
}

sealed interface AutomationCommand {
    data class Start(
        val scenarioIds: List<String>,
        val repeatCount: Int,
    ) : AutomationCommand

    data object Stop : AutomationCommand
    data object Show : AutomationCommand
}

/**
 * Only START consumes extras. Keeping this decision separate lets the Activity honor STOP without
 * first unparcelling attacker-controlled or version-skewed Bundle payloads.
 */
internal fun automationActionNeedsStartExtras(action: String?): Boolean =
    action == AutomationIntentContract.ACTION_START

internal enum class PendingAutomationEnqueueResult {
    ENQUEUED,
    DUPLICATE,
    OVERFLOW,
    STOP_SUPERSEDED,
}

/**
 * Applies the startup queue's safety ordering.
 *
 * STOP is handled before duplicate and capacity checks. The newest STOP invalidates every command
 * that has not started, including a START accepted after an older STOP.
 */
internal fun enqueuePendingAutomation(
    queue: ArrayDeque<AutomationIntentParseResult>,
    parsed: AutomationIntentParseResult,
    maxPendingCommands: Int,
): PendingAutomationEnqueueResult {
    require(maxPendingCommands > 0)
    if (
        parsed is AutomationIntentParseResult.Accepted &&
        parsed.command == AutomationCommand.Stop
    ) {
        queue.clear()
        queue.addLast(parsed)
        return PendingAutomationEnqueueResult.STOP_SUPERSEDED
    }
    if (parsed in queue) return PendingAutomationEnqueueResult.DUPLICATE
    if (queue.size >= maxPendingCommands) return PendingAutomationEnqueueResult.OVERFLOW
    queue.addLast(parsed)
    return PendingAutomationEnqueueResult.ENQUEUED
}
