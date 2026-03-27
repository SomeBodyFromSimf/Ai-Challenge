package com.sbfs.ai.data

data class Params(
    val manageContextStrategy: ContextMinimizationStrategy = ContextMinimizationStrategy.NO_STRATEGY,
)

enum class ContextMinimizationStrategy {
    NO_STRATEGY,
    SUMMARY,
    SLIDING,
    STICKY_FACTS,
    BRANCHING
}
