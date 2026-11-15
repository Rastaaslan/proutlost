> HISTORIQUE de la première revue. Remplacé par CB-213.md ; ces résultats ne décrivent pas la reprise actuelle.

# CB-213 — QUESTSPEC-1.0 — livraison isolée, BLOQUÉE

## IMPLEMENTE_EN_SOURCE

Identité :

```text
SPEC_VERSION: QUESTSPEC-1.0
BASE_COMMIT: 9d895ecf335f2cc7770f7f89b328c48e5e3173d3
SOURCE_WORKTREE: /var/lib/codexbridge/worktrees/CB-213
TARGET_WORKTREE: /var/lib/codexbridge/worktrees/CB-213
BRANCH: codexbridge/CB-213
DEPENDENCIES: Java 21, Minecraft 1.21.1, NeoForge 21.1.248, Gson fourni par Minecraft
FILE_OWNERSHIP: src/main/java/fr/proutlost/quest/**; src/main/resources/data/proutlost/quest/**;
  src/test/java/fr/proutlost/quest/**; docs/quest/**; tools/verify-quest-offline.py;
  six lignes d'enregistrement dans Proutlost.java
NON_GOALS: implémenter le Director; modifier WorldPrep, FIXE, production ou les refs Git
ACCEPTANCE_CRITERIA: critères 1–5 du ticket, non atteints intégralement (voir BLOQUE)
VALIDATION_COMMANDS: voir COMPILE et TESTE
```

Le dépôt Debian est un dépôt WorldPrep ; il ne contient pas le runtime Director du FIXE. Le commit FIXE `c7eaf2d` et la branche `world-director-s2-fixe-integration-20261101` sont des références fournies par le ticket, non inspectées ici. Aucune équivalence de sources, intégration FIXE ou installation n'est revendiquée. La restriction historique « story out of scope » d'AGENTS.md est dépassée pour ce ticket par la demande explicite Quest Core ; les fichiers WorldPrep restent inchangés.

Source ajoutée :

- `quest/core/` : contrat `WorldFact`, définitions validées, quatre états, conservation des preuves anticipées, relevés immuables, partage nominatif par le témoin, publication des seules preuves déjà partagées lors de résolution, journal filtré, nouveautés par curseur, activité glissante sept jours, seuils de convergence, instantanés versionnés. `CampaignState` conserve camps, arrangements, choix, votes, sessions perceptives, cicatrice et historique d'épilogue.
- `quest/choice/CollectiveVote.java` : dossier, discussion, électorat figé actif + dossier consulté, confirmation unique, majorité absolue, seuil de participation, reconsultation, second tour et tirage reproductible avec engagement SHA-256 préalable et graine révélée après résultat.
- `quest/interaction/Interactions.java` : primitives de revalidation de camp/signal, comparaison préparée puis éprouvée, arrangement ordonné 3–5 emplacements, réponse partielle sans solution révélée.
- `quest/perception/PerceptionSession.java` : fragments ciblés, collecte volontaire, changement de positions, inversion des familles lors de répétition, reprise additive, sérialisation. **Transport de perception non raccordé au jeu.**
- `quest/runtime/` : SavedData global Overworld séparé de WorldPrep, API passive de faits serveur, journal textuel, partage, commandes de vote, commandes initiales et sites préparés immuables. Activité échantillonnée sans trajectoires complètes. Pas de génération, écriture de blocs, chargement forcé de chunks ou import d'implémentation de mod externe.
- `data/proutlost/quest/campaign.json` : **27 définitions** P0, P1/P2, P3, A1–A3, B1–B3, C1–C3, J1, D1–D3, E1–E3, R1, J2, F1–F5. Conditions alternatives de transmission, R1 visible uniquement après perturbation, titres/bilans sans nom Alpa avant convergence F1. Ces définitions ne constituent pas les scènes jouables complètes.
- `IslandConcept` : sept concepts stables. Le motif textuel de la fleur au pétale carré, les textes de journal et la réponse textuelle du signal sont des **placeholders originaux**, sans asset tiers. Aucun son, dessin, document biographique détaillé ou staging final n'est fourni.

Démarrage du prototype, exclusivement dans une copie de monde de test après construction et contrôle par l'opérateur :

