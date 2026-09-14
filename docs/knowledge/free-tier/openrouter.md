---
type: Catalog
title: OpenRouter free-tier models
description: Chat models with $0 prompt and completion pricing on OpenRouter.
tags: [models, free-tier, openrouter]
status: stable
resource: https://openrouter.ai/api/v1/models
stale_after: 2026-09-24
generated: { by: process:update-free-tier-models, at: 2026-09-10T00:00:00Z }
sources:
  - id: openrouter-models
    resource: https://openrouter.ai/api/v1/models
    title: OpenRouter models API
  - id: free-tier-playbook
    resource: /refresh-playbook.md
    title: Refresh free-tier catalogs playbook
---

# Policy

Include a model id when **all** of the following hold:

1. It appears in the OpenRouter models API (`resource` above).[^openrouter-models]
2. `pricing.prompt` and `pricing.completion` are both numeric **0** (not missing).
3. It is chat-oriented — same idea as `isChatModel()` in the app: drop ids whose lowercase form contains non-chat patterns such as `embed`, `tts`, `transcribe`, `realtime`, `moderation`, `ocr`, `guard`, `safety`, `reward`, `whisper`, `lyria`, `imagen`, `image`, `veo`, and similar.

Prefer `:free` suffix ids and known routers such as `openrouter/free`.

Store ids **lowercase**. Free-ness applies only to the **OpenRouter** service instance, never as global model metadata.

# Current set

Snapshot mirrored into `FreeTierModels.openRouterFree` (runtime). Replace this list only via the [refresh playbook](refresh-playbook.md).

- `cohere/north-mini-code:free`
- `dots-studio/dots-3-note-preview:free`
- `google/gemma-4-26b-a4b-it:free`
- `google/gemma-4-31b-it:free`
- `inclusionai/ling-3.0-flash-fin:free`
- `inclusionai/ling-3.0-flash-sante:free`
- `inclusionai/ling-3.0-flash-vl:free`
- `liquid/lfm-2.5-2.6b:free`
- `nex-agi/nex-n2.5-mini:free`
- `nex-agi/nex-n2.5-pro:free`
- `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`
- `nvidia/nemotron-3-super-120b-a12b:free`
- `nvidia/nemotron-3-ultra-550b-a55b:free`
- `nvidia/nemotron-3.5-lightning:free`
- `openrouter/free`
- `poolside/laguna-s-2.1:free`
- `poolside/laguna-xs-2.1:free`
- `thinkingmachines/inkling-small:free`
- `thinkingmachines/inkling:free`

# Notes

- Runtime lookup is exact string match on the lowercase model id (no alias normalization for OpenRouter).
- When unsure, leave the id **out** (conservative).

[^openrouter-models]: OpenRouter models API
