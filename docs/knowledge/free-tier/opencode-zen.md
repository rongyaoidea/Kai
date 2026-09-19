---
type: Catalog
title: OpenCode Zen free-tier models
description: Zen models listed at $0 in the OpenCode docs pricing table, plus stealth drops.
tags: [models, free-tier, opencode]
status: stable
resource: https://opencode.ai/docs/zen/
stale_after: 2026-10-03
generated: { by: human:simon, at: 2026-09-19T00:00:00Z }
sources:
  - id: zen-models
    resource: https://opencode.ai/zen/v1/models
    title: OpenCode Zen models API
  - id: zen-pricing
    resource: https://opencode.ai/docs/zen/
    title: OpenCode Zen docs (pricing table and free-model notes)
  - id: free-tier-playbook
    resource: /refresh-playbook.md
    title: Refresh free-tier catalogs playbook
---

# Policy

Include a model id when **either** holds:

1. The id ends in `-free` (the suffix OpenCode uses for its free pool, e.g.
   `mimo-v2.5-free`).
2. The docs pricing table lists the id as **Free** without the suffix (stealth drops
   such as `big-pickle`), and the id is therefore kept in the explicit set in code.

Free Zen models are **client-gated**: the gateway only serves them to requests shaped
like the official OpenCode client. That shaping is a network concern, not a curation
one — see `composeApp/src/commonMain/.../network/ZenAgentShape.kt` and the
[multi-service feature doc](../../features/multi-service.md#session-header-opencode).
The list below therefore exists for two purposes: the Free badge in the model picker,
and deciding which models must be sent in agent shape.

Store ids **lowercase**. Free-ness applies only to the **OpenCode Zen** service
instance, never as global model metadata. The OpenCode Go subscription is a separate
product with no free pool.

# Current set

Snapshot mirrored into `FreeTierModels.openCodeFree` (runtime; the `-free` suffix is
matched at runtime, so this list only needs the ids that lack it — the full set is
recorded here for provenance). Replace this list only via the
[refresh playbook](refresh-playbook.md).

- `big-pickle` (stealth, no suffix)
- `mimo-v2.5-free`
- `ling-3.0-flash-fin-free`
- `nemotron-3-ultra-free`
- `nemotron-3.5-lightning-free`
- `muse-spark-1.3-contributor-free`
- `muse-spark-1.2-contributor-free`
- `jev-1.13-free`
- `deepseek-v4-flash-free`
