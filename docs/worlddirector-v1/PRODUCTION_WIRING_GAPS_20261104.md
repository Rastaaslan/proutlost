# World Director S2 — production wiring gap register (2026-11-04)

Status: TEST_ONLY_NON_DEPLOYABLE. This is a code-level inventory, not a claim of live qualification.

## Existing real J05 adapters and dispatch identities

The native `ServerEffectPort` now refuses bindings registered for primitive IDs they do not explicitly support. The durable `CommitPermit` consumption and J04 fsync behavior are unchanged.

| Native binding | Explicit physical ID | Production readiness |
| --- | --- | --- |
| `WeatherBinding` | `proutlost:global_weather_lease` | Real native adapter implemented; NOT lifecycle-wired or G5-qualified. |
| `BlockTransformBinding` | `proutlost:camp_growth` | Real block adapter implemented; NOT lifecycle-wired or G5-qualified. |
| `FaunaSpawnBinding` | `proutlost:fauna_spawn_owned` | Real one-slot adapter implemented; NOT lifecycle-wired or G5-qualified. |
| `FaunaNavigationBinding` | `proutlost:fauna_migrate` | Real route adapter implemented; NOT lifecycle-wired or G5-qualified. |
| `DecorativeBinding` | **none** | Deliberately unbindable until an exact canonical J02 ID has been reviewed. |

Important: the native weather and fauna-spawn IDs above must be reconciled with the reviewed J02 catalogue before production. Merely supporting an ID is NOT an approved descriptor, an admitted event, a durable J04 PREPARE, or a gameplay permit.

## Missing end-to-end components

The actual `DirectorServerLifecycle` currently uses an empty PRODUCTION composition; only a separate optional TEST_ONLY J04 laboratory store can recover. No complete nine-port PRODUCTION graph is installed; production J02/J05/J06 providers and J03 audit-to-admission remain disconnected. Do not change `View.admitsEvents` or `RunControl.PAUSED` on the basis of compiled contracts.

Mandatory remaining validation includes: real native J04 durability and reboot on the current HEAD, all ten physical primitives and thirteen capabilities, nine events and two chains, WARN/perception/cleanup of five catastrophes, two authenticated clients with F8 UI, and G5 24-hour soak/crash/rollback/OBS checks. River/Dam real clients have not been run for this revision. The chain schema currently only supports DEFAULT_FOR_TESTING, so APPROVED requires an explicit design/calibration decision.

## Safety and resource gates

Never start actual S2 before Contra B SHA verified, final map and clean clone qualified, genuine NeoForge 1.21.1 instance identified, no competing Minecraft/Contra jobs, and RAM/SSD/OBS guard green. The actual S2 alone requires exactly `-Xms20G -Xmx20G` in `user_jvm_args.txt`. No release/automatic events and no claim of gameplay success without the required evidence.
