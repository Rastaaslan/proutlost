# J06 — Authenticated perception asset closure / metadata pin
2026-10-30; isolated TEST_ONLY integration candidate, not yet merged.

The asset fingerprint used for J06 negotiation now binds:
- The `proutlost-director:perception-asset-manifest:v1` hash domain;
- The declared `ResourceId`, `PerceptionKind`, renderer resource location and
  `ProviderMode`;
- Each sorted ResourceLocation dependency and SHA256 of its bytes.

Every field is UTF-8 followed by NUL; each dependency content hash has 32
fixed bytes. A changed rendering path, effect category, provider mode, logical
asset identity or local file byte changes the fingerprint, even if all other
dependencies match. Set insertion order has no effect.

Directly texture-backed SYMBOL, LOCAL_WEATHER and APPARITION must place the
actual rendered ResourceLocation in their hash dependency set. SOUND, PARTICLES
and GHOST can target registry IDs whose underlying pack paths differ, so this
restriction does not incorrectly require registry IDs to be file paths.

TEST_ONLY content stays barred from PRODUCTION. The existing byte-count and
128-entry limits remain active; no production asset or real rendering is
registered by this change.

**Compatibility:** this is a stricter internal hash preimage than the old
dependency-only hash. Any future authored asset manifests must be recomputed
from the exact same fields before J06 authorization; older fingerprints must
not be assumed equivalent. The current shipped runtime asset map is empty,
so there is no APPROVED asset to migrate or promote.

The in-memory ResourceManager regression checks manifest identity, kind,
render path, mode, byte content, deterministic ordering, missing path,
duplicate IDs, oversize bytes and TEST_ONLY rejection. These are **pure
unit-level contract checks**, not a real Minecraft client, sound or texture
qualification.

Validation of the final source:
- Network/client J06: 3638 assertions PASS.
- Contracts: 69927 assertions PASS.
- Catalog: 364; candidate content: 86; scoring: 101; memory: 321 PASS.
- directorJar: PASS (NeoForge standalone JAR), one worker, offline.
- Native GameTests: NOT_RUN on this candidate while WorldPrep is active.
- Real client F8/J01 camp visits: NOT_RUN. No release/APPROVED.
