package me.ethanxu.typesafe.sdk

import kotlinx.serialization.json.JsonElement

/**
 * Anything that can be submitted to TypeSafe: plain text, a JSON object, a JSON
 * array, or `null`.
 *
 * Mirrors `EntryType` from the upstream `types.ts`.
 */
typealias EntryType = JsonElement?

/** Token usage for one request. Mirrors upstream `Usage`. */
data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
)

/** Metadata for a model available to the account. Mirrors upstream `ModelCard`. */
data class ModelCard(
    val name: String,
    val description: String,
    val releaseDate: String,
)

/**
 * The result of one `systemOne` call.
 *
 * Mirrors upstream `SystemOneResult<Q>`. Upstream leans on TypeScript generics to
 * infer each answer's type from the questions; this port instead does a
 * type-safe lookup through a [QuestionId] at the point of use (see [answer]).
 */
class SystemOneResult internal constructor(
    val model: String,
    val answers: Map<String, Answer>,
    val usage: Usage,
) {

    /**
     * Retrieves the answer for [id].
     *
     * The cast is guarded by an `answer.type == question.type` check first, so it
     * is sound at runtime.
     *
     * @throws TypeSafeException there is no answer for this question, or the answer type does not match the question type.
     */
    fun <A : Answer> answer(id: QuestionId<A>): A {
        val raw = answers[id.name]
            ?: throw TypeSafeException("No answer was returned for question \"${id.name}\".")
        if (raw.type != id.question.type) {
            throw TypeSafeException(
                "Answer for question \"${id.name}\" has type \"${raw.type}\" " +
                    "but the question is of type \"${id.question.type}\".",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return raw as A
    }

    override fun toString(): String =
        "SystemOneResult(model=$model, answers=${answers.keys}, usage=$usage)"
}

/**
 * Carries a return value alongside response metadata (HTTP status, request id).
 *
 * Mirrors upstream `APIPromise.withResponse()`.
 */
data class TypeSafeResponse<T>(
    val data: T,
    val status: Int,
    val requestId: String?,
)

/** The full description of one request. Built by [SystemOneRequestBuilder] or [TypeSafeClient.systemOne]. */
class SystemOneRequest internal constructor(
    val state: EntryType,
    val model: String?,
    val questions: Map<String, Question<*>>,
)
