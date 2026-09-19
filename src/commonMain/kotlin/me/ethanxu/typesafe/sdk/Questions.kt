package me.ethanxu.typesafe.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A single typed question.
 *
 * Mirrors `NoulQuestion | ScoreQuestion | ChoiceQuestion` from the upstream
 * `types.ts`. The key difference: [Question] carries one type parameter in one
 * direction only — `A` is the **answer** type, fixed by each subclass. That lets
 * [SystemOneResult.answer] guarantee the correct type at compile time, whereas
 * upstream relies on TypeScript's `ResultFor<T>` conditional type at the type
 * level while remaining a `Record<string, Answer>` at runtime.
 */
sealed class Question<A : Answer> {
    /** Matches the `type` field of the returned answer. */
    abstract val type: String

    /** The question itself; may be text, a JSON object, or a JSON array. */
    abstract val instructions: EntryType

    internal abstract fun toJson(): JsonObject

    internal abstract fun parseAnswer(element: JsonObject): A
}

/** The two sides of a noul question. Mirrors upstream `NoulQuestion["criteria"]`. */
data class NoulCriteria(
    /** What an answer of "yes" (close to 1) means. */
    val trueDescription: EntryType = null,
    /** What an answer of "no" (close to 0) means. */
    val falseDescription: EntryType = null,
)

/**
 * A yes/no question. The answer is a [NoulAnswer].
 *
 * Upstream states it explicitly: a noul answer carries **no confidence**,
 * because the returned value is itself a probability.
 */
class NoulQuestion(
    override val instructions: EntryType = null,
    val criteria: NoulCriteria? = null,
) : Question<NoulAnswer>() {
    override val type: String get() = NoulAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        criteria?.let { c ->
            put(
                "criteria",
                buildJsonObject {
                    put("true", c.trueDescription ?: JsonNull)
                    put("false", c.falseDescription ?: JsonNull)
                },
            )
        }
    }

    override fun parseAnswer(element: JsonObject): NoulAnswer =
        NoulAnswer(noul = element.requireDouble("noul"))
}

/**
 * Picks one of a set of named options. The answer is a [ChoiceAnswer].
 *
 * The keys of `criteria` are the answer space, which makes this the natural
 * shape for letting a caller's own categories drive the classification.
 */
class ChoiceQuestion(
    override val instructions: EntryType,
    val criteria: Map<String, EntryType>,
) : Question<ChoiceAnswer>() {
    override val type: String get() = ChoiceAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        put(
            "criteria",
            buildJsonObject {
                criteria.forEach { (label, description) ->
                    put(label, description ?: JsonNull)
                }
            },
        )
    }

    override fun parseAnswer(element: JsonObject): ChoiceAnswer = ChoiceAnswer(
        choice = element.requireString("choice"),
        confidence = element.requireDouble("confidence"),
        probabilities = element.requireDoubleMap("probabilities"),
    )
}

/**
 * Scores against an ordered rubric. The answer is a [ScoreAnswer].
 *
 * `criteria` is an ordered list of bucket descriptions and must hold **at least
 * two** entries, which [validateQuestions] enforces.
 */
class ScoreQuestion(
    override val instructions: EntryType,
    val criteria: List<EntryType>,
) : Question<ScoreAnswer>() {
    override val type: String get() = ScoreAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        put("criteria", JsonArray(criteria.map { it ?: JsonNull }))
    }

    override fun parseAnswer(element: JsonObject): ScoreAnswer = ScoreAnswer(
        score = element.requireDouble("score"),
        confidence = element.requireDouble("confidence"),
        legend = element.requireStringMap("legend"),
        probabilities = element.requireDoubleMap("probabilities"),
    )
}

/**
 * A handle to a named question, binding the answer type along with it.
 *
 * ```
 * val category = QuestionId("category", choice("Which category is this?", mapOf("spam" to "…", "normal" to "…")))
 * val result = client.systemOne(state) { ask(category) }
 * val answer: ChoiceAnswer = result.answer(category)   // type is fixed at compile time
 * ```
 */
class QuestionId<A : Answer>(
    val name: String,
    val question: Question<A>,
)

