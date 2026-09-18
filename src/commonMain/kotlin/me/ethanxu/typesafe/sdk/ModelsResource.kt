package me.ethanxu.typesafe.sdk

/** The models available to the account. Mirrors upstream `client.models`. */
class ModelsResource internal constructor(
    private val fetch: suspend () -> List<ModelCard>,
) {
    /** Lists the models available to the account. Mirrors upstream `Models.list()`. */
    suspend fun list(): List<ModelCard> = fetch()
}
