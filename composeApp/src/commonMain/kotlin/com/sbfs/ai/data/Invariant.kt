package com.sbfs.ai.data

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
sealed class Invariant {

    abstract val description: String

    abstract fun check(response: String): Boolean


    @Serializable
    data class StackOnly(
        val allowed: Set<String>,
    ): Invariant() {
        override val description: String = "Stack: $allowed"

        override fun check(response: String): Boolean =
            allowed.any { it in response }
    }

    @Serializable
    data class Arch(
        val allowedTypes: Set<ArchType>,
    ): Invariant() {
        override val description: String = "Architecture: $allowedTypes"

        override fun check(response: String): Boolean =
            allowedTypes.any { it.token in response }

        enum class ArchType(val token: String) {
            MVVM("ViewModel"),
            MVP("Presenter"),
            MVC("Controller")
        }
    }

    @Serializable
    data class ExcludeTechnology(
        val excluded: Set<String>,
    ): Invariant() {
        override val description: String = "exclude technologies: $excluded"

        override fun check(response: String): Boolean =
            !excluded.any { it in response }
    }


    companion object {
        val allTypes: List<Pair<String, String>> = listOf(
            StackOnly::class.simpleName!! to "Стэк",
            Arch::class.simpleName!! to "Архитектура",
            ExcludeTechnology::class.simpleName!! to "Исключенные технологии",
        )
    }
}

@Serializable
sealed class ValidateInvariantsResult {
    @Serializable
    data object Success: ValidateInvariantsResult()
    @Serializable
    data class Fail(
        val variants: List<Invariant>,
    ): ValidateInvariantsResult()
}