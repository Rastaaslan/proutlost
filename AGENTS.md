# Proutlost engineering rules

- Target Minecraft 1.21.1, NeoForge 21.1.248, and Java 21 exactly.
- WorldPrep is deterministic and reversible; preview never modifies the world.
- Unknown or protected content is untouched by default.
- External mod content is registry/tag driven; core must not import external-mod implementation classes.
- Never replay full world generation.
- Never fabricate test results; compile against the real pinned APIs.
- World Director, season progression, and story are future subsystems and are out of scope.
