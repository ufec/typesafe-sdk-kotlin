package me.ethanxu.typesafe.sdk

/**
 * A typed answer returned by TypeSafe.
 *
 * Mirrors `NoulResponse` / `ChoiceResponse` / `ScoreResponse` from the upstream
 * `types.ts`. A sealed hierarchy is used because the three shapes have disjoint
 * field sets and callers are expected to exhaustively branch on them.
 */
sealed interface Answer {
    /** Matches the `type` of the question that produced this answer. */
    val type: String
}

/**
 * noul (yes/no) answer: `noul` is a probability in the 0-1 range.
 *
 * Note that upstream deliberately omits a confidence value for noul answers,
 * because the returned value already is one.
 */
data class NoulAnswer(
    val noul: Double,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "noul"
    }
}

/** choice answer: the selected option, the per-option probability distribution, and the derived confidence. */
data class ChoiceAnswer(
    val choice: String,
    val confidence: Double,
    val probabilities: Map<String, Double>,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "choice"
    }
}

/**
 * score answer: a probability-weighted score (which may land between integer
 * buckets), the bucket legend, and the probability distribution.
 */
data class ScoreAnswer(
    val score: Double,
    val confidence: Double,
    val legend: Map<String, String>,
    val probabilities: Map<String, Double>,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "score"
    }
}
