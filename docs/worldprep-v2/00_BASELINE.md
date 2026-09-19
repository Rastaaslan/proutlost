# WorldPrep V2 baseline

- Date: 2026-09-19 UTC
- Base: `12ea7409aab76179319c3bd6c609038bf690951e` (merged PR #5)
- Java: OpenJDK 21.0.2
- Gradle wrapper: 8.14.4
- `./gradlew test --no-daemon`: PASS (`BUILD SUCCESSFUL`)
- `./gradlew build --no-daemon`: PASS (`BUILD SUCCESSFUL`)
- `./gradlew runGameTestServer --no-daemon`: PASS (`BUILD SUCCESSFUL`; all 15 required GameTests passed).
- Dedicated production server smoke: NOT TESTED.

The baseline contains the hardened page store, manifest, pipeline, canonical digest,
mutation semantics/groups, protection resolver, and enable gates from merged PR #5.
