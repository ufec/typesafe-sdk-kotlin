// Run with `./gradlew :examples:run`. Needs TYPESAFE_API_KEY in the environment.
//
// Mirrors the upstream JavaScript SDK's examples/demo.ts, which is the reason the
// questions and the ticket text below match it rather than being invented here.
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.ethanxu.typesafe.sdk.APIException
import me.ethanxu.typesafe.sdk.LogLevel
import me.ethanxu.typesafe.sdk.QuestionId
import me.ethanxu.typesafe.sdk.TypeSafeClient
import me.ethanxu.typesafe.sdk.TypeSafeConfig
import me.ethanxu.typesafe.sdk.choice
import me.ethanxu.typesafe.sdk.noul
import me.ethanxu.typesafe.sdk.score

private val isBilling = QuestionId(
    name = "is_billing",
    question = noul("Is this ticket about billing?"),
)

private val sentiment = QuestionId(
    name = "sentiment",
    question = choice(
        instructions = "What is the customer's tone?",
        criteria = mapOf("calm" to null, "frustrated" to null, "angry" to null),
    ),
)

private val urgency = QuestionId(
    name = "urgency",
    question = score(
        instructions = "How urgent is this ticket?",
        criteria = listOf("can wait", "this week", "today", "right now"),
    ),
)

private val refundRisk = QuestionId(
    name = "refund_risk",
    question = score(
        instructions = "How likely is the customer to demand a refund?",
        criteria = listOf("unlikely", "possible", "likely"),
    ),
)

fun main() = runBlocking {
    val apiKey = System.getenv("TYPESAFE_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("TYPESAFE_API_KEY is not set. Export it and run again.")
        return@runBlocking
    }

    // The client owns an HTTP engine, so it is closed rather than left to the GC.
    TypeSafeClient(TypeSafeConfig(apiKey = apiKey, logLevel = LogLevel.INFO)).use { client ->
        // Both calls sit inside the try. The upstream JavaScript demo catches only
        // the second one, which means a bad API key escapes as an unhandled
        // rejection before the friendly handler can run -- an unhelpful first
        // impression for something whose whole purpose is to be run.
        try {
            val models = client.models.list()
            println("Available models: " + models.joinToString(", ") { it.name })
            println()

            val ticket = buildJsonObject {
                put("subject", JsonPrimitive("Charged twice this month"))
                put(
                    "body",
                    JsonPrimitive(
                        "Hi, I see two charges of \$49 on my card for August. " +
                            "I only have one account. Please fix this ASAP, I'm pretty frustrated.",
                    ),
                )
            }

            val result = client.systemOne(ticket) {
                ask(isBilling)
                ask(sentiment)
                ask(urgency)
                ask(refundRisk)
            }

            // Each answer's type is fixed by the QuestionId that produced it, so
            // there is nothing to cast and nothing to guess.
            println("billing?     " + fmt(result.answer(isBilling).noul))

            val tone = result.answer(sentiment)
            println("tone         ${tone.choice} (${fmt(tone.probabilities[tone.choice] ?: 0.0)})")

            val howUrgent = result.answer(urgency)
            println(
                "urgency      ${fmt(howUrgent.score)} on a 0-3 scale: " +
                    howUrgent.legend.toSortedMap(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE }),
            )

            val risk = result.answer(refundRisk)
            println("refund risk  ${fmt(risk.score)} (${fmt(risk.confidence)} confidence)")

            println("tokens       ${result.usage.inputTokens} in / ${result.usage.outputTokens} out")
        } catch (e: APIException) {
            // Reported, not rethrown, and the process still exits zero. Letting the
            // exception escape would bury this one useful line under a Gradle
            // "BUILD FAILED" block, which is a lot of noise for a demo.
            System.err.println(
                "API error ${e.status} (request ${e.requestId ?: "unknown"}): ${e.body}",
            )
        }
    }
}

/** Two decimals, matching how the JavaScript demo renders numbers. */
private fun fmt(value: Double): String = String.format("%.2f", value)
