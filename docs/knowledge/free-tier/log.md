# Free-tier knowledge update log

## 2026-09-10

* **Update**: Live refresh via `process:update-free-tier-models` (models API fetched 2026-09-10, 437 models).
  * **OpenRouter** — added 9: `dots-studio/dots-3-note-preview:free`, `inclusionai/ling-3.0-flash-fin:free`, `inclusionai/ling-3.0-flash-sante:free`, `inclusionai/ling-3.0-flash-vl:free`, `liquid/lfm-2.5-2.6b:free`, `nex-agi/nex-n2.5-mini:free`, `nex-agi/nex-n2.5-pro:free`, `thinkingmachines/inkling-small:free`, `thinkingmachines/inkling:free`; removed 5 gone from the API (no longer listed at any price): `inclusionai/ling-3.0-tiny:free`, `nvidia/nemotron-3-nano-30b-a3b:free`, `nvidia/nemotron-nano-12b-v2-vl:free`, `nvidia/nemotron-nano-9b-v2:free`, `openai/gpt-oss-20b:free` (bare `openai/gpt-oss-20b` is now metered). 15 → 19 ids. Test fixture in `ModelTransformationsTest` switched to `nex-agi/nex-n2.5-pro:free`.
  * **Ollama Cloud** — unchanged (3 ids, all still on `/v1/models`). New cloud ids exist but their Low/Medium/High usage level is only published in JS-rendered pages, so per policy nothing was added.
  * `verified` stamps dropped from both bundles until `desktopTest-FreeTierModels` + `ModelTransformations` run green on a machine with a JVM.

## 2026-08-11

* **Update**: Live refresh via `process:update-free-tier-models`.
  * **OpenRouter** — added `inclusionai/ling-3.0-tiny:free`, `nvidia/nemotron-3.5-lightning:free`; removed `inclusionai/ling-3.0-flash:free`, `poolside/laguna-m.1:free` (no longer $0 on models API). 15 → 15 ids.
  * **Ollama Cloud** — kept Low-only API ids `gpt-oss:20b`, `gemma4:31b`, `nemotron-3-nano:30b`; removed bare aliases `gemma4`, `nemotron-3-nano` (not on `/v1/models`; cloud usage for tagged ids remains Low). 5 → 3 ids.
* **Initialization**: Created OKF free-tier bundle from the existing `FreeTierModels.kt` snapshot (OpenRouter 15 ids, Ollama Cloud 5 ids). Policy and sources documented; no live re-fetch on seed.