1. Utiliser Java 21, Minecraft 1.21.1 et NeoForge 21.1.248. Ne pas installer deux JARs portant le même mod ID `proutlost`.
2. `proutlost-quest-server.toml` est un fichier SERVER, par monde. Le système est désactivé par défaut. Activer `enabled=true` dans la copie de test ; laisser `testCommands=false` pour les interactions normales. Configurables : contributions, ratio actif, circulation, discussion/vote, participation, activité/fréquentation du camp, quantités des signaux.
3. Un opérateur niveau 4 prépare des lieux existants puis, à chaque lieu, utilise `/proutlost quest bind <id> <kind>` avec `WRECK`, `SAFE_SHORE`, `CAMP`, `SIGNAL`, `OLD_CAMP`. Le système ne construit pas les lieux. Un identifiant déjà lié n'est pas déplaçable ; ajouter un autre identifiant. Le choix du camp ancien comme lieu intérieur relève de la préparation, pas d'une détection de géographie de l'île.
4. Réunir manuellement les testeurs près de l'épave. `/proutlost quest journal`, `news`, `examine wreck_motif`, puis `reach_shore` au rivage sûr. Pas de téléportation ou placement initial automatisé.
5. P1 : fréquentation et activité au site CAMP, lit, feu de camp allumé et conteneur rempli dans le voisinage ; `claim_camp` rescane avant de persister. P2 : au site SIGNAL, `activate_distress_signal` contrôle le dégagement de plusieurs feux ou d'un ensemble de laines de plusieurs couleurs, avec eau proche ou altitude. La réponse textuelle indique un secteur cardinal vers OLD_CAMP, sans coordonnées.
6. P3 : `examine camp_motif`, `share <observation_id> <recipients>` (sélecteurs Minecraft), `compare_prepare <first_id> <second_id>`, puis `compare_here` sur le lieu concerné. Les deux relevés doivent être accessibles au comparateur ; partager les relevés permet leur utilisation collective. Les identifiants apparaissent dans le relevé et le journal.
7. Votes accessibles : `/proutlost quest choice D3|F3 dossier`, puis `open`, `confirm <option>`, `close`, `status`. D3 exige les deux explorations. Ouverture/clôture respectent les délais persistés ; une consultation ne prouve pas une activité réelle. Les faits d'exploration et d'intervention des scènes intermédiaires restent à raccorder.
8. Tests administrateur uniquement : `testCommands=true` **et** permission 4 pour `/proutlost quest test fact <type>`. Le type doit exister dans la campagne. Cette commande injecte une preuve synthétique ; elle n'est ni une validation normale ni un test de gameplay.

Persistance : `<monde>/data/proutlost_quest_v1.dat`, enveloppe NBT schéma 1 et instantanés JSON internes schéma 1 ; aucun fichier WorldPrep existant n'est migré. Les nouvelles calibrations sont relues lors du chargement du monde. Les interfaces n'écoutent ni chat ni vocal.

## COMPILE

Commandes réellement exécutées dans ce worktree :

```sh
./gradlew --offline test build --no-daemon
# sortie 1 : cache partagé en lecture seule, verrou du wrapper impossible

GRADLE_USER_HOME="$PWD/.gradle/local-home" ./gradlew --offline test build --no-daemon
# sortie 1, y compris nouvelle tentative finale : Gradle ne peut pas créer
# FileLockContentionHandler : Could not determine a usable wildcard IP for this machine.

python3 tools/verify-quest-offline.py
# compilation javac --release 21 de TOUTES les sources main et test,
# contre le vrai JAR NeoForm compilé de NeoForge 21.1.248 présent en cache.
```

Le script vérifie la provenance 21.1.248 du manifeste de cache et échoue si ce cache manque. Les arguments complets javac sont dans `build/quest-offline/main.args` et `test.args`. Le fallback utilise le cache de dépendances disponible ; il ne remplace pas la résolution Gradle. Avertissements observés : API dépréciée dans le scan des sites, plusieurs providers SLF4J sur ce classpath de vérification.

Artefact de diagnostic, **pas un build Gradle attesté ni un JAR validé en jeu** :

```text
build/quest-offline/proutlost-0.1.0-CB213-verification-only.jar
build/quest-offline/artifact.sha256
```

Ce JAR inclut les classes compilées, ressources et métadonnées développées depuis le template existant ; aucune installation n'a été effectuée. Les journaux sont conservés dans `build/quest-offline/verification.log` et `gradle-failure.log`.

## TESTE

