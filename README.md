# Proutlost WorldPrep

WorldPrep is a server-authoritative environmental compiler for handmade Minecraft terrain. It targets Minecraft 1.21.1, NeoForge 21.1.248, and Java 21.

WorldPrep is disabled by default. On a disposable map-development copy, enable it in the server config and use the admin-only `/proutlost worldprep` commands. See [the River guide](docs/WORLDPREP_RIVER_GUIDE.md) before use.

## Build

```bash
./gradlew build
```

A Java 21 toolchain and access to the NeoForge Maven repository are required.