// ---------------------------------------------------------------------------
// Builders (mirroring noul / choice / score from the upstream questions.ts)
// ---------------------------------------------------------------------------

/*
 * Upstream throws at runtime when `criteria` is given the wrong container
 * (an array for choice, a map for score). In Kotlin the parameter types rule
 * that out at compile time, so those two checks are unnecessary here.
 *
 * On the **overloads** below: upstream types `EntryType` as
 * `string | object | array | null`, which becomes `JsonElement?` in Kotlin, and
 * `JsonElement` does not accept a bare `String`. Since instructions and criteria
 * descriptions are almost always plain text in practice, each builder gets a
 * `String` overload for that common case while the `JsonElement` form stays
 * available for structured content. The two never become ambiguous, because
 * `String` is not a subtype of `JsonElement?`.
 */

/** Builds a yes/no question whose instructions are plain text. */
fun noul(
    instructions: String,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(JsonPrimitive(instructions), criteria)

/** Builds a yes/no question. `instructions` may be a JSON object or array. */
fun noul(
    instructions: EntryType = null,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(instructions, criteria)

/** Builds plain-text true/false criteria for a noul question. */
fun noulCriteria(
    trueDescription: String? = null,
    falseDescription: String? = null,
): NoulCriteria = NoulCriteria(
    trueDescription = trueDescription?.let(::JsonPrimitive),
    falseDescription = falseDescription?.let(::JsonPrimitive),
)

/** Builds a choice question with plain-text instructions and option descriptions. A null description adds nothing. */
fun choice(
    instructions: String,
    criteria: Map<String, String?>,
): ChoiceQuestion = ChoiceQuestion(
    instructions = JsonPrimitive(instructions),
    criteria = criteria.mapValues { (_, description) -> description?.let(::JsonPrimitive) },
)

/** Builds a choice question. `instructions` and the option descriptions may be JSON objects or arrays. */
fun choice(
    instructions: EntryType,
    criteria: Map<String, EntryType>,
): ChoiceQuestion = ChoiceQuestion(instructions, criteria)

/** Builds a score question with plain-text instructions and bucket descriptions. */
fun score(
    instructions: String,
    criteria: List<String?>,
): ScoreQuestion = ScoreQuestion(
    instructions = JsonPrimitive(instructions),
    criteria = criteria.map { description -> description?.let(::JsonPrimitive) },
)

/** Builds a score question. `instructions` and the bucket descriptions may be JSON objects or arrays. */
fun score(
    instructions: EntryType,
    criteria: List<EntryType>,
): ScoreQuestion = ScoreQuestion(instructions, criteria)

/**
 * Incremental builder used by the lambda form of [TypeSafeClient.systemOne].
 */
class SystemOneRequestBuilder internal constructor(
    private val state: EntryType,
) {
    private val questions = LinkedHashMap<String, Question<*>>()

    /** Overrides the model for this call; defaults to the client's `defaultModel` when unset. */
    var model: String? = null

    /** Registers a question. Retrieve the answer later with the same [QuestionId]. */
    fun <A : Answer> ask(id: QuestionId<A>): SystemOneRequestBuilder = apply {
        questions[id.name] = id.question
    }

    /** Registers a question. Retrieve the answer later by [name]. */
    fun <A : Answer> ask(name: String, question: Question<A>): SystemOneRequestBuilder = apply {
        questions[name] = question
    }

    internal fun build(): SystemOneRequest =
        SystemOneRequest(state = state, model = model, questions = questions.toMap())
}

/**
 * Validates a question set. Mirrors upstream `validateQuestions`.
 *
 * Only the two constraints the Kotlin type system cannot express are kept:
 * the set must be non-empty, and a score question needs at least two buckets.
 *
 * @throws TypeSafeException the question set is empty, or a score question has fewer than two buckets.
 */
internal fun validateQuestions(questions: Map<String, Question<*>>) {
    if (questions.isEmpty()) {
        throw TypeSafeException("At least one question is required.")
    }
    questions.forEach { (name, question) ->
        if (question is ScoreQuestion && question.criteria.size < 2) {
            throw TypeSafeException(
                "Score question \"$name\" has ${question.criteria.size} criteria; " +
                    "at least two scores are required.",
            )
        }
    }
}
