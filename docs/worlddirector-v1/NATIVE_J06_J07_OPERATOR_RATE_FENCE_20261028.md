# J06/J07 native operator traffic safety — 28 October 2026

## Corrected transport boundary

The native NeoForge `DirectorNativeNetworkBootstrap.serverbound` previously dispatched **every** authenticated-operator `AdminQuery` directly to the J07/J04 WAL read bridge, without invoking the existing actor-owned `PerceptionTransport.adminQuery` quota. Even though the underlying `PerceptionTransport` already implemented authentication and a 40-tick read refresh fence, that fence was bypassed on the real server inbound path.

The native inbound adapter now checks the **real** `ServerPlayer.hasPermissions(4)` and the correlation ID for the **current J06 connection epoch** before it calls `PerceptionTransport.adminQuery`. A mismatch receives `AUTH_DENIED`, a retry inside the bounded 40-tick refresh interval receives `RATE_LIMITED`, and an accepted request alone reaches the WAL-backed J07 read. Each operator denial is returned with the original request ID as `AdminCommandResult(REJECTED)`, which the physical J07 page uses to terminate the pending read safely rather than hang on a lost result. Non-operators receive no private data.

The still-disabled mutation `AdminCommand` inbound path now also applies operator/session authentication and the existing per-second admin command quota before sending its **rejection**. It never installs a mailbox, performs an effect or grants a gameplay capability.

## Regression evidence

The previously standalone J06 transport test was not invoked by the 69,923-assertion contract suite. A new explicit `directorNetworkTest` Gradle gate now runs it automatically as an independent, offline-only task. `J06_TRANSPORT_EXPLICIT_GATE_20261028.log` reports **3,599 transport/client-state assertions PASS**, including cross-player spoof rejection, a request before tick 40, admission at tick 40 and a new connection invalidating the old actor session. The combined `J06_J07_AUTH_RATE_29_NATIVE_20261028.log` reports **29/29 real NeoForge GameTests PASS**, **69,923** domain contract assertions PASS, and Gradle SUCCESS. These are transport + server-integration tests; they do not claim real two-client interactive rendering.

No public listener or production server is started. The mod remains **LAB / DEFAULT_FOR_TESTING / not deployable** pending J02/J05/J06 real providers, both physical clients and user APPROVED calibration.
