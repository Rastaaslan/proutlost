# J04 cold WAL recovery / native GameTest startup qualification

The standalone NeoForge GameTest server advances ticks much faster than wall time.
On a reused disposable world, 998 WAL records required 2759 accelerated game ticks
before J04 published a complete projection and native J01/J02/J03 became ready.
Tests with 900–3600 tick limits could time out while legitimate asynchronous
recovery was underway, falsely reporting missing production dependencies.

The longer, bounded 12000-tick readiness window is **test-only** and changes no
J04 replay algorithms, Minecraft runtime cadence, public ports, production
calibration or physical effects. The seven-independent-visits case gets 24000
ticks because its evidence collection itself spans ~8k ticks after recovery.
All assertions still require actual J04 recovery, WAL fsync and exact durable
readback. Failure to recover within the bounded deadline remains a red test.

Never treat this timeout change as G5 cold-start performance qualification.
Measure wall time and TPS/latency separately with a real dedicated server and
two real clients; leave the automatic gameplay/APPROVED gate closed.
