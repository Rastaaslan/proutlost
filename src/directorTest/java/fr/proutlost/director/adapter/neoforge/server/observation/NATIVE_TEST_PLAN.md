# J01 native qualification — NOT_RUN

Work must allocate the serialized native slot and a unique disposable world beneath CB-203 before executing this plan. No active map, external service or deployment is required. `NativeObservationChecks` compiles against the real pinned API but has not executed on a server.

Bootstrap must provide the real simTick clock, health projection, capability handshake projection, sparse primary-region lookup and sparse known-camp-anchor lookup. Register exactly one adapter per MinecraftServer, then unregister it at shutdown. The memory projection worker loads bounded J04 pages; it must never perform a disk read on the server actor.

1. Join with two real clients. Invoke `connectedPlayer` on the server actor for each. Move across a negative grid boundary, separate beyond the proximity radius, and inspect group/isolated snapshots. Budget exhaustion must report missing GROUP_MEMBERSHIP rather than isolated=true.
2. Break/place an ordinary block and interact. Seal the completed observation bucket. Assert one regional primary count, player activity, stable retry input IDs, and no world edits by the observer. Commit its aggregate changes and consumed-input inbox atomically through J04; only then acknowledge the exact batch on the actor.
3. Kill a fixture player through the actual server action. Invoke `afterDeath`; respawn and verify recovery safety remains until the configured deadline. Change dimension through the actual portal/action and invoke `afterDimensionChange`. Disconnect/reconnect and prove offline time adds zero eligible duration.
4. Schedule `workerDenied` on the bounded worker. Consume its completion through the fixture mailbox. Never join/get from the actor.
5. Populate two confirmed camp anchors. Observe repeated stays and spaced revisits, inspect sectors/rings/group buckets and all 12 day bins. Use API1.0.1 ActivityTimeCounts through the combined seal/reduce/durable-ACK path; do not convert indexed bins to four DayPhase values. A fire's single appearance must not create a recognized routine.
6. Drain and durably acknowledge pre-topology observations. Commit the complete RegionService fusion/scission plan atomically, including inherited uniqueness. Call invalidateMembership on the actor. Restart the fixture with the same store and confirm aliases, parent lineage, fact references and spent/possible uniqueness persist.
7. Saturate the observation budget with fixture actions; verify explicit degraded/unknown snapshots and bounded buffers. Remove the pressure, drain and verify recovery. Check no chunk loads/generation, trajectory archive, chat or client implementation class enters the observer.

Native results, store recovery and actual client behavior remain NOT_RUN until Work supplies runtime evidence. The pure suite covers the arithmetic and DTO/transaction plans, not fsync, crash recovery or real players.

Review iteration 3 regressions (native execution NOT_RUN): with an empty region index,
place an eligible player's campfire and publish the anchor lookup. Observe consecutive
regular player sampling ticks, leave for the configured independent-visit gap, then
return. Sealed raw windows must contain both occupied visits even though primary
region lookup stays empty. Between two snapshot requests, bring a second eligible
player inside proximity range for a regular census, then remove that player; the next
snapshot must credit only isolation since the first complete solo census after exit.
Repeat with spectator transition, skipped sampling and exhausted proximity budget;
none may bridge the missing interval. Call seal at a camp bucket boundary before its
boundary sample, verify the late sample is rejected/degraded, then acknowledge the
old window while the next bucket contains data; next-bucket data must remain intact.
The pure suite executes the same ObservationContinuity and CampSampler components
used by tick(), but does not execute Minecraft callbacks or stand in for these checks.

Iteration 5 native checks (NOT_RUN): issue a player/region-scoped request while many
connected players are outside its requested scopes. Instrument read-only primary
lookups and verify lookup count is bounded even when no player matches. Exhaust the
request deadline during filtering and during grouping; verify GROUP_MEMBERSHIP stays
missing and no partial groups/isolation are published. With player cadence 40, camp
cadence 400 and bucket 200, count sparse anchor lookup calls over 1200 ticks. Camp
scans must occur only at 400/800/1200, subject to rotation, and the first observation
must not award retrospective occupancy. Two consecutive later censuses contribute
400 ticks each split across 200-tick windows; frequent snapshot/seal requests must
not close camp windows ahead of the census that supplies them. Repeat with a cadence
not divisible by bucket size and with a scan budget that rotates across players.