**Résultat final : `python3 tools/verify-quest-offline.py`, sortie 0 ; 105 tests réussis, 0 échec, 0 ignoré (38 Quest Core, 67 WorldPrep).** La première exécution des tests avait échoué sur `/tmp` en lecture seule ; le script utilise désormais un répertoire temporaire dans le worktree.

Le résultat final effectif et la liste de chaque test sont dans `build/quest-offline/verification.log` et `tests.tsv`. Les tests sont des **tests JVM**, avec des faits/sessions simulés pour le domaine ; ils ne prouvent pas l'exécution de scènes dans Minecraft. Les tests WorldPrep existants sont exécutés avec la même commande pour vérifier la non-régression JVM. Aucun test humain n'est approuvé et aucun GameTest/serveur Minecraft n'a été lancé.

Matrice de couverture (PASS = uniquement la portée JVM décrite ; PARTIEL/FAIL ne vaut pas acceptation de l'exigence entière) :

| Exigences / acceptation | Résultat démontré |
| --- | --- |
| INV-007, INV-008, INV-011 ; TEST-QCORE-001..006 | PASS domaine : journal sans quêtes verrouillées, actions visibles, preuves partielles/anticipées, résolution automatique, round-trip. UI indépendante de la sauvegarde. |
| KNOW-001..004, KNOW-006 ; TEST-KNOW-001..004 | PASS domaine : propriété des relevés, destinataires choisis, absence de fuite privée, preuves collectives disponibles après convergence. |
| KNOW-005 ; TEST-QCORE-007 | PARTIEL : nouveautés et progression commune sans refaire P0 ; parcours facultatif de rattrapage absent. |
| QCORE-001..006 | PARTIEL : modèle et transitions implémentés ; garantie d'action réellement disponible selon chaque site/scène non complète, reprise Minecraft non testée. |
| QCORE-007 | PARTIEL : portée serveur et contributions distinctes ; instances personnelles et groupes distincts non implémentées (définitions non-SERVER refusées). |
| QCORE-008/009 ; TEST-QCORE-008 | PASS sur contributeurs, ratio actif, fenêtre sept jours, délai ; autres gates complexes intégrés par faits de confiance. Calibration non validée en jeu. |
| TEST-EARLY-001..004 | PASS modèle : six ordres de branches, P1/P2 parallèles, conjonction A3+B3+C3, reprise entre branches. Groupes simulés par UUID/contributions, pas de groupes runtime. |
| INT-001/003/007/008 ; TEST-CAMP-001..003 | PARTIEL : primitives testées et adaptateurs initiaux compilés ; scans réels non testés en serveur, critères de contraste/côte encore simplifiés. |
| INT-002/004/005/006 ; TEST-C-003/004 | PASS primitives de collecte, séquence refusée/réussie, arrangements immuables ; emplacements physiques et commandes de séquence non raccordés. |
| PER-001..003 ; TEST-C-001/002 | PARTIEL : familles distinctes, mouvement réel représenté par positions serveur, redistribution, reprise et round-trip testés. Transport ciblé, équité entre scènes et intégration physique à compléter. |
| VOTE-001..006 ; TEST-VOTE-001..006 | PASS domaine : éligibilité, doublon, majorité, reconsultation sans tirage insuffisant, second tour/tirage persistant auditable, reconnexion. Interface textuelle compilée ; sessions en jeu non testées. |
| WDQ-001, INV-001/002, ARCH-001/002 | PARTIEL : API indépendante, AMBIENT ignoré par le moteur, HYBRID filtré. Aucun moteur Director n'est ajouté ou requis. |
| TEST-WDQ-001..003, B1-001/002 | FAIL intégration : indépendance structurelle couverte, mais aucune routine de monde réellement exécutée/consommée en Minecraft. |
| DATA-001/002, LANG-001/002 | PASS source : données JSON et concepts stables ; supports, séquences et assets complets absents. |
| TEST-MID-001/002 | PASS domaine : deux explorations avant dossier, vote rechargé, seule intervention gagnante valide D3, les deux options réussissent. |
| TEST-MID-003 | PASS immutabilité des relevés ; scène E2 et deux supports jouables absents. |
| TEST-MID-004/005 | PASS graphe : filtrer, amplifier stable, amplifier perturbé nécessitant reprise. R1 reste verrouillée sans perturbation. Scènes absentes. |
| TEST-FINAL-001/002 | PASS modèle : aucun UUID/connexion Alpa requis ; nom absent du journal avant résolution F1. |
| TEST-FINAL-003/004/005 | PARTIEL : convergence persistante, épilogue à partir des choix/camps/lieux réels du modèle ; exécution distribuée et toutes variantes du réveil en Minecraft non testées. |
| P0..F5, acceptation 3 | PARTIEL : toutes définitions présentes ; campagne jouable complète non intégrée. |
| Acceptation 1 | PARTIEL : cœur serveur et sauvegarde présents ; scopes et reprise gameplay incomplets. |
| Acceptation 2 | PARTIEL : journal textuel, premières interactions, primitives et votes ; pas de campagne complète ni de transport perceptif. |
| Acceptation 4 | FAIL : compilation et tests JVM effectifs, mais build Gradle bloqué avant compilation. |
| Acceptation 5 | PASS limites de livraison : fichiers ici seulement, procédure de portage ; aucun accès à FIXE/live. |

## BLOQUE

Le ticket n'est **pas terminé**. Deux catégories de manque distinctes :

1. **Environnement** : Gradle exige des capacités réseau locales indisponibles dans ce sandbox ; la politique ne permet pas l'escalade. Un opérateur/service de confiance doit exécuter le build Gradle exact dans un environnement autorisé. Aucun identifiant manquant et aucune décision produit ne sont nécessaires.
2. **Implémentation restante** : portées personnelles/groupes, garanties de disponibilité des actions, parcours de rattrapage ; scènes A1→F5, supports d'enquête et reprise ; capacité générale de routine B1 ; transport ciblé C1/C2, supports C3 et arrangement physique ; vrais états des lieux et interventions D3/R1/F4 ; sélection/transmission E3 ; mise en scène F2/F5 ; adaptation FTB si disponible. Les critères initiaux de signal doivent être renforcés au-delà du nombre de laines/couleurs et de l'eau proche. Les votes/session perceptives sont sérialisés mais le cycle SavedData complet n'a pas été éprouvé avec un serveur redémarré.

HANDOFF :

```text
public_interfaces_changed:
  WorldFact; QuestDefinition; QuestCore; CampaignState; CollectiveVote;
  Interactions; PerceptionSession; QuestRuntime.publish(MinecraftServer, WorldFact)
migrations_required:
  Aucune migration WorldPrep ; nouvelle sauvegarde proutlost_quest_v1.dat.
  Format expérimental schéma 1, non compatible avec une sauvegarde Quest inconnue du FIXE.
integration_dependencies:
  Build Gradle autorisé, revue des différences de Proutlost.java,
  mapping préparé des lieux, adaptateurs passifs du Director, validation Minecraft.
next_smallest_blocker:
  Faire exécuter Gradle par le service de confiance ; tester P0→P3 avec deux clients
  sur une copie de monde préparée, puis compléter les scènes avec des faits observables.
```

Portage ultérieur vers FIXE, à réaliser par l'opérateur (aucune étape exécutée ici) :

1. Examiner `build/quest-offline/CB-213.patch`, les nouveaux fichiers et les résultats. Le patch est indépendant des refs et contient uniquement ce ticket. Ne pas remplacer le JAR/runtime World Director existant par le JAR de diagnostic.
2. Dans un checkout d'intégration séparé choisi par l'opérateur, comparer les pins et la classe d'entrée à la version cible. Reporter les nouveaux dossiers ; adapter manuellement les six lignes d'enregistrement. Ne pas écraser les listeners, choix d'architecture ou dépendances du FIXE.
3. Vérifier `QuestRuntime.publish` sur le thread serveur ; réserver les faits NARRATIVE aux producteurs validés. Un événement AMBIENT reste entièrement exécutable sans appel au Quest Core. Aucun adaptateur ne doit injecter un fait de succès avant une interaction effectivement validée.
4. Exécuter `./gradlew test build` (Windows : `gradlew.bat test build`) puis les GameTests et un test multijoueur/restart dans une copie jetable. Le JAR Gradle attendu du projet actuel est `build/libs/proutlost-0.1.0.jar`, **non produit par Gradle ici**.
5. Conserver `enabled=false` jusqu'à validation du mapping et de la campagne ; ne jamais activer `testCommands` en production. En cas de retour arrière du prototype, désactiver Quest Core et conserver sa sauvegarde ; ne supprimer ni monde ni données existantes.
