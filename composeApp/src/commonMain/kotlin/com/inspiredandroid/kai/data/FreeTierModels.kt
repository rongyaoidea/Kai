package com.inspiredandroid.kai.data

/**
 * Curated per-service sets of model ids that are usable on that provider's
 * free tier (with free-tier rate/usage limits). Free-ness is service-scoped:
 * the same weights may be free on OpenRouter but paid elsewhere.
 *
 * This object is the **runtime** source of truth for Free badges — not live
 * pricing APIs. Curation policy, provenance, and the mirrored snapshot live in
 * the OKF bundle `docs/knowledge/free-tier/`. Refresh both via the
 * `update-free-tier-models` skill.
 *
 * Keys and set members are lowercase.
 */
internal object FreeTierModels {

    /**
     * OpenRouter models with $0 prompt + completion pricing (chat-oriented).
     * Snapshot maintained by hand / skill; ids usually end in `:free`.
     */
    private val openRouterFree: Set<String> = setOf(
        "cohere/north-mini-code:free",
        "dots-studio/dots-3-note-preview:free",
        "google/gemma-4-26b-a4b-it:free",
        "google/gemma-4-31b-it:free",
        "inclusionai/ling-3.0-flash-fin:free",
        "inclusionai/ling-3.0-flash-sante:free",
        "inclusionai/ling-3.0-flash-vl:free",
        "liquid/lfm-2.5-2.6b:free",
        "nex-agi/nex-n2.5-mini:free",
        "nex-agi/nex-n2.5-pro:free",
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "nvidia/nemotron-3.5-lightning:free",
        "openrouter/free",
        "poolside/laguna-s-2.1:free",
        "poolside/laguna-xs-2.1:free",
        "thinkingmachines/inkling-small:free",
        "thinkingmachines/inkling:free",
    )

    /**
     * Ollama Cloud free-plan models. Free includes light cloud usage; usage
     * level Low models fit free quotas best. Ids match `ollama.com/v1/models`
     * (often without a `-cloud` suffix). Aliases with `:cloud` / `-cloud` are
     * normalized in [normalizeOllamaId].
     */
    private val ollamaCloudFree: Set<String> = setOf(
        "gpt-oss:20b",
        "gemma4:31b",
        "nemotron-3-nano:30b",
    )

    private val byService: Map<String, Set<String>> = mapOf(
        Service.OpenRouter.id to openRouterFree,
        Service.OllamaCloud.id to ollamaCloudFree,
    )

    fun isFreeTier(service: Service, modelId: String): Boolean {
        val set = byService[service.id] ?: return false
        val lower = modelId.lowercase()
        if (lower in set) return true
        if (service == Service.OllamaCloud) {
            return normalizeOllamaId(lower) in set
        }
        return false
    }

    /**
     * Strip cloud tag suffixes so library-style ids (`gpt-oss:20b-cloud`,
     * `gemma4:cloud`) match API-style free-list entries (`gpt-oss:20b`,
     * `gemma4`).
     */
    internal fun normalizeOllamaId(modelId: String): String {
        val lower = modelId.lowercase()
        return when {
            lower.endsWith(":cloud") -> lower.removeSuffix(":cloud").ifEmpty { lower }

            lower.endsWith("-cloud") -> {
                val stripped = lower.removeSuffix("-cloud")
                // e.g. gpt-oss:20b-cloud → gpt-oss:20b
                stripped
            }

            else -> lower
        }
    }
}
