package com.inspiredandroid.kai.data

/**
 * Model IDs (by prefix, lower-cased) that can't drive tool/function-calling loops
 * reliably. Add new small/weak models here when they prove unable to emit well-formed
 * tool_use JSON or to parse tool schemas.
 */
internal val LIMITED_MODELS = listOf(
    "llama3.2:1b",
    "llama3.2:3b",
    "llama3.1:8b",
    "gemma2",
    "gemma:2b",
    "gemma:7b",
    "gemma3",
    "gemma-3",
    "gemma-4-e2b",
    "gemma-4-e4b",
    "phi3:mini",
    "tinyllama",
    "stablelm",
    "codellama",
    "deepseek-coder:1.3b",
    "deepseek-coder:6.7b",
    // Perplexity Sonar models use built-in web search, not OpenAI tool/function calling.
    // Sending a tools[] array is rejected (or silently ignored), so treat them as text-only.
    "sonar",
)

/** True if the model can handle tool_use / tool_result round-trips. */
internal fun supportsTools(modelId: String): Boolean {
    val lower = modelId.lowercase()
    return LIMITED_MODELS.none { lower.startsWith(it) }
}

/**
 * GLM / Zhipu text model ids (normalized: any `provider/` prefix stripped, lower-cased) that
 * DON'T accept image input even though Z.AI's service does — Z.AI serves these text models next
 * to the multimodal GLM-V variants. Sending image content-parts to them returns a hard 400 that
 * then poisons every later turn in the chat (the image stays in history), so we strip images.
 *
 * Exact match only: the vision variants (glm-4.6v, glm-4v-plus, …) share a prefix with the text
 * ones (glm-4.6, glm-4), so prefix matching would wrongly flag them as text-only. DeepSeek, which
 * has no vision models at all, is matched by family prefix in [modelSupportsImages] instead.
 */
internal val TEXT_ONLY_IMAGE_MODELS: Set<String> = setOf(
    // GLM / Zhipu text models. The -v / v-plus variants are multimodal and are
    // deliberately left out so images still reach them.
    "glm-4.6",
    "glm-4.6-air",
    "glm-4.5",
    "glm-4.5-air",
    "glm-4.5-air:free",
    "glm-4.5-x",
    "glm-4-plus",
    "glm-4-plus-0111",
    "glm-4-air",
    "glm-4-airx",
    "glm-4-long",
    "glm-4-flash",
    "glm-4-32b",
    "glm-z1-airx",
    "glm-z1-air",
    "glm-z1-flash",
    "glm-5",
    "glm5",
    "glm-5.1",
    "glm-5.2",
    "glm5.2",
    "glm-5.2-max",
    "glm-5-turbo",
    "glm-4.7",
    "glm4.7",
    "zai-glm-4.7",
    "glm-4.7-flash",
    "chatglm3-6b",
)

/**
 * True if the model accepts image input. Defaults to `true` for unknown models — most modern
 * flagship models are multimodal, and stripping images from a model that supports them is a
 * worse failure than leaving the rare unknown text-only model to reject them.
 *
 * DeepSeek is matched by family prefix: its chat API has no vision models at all, so this also
 * covers DeepSeek reached through an aggregator and any future DeepSeek id. GLM is matched
 * exactly via [TEXT_ONLY_IMAGE_MODELS] because its vision variants share a prefix with the text ones.
 */
internal fun modelSupportsImages(modelId: String): Boolean {
    val key = modelId.substringAfterLast('/').lowercase()
    // DeepSeek's chat models are all text-only; the lone vision family is DeepSeek-VL, which
    // carries "vl" in the id. Match the text family by prefix so this also covers DeepSeek via
    // aggregators and future ids, while leaving DeepSeek-VL recognised as multimodal.
    if (key.startsWith("deepseek") && !key.contains("vl")) return false
    return key !in TEXT_ONLY_IMAGE_MODELS
}

/**
 * True if a service+model combo is suitable for autonomous/agentic flows —
 * heartbeat, interactive mode, and any future background feature that runs a
 * tool-calling loop without the user present to course-correct.
 *
 * Two gates, both must hold:
 *  - **Service** must be remote: on-device inference (LiteRT) can't run long
 *    agentic loops reliably.
 *  - **Model** must support tools: some small open-weight models don't
 *    (see [LIMITED_MODELS]).
 *
 * If you're filtering a service/model picker for a background feature,
 * prefer this over checking either gate in isolation.
 */
internal fun supportsAgenticFlows(serviceId: String, modelId: String): Boolean = !Service.fromId(serviceId).isOnDevice && supportsTools(modelId)

/**
 * OpenAI model id prefixes whose function calling only works on the Responses API
 * (`/v1/responses`). Chat completions answers these with
 * `400 Function tools with reasoning_effort are not supported for gpt-5.6-terra in
 * /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to
 * 'none'.`
 *
 * The family reasons by default, so the rejection lands even though Kai never sends
 * `reasoning_effort` — and the documented chat-completions escape hatch (`reasoning_effort:
 * "none"`) would trade away the reasoning these models are chosen for.
 *
 * Prefix matching covers the tier ids (`-sol`, `-terra`, `-luna`), the bare `gpt-5.6` alias, and
 * effort-suffixed variants (`gpt-5.6-luna-xhigh`). Add a prefix here when a new OpenAI family
 * shows the same 400.
 *
 * `gpt-6` is included on the GPT-5.6 precedent: a reasoning-by-default flagship, where the
 * Responses API is the only path that supports tools alongside reasoning. If OpenAI ever
 * allows tools+reasoning for GPT-6 on chat completions, narrowing this back is safe — the
 * Responses path is fully featured (tools, reasoning summaries, attachments).
 */
