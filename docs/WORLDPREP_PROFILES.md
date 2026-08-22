# WorldPrep profiles

Profiles are data resources under `data/<namespace>/worldprep/profiles`. Registry content is referenced only by namespaced IDs. Each optional external ID must declare a vanilla fallback; unresolved candidate and fallback is a validation error that prevents apply. `proutlost:river_test` contains vanilla-only development values. Profile content, seed, registries, overrides, protections, and terrain contribute to the persisted plan fingerprint.
