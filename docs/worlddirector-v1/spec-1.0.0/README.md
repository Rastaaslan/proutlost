# World Director — spécification technique 1.0.0

Proutlost Saison 2 · 23 octobre 2026 · **Conception uniquement**

Ce dossier constitue le contrat de la future implémentation du World Director. Il ne contient ni mod développé, ni preuve de compilation du mod, ni résultat de test Minecraft. Aucune intervention sur WorldPrep, MapDev ou la génération écologique n'a été effectuée.

## Autorité

1. La mission jointe « Texte collé(3).txt », sections 1–55, fixe le produit et prévaut sur les propositions antérieures.
2. La partie corrigée finale de « Concept.txt » fournit le contexte ; les passages antérieurs qu'elle corrige ne rétablissent ni un Director limité aux perceptions, ni un seul événement simultané, ni une interdiction générale des transformations substantielles.
3. `WORLD_DIRECTOR_TECH_SPEC.md` fixe les invariants et renvoie aux contrats spécialisés. Ceux-ci sont normatifs pour leur domaine.
4. Les exemples sont des fixtures de conception, pas le catalogue de contenu validé.
5. Tous les nombres de calibration sont dans `DEFAULT_FOR_TESTING.json`. Ils ne sont pas des décisions gameplay finales. Les versions de protocole et les formats binaires sont des décisions techniques, pas des calibrations.

## Parcours de lecture

| Livrable | Rôle |
| --- | --- |
| [WORLD_DIRECTOR_TECH_SPEC.md](WORLD_DIRECTOR_TECH_SPEC.md) | Invariants, algorithmes, choix techniques et critères de fin |
| [WORLD_DIRECTOR_ARCHITECTURE.md](WORLD_DIRECTOR_ARCHITECTURE.md) | Modules, ports, flux, frontières de threads |
| [WORLD_DIRECTOR_DATA_MODEL.md](WORLD_DIRECTOR_DATA_MODEL.md) | Types, états, identités, API Quest Core et WorldPrep |
| [WORLD_DIRECTOR_EVENT_CONTRACT.md](WORLD_DIRECTOR_EVENT_CONTRACT.md) | Format événement, primitives, chaînes et définitions complètes |
| [WORLD_DIRECTOR_PERSISTENCE_AND_RECOVERY.md](WORLD_DIRECTOR_PERSISTENCE_AND_RECOVERY.md) | WAL, checkpoints, ownership, reprise et rollback |
| [WORLD_DIRECTOR_PERFORMANCE.md](WORLD_DIRECTOR_PERFORMANCE.md) | Cadences, budgets, backpressure, dégradation |
| [WORLD_DIRECTOR_AI_LAYER.md](WORLD_DIRECTOR_AI_LAYER.md) | IA facultative, validation et fallback |
| [WORLD_DIRECTOR_ADMIN_V1.md](WORLD_DIRECTOR_ADMIN_V1.md) | Dashboard en jeu, protocole et commandes |
| [WORLD_DIRECTOR_TEST_PLAN.md](WORLD_DIRECTOR_TEST_PLAN.md) | Oracles et campagne future de qualification |
| [WORLD_DIRECTOR_IMPLEMENTATION_PLAN.md](WORLD_DIRECTOR_IMPLEMENTATION_PLAN.md) | Huit lots indépendants après gel du socle |
| [WORLD_DIRECTOR_SPEC_AUDIT.md](WORLD_DIRECTOR_SPEC_AUDIT.md) | Traçabilité des 55 sections et limites des vérifications |
| [WORLD_DIRECTOR_DEVELOPMENT_TICKET.md](WORLD_DIRECTOR_DEVELOPMENT_TICKET.md) | Mission suivante prête à transmettre, sans lancement |

`schemas/` contient les contrats JSON ; `examples/` contient des événements, chaînes, une proposition IA et un import de lieux. `validation/` contient la vérification statique de cette livraison. Le ZIP regroupe tous ces fichiers.

## Décisions techniques prises

Minecraft Java 1.21.1, NeoForge famille 21.1, JDK 21 ; moteur indépendant du chargeur ; serveur autoritaire ; acteur à écrivain unique ; observation incrémentale ; stockage Director par journal durable et checkpoints dans le monde ; attachments NeoForge comme marqueurs locaux ; bus transactionnel avec outbox et inbox ; dashboard Minecraft en jeu ; IA via port asynchrone facultatif. Aucun service web, Quest Core, WorldPrep, Twitch, YouTube ou modèle IA n'est nécessaire au fonctionnement de base.

La version binaire exacte du dépôt existant n'a pas été inspectée : la future gate G0 doit verrouiller ses versions et vérifier les signatures des adaptateurs. Cela ne permet pas de revendiquer une intégration déjà réalisée.

## Transmission

Transmettre le dossier entier au fil d'implémentation et utiliser `WORLD_DIRECTOR_DEVELOPMENT_TICKET.md`. Les paramètres de danger, fréquence, durée et ampleur restent en statut de test jusqu'à calibration produit. Une nouvelle contradiction fonctionnelle suit le format `TECHNICAL_CONFLICT` ; elle ne justifie pas de simplifier silencieusement le gameplay.
