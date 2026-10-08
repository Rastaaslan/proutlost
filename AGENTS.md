# Proutlost engineering rules

- Target Minecraft 1.21.1, NeoForge 21.1.248, and Java 21 exactly.
- WorldPrep is deterministic and reversible; preview never modifies the world.
- Unknown or protected content is untouched by default.
- External mod content is registry/tag driven; core must not import external-mod implementation classes.
- Never replay full world generation.
- Never fabricate test results; compile against the real pinned APIs.
- World Director V1 implementation is governed by docs/DIRECTOR_V1_SPEC.md.
- Preserve the one-way WorldPrep -> PreparedWorldState -> Director boundary; Director never runs WorldPrep apply at runtime.
- Reusable pure planners may live in shared code when both WorldPrep and runtime services need the same deterministic logic.
- Director Core must remain mod-agnostic; optional adapters are isolated and semantic. Immersive Portals is outside Director integration scope.
- Story and season content must be data-driven where practical; do not invent production canon unless the task explicitly authorizes it.
- Default project-owner diagnostics and reports must respect the MINIMAL / PLAYER-SAFE spoiler policy.