internal val RESPONSES_API_MODELS = listOf(
    "gpt-5.6",
    "gpt-6",
)

/**
 * True when this service+model reaches OpenCode's gateway: the first-party OpenCode (Zen)
 * and OpenCode Go services, or the OpenAI-Compatible service pointed at an opencode.ai
 * base URL (how Go was reached before the dedicated preset). Mirrors `isOpenCodeEndpoint`
 * in Requests.kt, duplicated here to avoid a data→network package cycle.
 */
internal fun isOpenCodeGateway(service: Service, baseUrl: String = ""): Boolean {
    if (service == Service.OpenCode || service == Service.OpenCodeGo) return true
    if (service != Service.OpenAICompatible) return false
    return baseUrl.contains("opencode.ai", ignoreCase = true)
}

/** True for Go (the Go preset, or `…/zen/go/…` bases); false for Zen. Only meaningful with [isOpenCodeGateway]. */
internal fun isOpenCodeGoBase(service: Service, baseUrl: String = ""): Boolean {
    if (service == Service.OpenCodeGo) return true
    if (service == Service.OpenCode) return false
    return baseUrl.contains("/zen/go/", ignoreCase = true)
}

/**
 * True when this service+model must talk to OpenAI's Responses API instead of chat completions.
 *
 * Gated on reaching OpenAI directly: aggregators that resell the same models (OpenRouter, AI
 * HubMix, …) translate to the Responses API themselves and only accept chat completions, so
 * routing their ids to `/responses` would break them. The OpenAI-Compatible service qualifies when
 * its base URL points at OpenAI, which is what a user reaching for that service as a workaround
 * would configure.
 *
 * The OpenCode gateway qualifies separately with its own id list: Zen and Go serve their
 * GPT/Grok/Muse Spark models (including the free `muse-spark-1.3-contributor-free`) on the
 * Responses endpoint only.
 */
internal fun requiresResponsesApi(service: Service, modelId: String, baseUrl: String = ""): Boolean {
    if (service.responsesUrl == null) return false
    val id = modelId.substringAfterLast('/').lowercase()
    if (isOpenCodeGateway(service, baseUrl)) {
        return OPENCODE_RESPONSES_API_MODELS.any { id.startsWith(it) }
    }
    val isOpenAiEndpoint = service == Service.OpenAI || baseUrl.contains("api.openai.com", ignoreCase = true)
    if (!isOpenAiEndpoint) return false
    return RESPONSES_API_MODELS.any { id.startsWith(it) }
}

/**
 * OpenCode-gateway model id prefixes served on the Responses endpoint. Covers Zen's and Go's
 * GPT, Grok and Muse Spark rows (per opencode.ai/docs/zen and /go) — every `gpt-5*`/`gpt-6*`
 * tier, every `grok-*`, and `muse-spark-*` including the free contributor tier.
 */
internal val OPENCODE_RESPONSES_API_MODELS = listOf(
    "gpt-5",
    "gpt-6",
    "grok",
    "muse-spark",
)

/**
 * True when this service+model must talk to the gateway's Anthropic Messages endpoint
 * instead of chat completions. Only the OpenCode gateway qualifies: Zen lists its Claude and
 * Qwen models on `/messages`, Go additionally serves its MiniMax models there (Zen serves
 * the same MiniMax ids on chat completions, so the Go base switches lists).
 */
internal fun requiresMessagesApi(service: Service, modelId: String, baseUrl: String = ""): Boolean {
    if (service.messagesUrl == null) return false
    if (!isOpenCodeGateway(service, baseUrl)) return false
    val id = modelId.substringAfterLast('/').lowercase()
    val candidates = if (isOpenCodeGoBase(service, baseUrl)) {
        OPENCODE_GO_MESSAGES_API_MODELS
    } else {
        OPENCODE_ZEN_MESSAGES_API_MODELS
    }
    return candidates.any { id.startsWith(it) }
}

/** Zen serves these families on `zen/v1/messages` (Anthropic shape). */
internal val OPENCODE_ZEN_MESSAGES_API_MODELS = listOf(
    "claude",
    "qwen",
)

/** Go serves these families on `zen/go/v1/messages`; MiniMax additionally (unlike Zen). */
internal val OPENCODE_GO_MESSAGES_API_MODELS = listOf(
    "claude",
    "qwen",
    "minimax",
)

/**
 * Zen free-tier chat models served by strict upstreams: echoing `reasoning_content`
 * back answers 400, so these stay silent even though the OpenCode service otherwise
 * echoes (its paid DeepSeek route requires the echo). The free Responses model
 * (`muse-spark-1.3-contributor-free`) needs no entry — the Responses path never
 * replays reasoning at all.
 */
internal val ZEN_FREE_NO_ECHO_MODELS = listOf(
    "big-pickle",
    "mimo-v2.5-free",
    "ling-3.0-flash-fin-free",
    "nemotron-3-ultra-free",
    "nemotron-3.5-lightning-free",
    "jev-1.13-free",
    "deepseek-v4-flash-free",
)

/**
 * Effective reasoning echo mode for one service+model. Defaults to the service's
 * [Service.reasoningRequestMode]; Zen's strict free-tier models opt out (see
 * [ZEN_FREE_NO_ECHO_MODELS]).
 */
internal fun reasoningModeFor(service: Service, modelId: String): ReasoningRequestMode {
    if (service == Service.OpenCode) {
        val id = modelId.substringAfterLast('/').lowercase()
        if (ZEN_FREE_NO_ECHO_MODELS.any { id.startsWith(it) }) return ReasoningRequestMode.NONE
    }
    return service.reasoningRequestMode
}
