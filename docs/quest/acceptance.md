# CB-213 — matrice de preuves de la reprise

PASS signifie uniquement que la vérification indiquée a été exécutée avec succès ; ce n'est pas une certification de l'exigence entière en jeu. PARTIEL signifie source raccordée et preuves limitées ; FAIL/BLOQUÉ interdit l'acceptation globale. Les tests de graphe injectant des faits ne sont jamais présentés comme tests d'interaction Minecraft.

| Exigences | Source / preuve | Verdict et limite |
| --- | --- | --- |
| TEST-QCORE-001..005, INV-007/008/011, QCORE-001..005 | QuestCoreTest, ScopedQuestTest, ActionReadinessTest | PASS JVM : états, visibilité, preuves anticipées, résolution factuelle. PARTIEL disponibilité réelle de tous les sites selon mapping. |
| TEST-QCORE-006, QCORE-006 | QuestSavedDataTest ; GameTest realCommandHandlersAcceptGeneratedEvidenceIds | PASS JVM NBT + SavedData serveur sur disque et rechargement dans un second processus Minecraft. Voir aussi le parcours serveur embarqué de la reprise 5 ci-dessous ; rendu multiclient non validé. |
| TEST-QCORE-007, KNOW-005, QCORE-007 | ScopedQuestTest, QuestCoreTest | PASS JVM : scopes distincts, nouveaux membres, nouveautés, rattrapage facultatif sans fuite privée. Équipes scoreboard raccordées ; migration de toutes configurations d'équipes non éprouvée. |
| TEST-QCORE-008, QCORE-008/009, INV-010 | QuestCoreTest, CampaignActionProgressionTest | PASS JVM : contributions distinctes, ratio actifs, circulation, fenêtre sept jours ; calibration jeu non validée. |
| TEST-KNOW-001..004, KNOW-001..006 | ScopedQuestTest, QuestCoreTest, InteractionFlowTest | PASS JVM : partage choisi, collecte volontaire, établi différent de privé, versions immuables, rattrapage. |
| TEST-VOTE-001..006, VOTE-001..006 | CollectiveVoteTest, CampaignStateTest | PASS JVM : éligibilité, doublon, majorité, quorum, reconsultation, second tour/tirage, persistance. Commandes serveur exercées dans CampaignGameTests avec deux électeurs, rejet du doublon et restauration des bulletins ; rendu multiclient non validé. |
| TEST-WDQ-001..003, WDQ-001..003, B1, ARCH-001/002, INV-001/002 | InteractionFlowTest + module ambient indépendant | PASS JVM : routine sans Quest Core, même replay consommé par B1. PARTIEL : cadence réelle et effets ambiants hors quête non testés avec clients. Aucun accès au Director FIXE. |
| TEST-CAMP-001..003, INT-007, P1 | InteractionsTest, CampaignStateTest | PASS JVM primitive revalidation/confirmation/persistance ; adaptateur lit/feu/conteneur/activité exercé par le parcours serveur CampaignGameTests. |
| INT-008, P2 | DistressScanTest ; GameTest actualTerrainScanRejectsPilesAndAcceptsSpacedFires | PASS JVM : bassins/amas/feux/côte/SOS ; GameTest terrain réel refuse amas et feu couvert, accepte feux espacés sur plateau. La reconnaissance de toutes côtes réelles reste une calibration. |
| INT-001/003, P3 | CommandParsingTest, InteractionFlowTest ; GameTest realCommandHandlersAcceptGeneratedEvidenceIds | PASS : Brigadier et commandes serveur avec vrais identifiants générés contenant « : », lecture, partage, préparation/comparaison, fait produit. Ce test ne valide pas seul toute progression P0→P3. |
| TEST-EARLY-001..004, P0..J1 | QuestCoreTest, InteractionFlowTest | PASS domaine : graphe, ordres de branches, groupes UUID, producteurs d'enquête, synthèse J1 ; PARTIEL E2E multiclient des branches. |
| INT-002/004/005/006, TEST-C-003/004 | InteractionsTest, InteractionFlowTest | PASS JVM : séquence correcte/incorrecte, complémentarité, arrangement enregistré et comparé. Adaptateurs conteneurs/sons raccordés, perception client non évaluée humainement. |
| TEST-C-001/002, PER-001..003, INV-003/009 | PerceptionReplacementTest, InteractionFlowTest, QuestSavedDataTest | PASS JVM : deux familles, mouvement, redistribution, remplacement d'absent et restauration. PARTIEL équité à long terme entre plusieurs scènes et qualité perceptive client. |
| TEST-MID-001/002, D3 | CampaignActionProgressionTest, CampaignStateTest | PASS domaine : deux explorations, vote, seule option gagnante, deux interventions et contributions, réparation/détour. Commandes vérifient planches et état du point ; parcours serveur CampaignGameTests documenté ci-dessous. |
| TEST-MID-003, E2 | InteractionFlowTest, QuestCoreTest | PASS JVM : deux documents examinés/comparés, ancien relevé conservé. |
| TEST-MID-004/005, E3/R1 | CampaignActionProgressionTest | PASS domaine : filtrages multiples, amplification, repli/stabilisation/réparation puis reprise nécessaire ; R1 conditionnelle, choix persistant. Perturbation audiovisuelle à valider en jeu. |
| J1/J2, F1 | Investigation, InvestigationCommands, InteractionFlowTest | PASS domaine : synthèses utilisables depuis carnet, prérequis requis ; pas de chasse ajoutée pour F1. |
| TEST-FINAL-001/002, INV-004..006 | QuestCoreTest | PASS domaine : aucune connexion Alpa requise, pas de révélation du nom avant F1, canon des bilans. |
| TEST-FINAL-003..005, F2..F5 | CampaignActionProgressionTest, CampaignStateTest | PASS domaine : quatre combinaisons passage/mémoire, transmission, contributions persistantes, épilogue historique et réveil certain. PARTIEL conséquences physiques complètes et E2E multiclient ; staging final placeholder. |
| DATA-001/002, LANG-001..003, MAP-001/002 | JSON campagne/enquête/perception, PreparedSites | Source : 27 définitions, sept concepts, sites réutilisables, supports et séquences. Mapping exact opérateur ; pas de WorldPrep ajouté. |
| Acceptation 1 | Cœur/scopes/persistance implémentés | PARTIEL : tests domaine et sauvegarde ciblée ; tous états de campagne après restart Minecraft non éprouvés. |
| Acceptation 2 | Journal/commandes/interactions/perceptions/votes raccordés | PARTIEL : pas de test humain/multiclient intégral ; journal textuel de repli. |
| Acceptation 3 | Toutes les phases raccordées dans la source | PASS parcours serveur embarqué documenté ci-dessous ; PARTIEL pour le rendu et la validation multiclient humaine. |
| Acceptation 4 | javac réel, JVM et GameTests directs | FAIL/BLOQUÉ : Gradle échoue avant compilation faute de sockets locaux autorisés. |
| Acceptation 5, CB-001..005 | Worktree isolé, inventaire, patch et procédure | PASS limites de livraison : aucun reset/commit/merge/push, FIXE/live non modifiés ; portage non exécuté. |

Résultats élémentaires : `build/quest-offline/tests.tsv` (139 tests JVM : 72 Quest, 67 WorldPrep). Les GameTests directs utilisent le JAR de diagnostic compilé contre les API réelles, pas des stubs. Leurs journaux et arguments de lancement sont dans `build/quest-game-test/`, chemin courant dans `last-run.txt`. Le test ciblé de redémarrage recharge le même monde dans un second processus et vérifie les données issues du premier. Le mode `--campaign --restart` est distinct et couvre le graphe complet, voir reprise 5 ci-dessous.

## Corrections de revue 2

- INV-010 / TEST-QCORE-008 : PASS JVM `shorelineParticipationCannotRushLaterConvergence` — dix actifs avec contribution P0 ne satisfont pas J1 ; il faut des synthèses J1 distinctes. Aucun ancêtre n'est compté automatiquement. `absentActivePlayerCanAcknowledgePublishedChoiceWithoutChangingIt` vérifie la reprise F3 sans réécrire le vote.
- KNOW-006 : PASS JVM `earlyRuntimeDocumentIsRetainedWithoutRevealingLockedPage` — véritable producteur `Investigation.examine`, preuve anticipée, page cachée puis preuve accessible après déblocage.
- KNOW-003 / INV-009 / QCORE-006 : PASS JVM `unrecordedPerceptionSurvivesDisconnectAndCanBeVoluntarilyRecovered` — restauration du fragment, aucune collecte silencieuse, collecte volontaire et confidentialité. PASS GameTest `lostPerceptionMessageCanBeRecalledWithoutAutomaticCollection` exécuté aux deux démarrages : vraies commandes après restauration, absence de collecte automatique puis collecte volontaire. Prérequis injectés explicitement dans cette fixture ciblée.
- F4-001 : PASS JVM `finaleRejectsMissingUnrelatedAndIncompleteInputs` — absence, origines incompatibles, séquence incomplète et mauvais ordre refusés avant les faits de réussite. Les quatre variantes existantes passent par les nouvelles validations.
- F4-002 : PASS JVM dans les quatre variantes de `allChoiceVariantsUseConfirmedWorkAndPersistentFinalContributions` — points retenus, première vérification restaurée puis deuxième vérification, convergence indépendante du chargement simultané. Ce n'est pas une preuve multiclient intégrale.

À la reprise 3, le build Gradle et le parcours Minecraft P0→F5 complet restaient BLOQUÉS/non démontrés ; les tests ciblés ne remplaçaient pas cette acceptation.

Résultat final reprise 3 : 136 tests JVM réussis (69 Quest, 67 WorldPrep), 22 GameTests réussis à chacun des deux démarrages (3 Quest, 19 WorldPrep). Gradle sortie 1 avant compilation. Journaux : `verification.log`, `minecraft-verification.log`, `gradle-failure.log` dans `build/quest-offline/`.

## Corrections de revue 3

`ReviewFourRegressionTest` : 3 tests JVM supplémentaires PASS. Numéro d'essai conservé par un remplacement et par restauration ; anciens fragments ordinaires migrés à partir de leur identifiant, reprise historique inconnue refusée pour C2 ; relevés A3/E1 visibles après restauration, partagés nominativement, puis accessibles à l'absent après résolution, sans divulguer le relevé privé témoin. Les conditions de résolution des JSON restent identiques.

Le GameTest de reprise est étendu à la vraie commande `compare_perceptions` : après remplacement au premier essai puis répétition, comparer uniquement les anciens fragments doit retourner 0 sans changer l'état canonique ; collecter le fragment du nouvel essai permet ensuite la comparaison. La préparation des prérequis reste une fixture explicite ; aucun parcours complet P0→F5 n'est revendiqué.

Résultat final reprise 4 : 139 tests JVM réussis (72 Quest, 67 WorldPrep), 22 GameTests réussis aux deux démarrages. Le scénario de remplacement/C2 est intégré au GameTest de reprise existant. Les 27 conditions de résolution ont été comparées au JAR vérifié de la reprise 3 : identiques ; seules les listes observations de A3 et E1 ont changé. Gradle reste en échec avant compilation.


## Reprise 5 — parcours des commandes serveur

`CampaignGameTests.campaignAcrossProcessRestart` ne modifie ni le graphe ni les gates, n'appelle pas `QuestCore.accept` et n'utilise pas les commandes de secours. Les deux joueurs embarqués confirment camp et signal, examinent/partagent/comparent les preuves, enregistrent les perceptions, changent de position, reproduisent la séquence, synthétisent, votent et contribuent aux travaux et à la finale. B1 reçoit une véritable manifestation du moteur ambiant autour du feu. Les variantes détour réalisent A après B/C1, dans le second processus.

Les scènes et les sites physiques sont des fixtures préparées dans un monde jetable. Les sons sont envoyés par le serveur ; leur perception effective et le rendu ne sont pas évalués par ces connexions embarquées. Les délais sont calibrés à une seconde, jamais désactivés ; les convergences requièrent deux contributeurs. Les deux acteurs se reconnectent dans le second processus ; un témoin se reconnecte aussi avant sa première collecte C1. SavedData est relu depuis son fichier pendant les votes, les transmissions et F4, puis après F5. Aucun état de progression n'est réécrit par le test.

La validation de chaque exécution exige le marqueur GameTest **et** une sortie 0 des deux processus. Les tentatives en timeout d'arrêt ne comptent pas comme PASS. Le harnais retire ses joueurs et tickets puis laisse achever les travaux de chunks avant l'arrêt du serveur ; les journaux d'échec sont conservés dans `.gradle/review5-*`.

Résultats exécutés de campagne (reprise 5), chacun avec sortie 0 et un GameTest réussi à chacun des deux processus :

| Variante | Ordre des branches | Transmission / conséquence | Résultat |
| --- | --- | --- | --- |
| repair_filter_unite | A puis B/C | Filtrer, trois activations | PASS serveur, F5 restauré |
| detour_filter_distribute | B/C1 puis A après redémarrage | Filtrer, trois activations | PASS serveur, F5 restauré |
| repair_amplify_stable_distribute | A puis B/C | Amplifier sans perturbation | PASS serveur, F5 restauré |
| detour_amplify_disturbed_unite | B/C1 puis A après redémarrage | Amplifier, R1, repli/réparation/reprise | PASS serveur, F5 restauré |

Preuves exactes et empreinte commune : `build/quest-offline/campaign-results.json` et `campaign-*.log`. Ces parcours apportent une preuve serveur pour TEST-EARLY-001/002/004, TEST-CAMP-003, TEST-C-001..004, TEST-MID-001..005, TEST-FINAL-001/003..005 et les restaurations QCORE-006. Les deux acteurs alimentent les branches et convergences ; l'absence avant collecte et le processus interrompu à C1 sont effectivement repris. La matrice JVM reste nécessaire pour les grandes populations, les portées personnelles/groupes, tous les cas d'égalité/quorum et la confidentialité exhaustive. Aucun test humain n'est déclaré réussi.

Le build Gradle reste FAIL/BLOQUÉ : `GRADLE_USER_HOME="$PWD/.gradle/local-home" ./gradlew --offline test build runGameTestServer --no-daemon`, sortie 1 avant compilation (`FileLockContentionHandler`, wildcard IP indisponible). Le service de confiance doit encore produire un build vert et son JAR. L'acceptation globale demeure BLOQUÉE.

Non-régression réexécutée sur le même JAR : `python3 tools/run-quest-gametest-offline.py --restart`, sortie 0 ; 22 GameTests réussis au premier démarrage et 22 au second (19 WorldPrep, 3 Quest). Journal : `build/quest-offline/minecraft-verification.log`.

Actualisation après reprise timeout : build Gradle **BLOQUÉ**, commande avec `--stacktrace` sortie 1 avant compilation. Cause racine vérifiée : création de socket refusée dans `NetworkInterface.getAll`; écriture/verrou de fichier local réussis. Voir `build/quest-offline/gradle-diagnostic.log` et `environment-probe.log`. Procédure de service préparée : `bash tools/verify-quest-trusted.sh` (syntaxe vérifiée, exécution Gradle non réussie ici). Résultats fonctionnels précédents conservés, aucun nouveau PASS de gameplay ni de multiclient revendiqué.


## Qualification Windows FIXE — 8 novembre 2026 (postérieure aux reprises Debian)

Le blocage Gradle du sandbox Debian n'est **pas** un échec du code sous Windows : sur FIXE, `gradlew.bat --offline test build --no-daemon --console=plain` retourne 0 et produit `build/libs/proutlost-0.1.0.jar`, SHA-256 `7244E86BA7B4F79F8FF53FE42BC6939852FC9ADA30290999CA29273EF4E77D36`. Le GameTest standard retourne 0 avec **22/22** après adaptation minimale de la fixture au mod `proutlost_director`. Les quatre parcours P0→F5 sont exécutés via Gradle avec un redémarrage réel par variante (8 exécutions : code 0, 1 GameTest PASS chacune). Les détails, les commandes et les limites se trouvent dans `docs/quest/acceptance-windows-2026-11-08.md` et `build/quest-windows-build-verification.log`.

**Reste BLOQUÉ pour acceptation humaine complète :** contrôle multiclient réel (sons/perceptions ciblées, carnet, votes, présentation et finale). Les clients du GameTest sont embarqués et ne constituent pas une validation graphique ou acoustique. Pas de déploiement live.

## Vérification complémentaire Windows FIXE + Laptop — 09/11/2026 02:52–03:05

Build Gradle `--offline test build` : **PASS sortie 0** (journal `build/quest-qa-20261109-gradle-r2.log`). `--offline runGameTestServer` : **PASS sortie 0, 22/22 requis** (journal `build/quest-qa-20261109-gametest-r2.log`). Les deux JARs ont les mêmes SHA-256 que le 08/11 et sont présents sur le client QA Laptop. Serveur QA isolé :25577 et deux vrais clients NeoForge 21.1.248 **connectés simultanément**. Pour Acceptations 1/2/3 : **PARTIEL**, car la commande journal sur les clients n'a pas montré de pages dans le monde QA sans sites préparés ; perceptions ciblées, confidentialité, échanges, votes et reprise avec progression sont **NON TESTÉS** graphiquement. Suite selon `manual-two-client-qualification.md`. Compte-rendu sans spoilers dans `qa-dual-client-2026-11-09.md`. Aucune acceptation globale/100 % revendiquée.

## QA complémentaire 09/11/2026 — 03:49–04:05

PASS environnement graphique FIXE et Laptop : deux clients connectés simultanément au serveur QA isolé :25577, deux JAR obligatoires sur chaque client et empreintes SHA-256 concordantes. PASS de bout en bout pour la seule commande neutre /help : sortie chat réelle consignée par les deux clients. Les saisies de 03:00 commençant par « t » étaient de simples messages de chat et ne sont pas des tests du journal. Les commandes journal et catch_up ont été tentées avec l'outil corrigé ; aucune page observable, donc acceptation 2 toujours PARTIELLE. Sites et progression QA à préparer avant confidentialité, partages, votes, reconnexions et persistance. Build Gradle PASS sortie 0 (build/quest-qa-20261109-gradle-r3.log) et GameTests PASS 22/22 sortie 0 (build/quest-qa-20261109-gametest-r3.log). Pas de 100 % ni de résultat humain perceptif revendiqués. Rapport détaillé : qa-dual-client-2026-11-09.md.

Préparation complémentaire : ops.json du **seul serveur QA isolé** préconfiguré pour l'acteur FIXE Dev avec sauvegarde de l'état original. La modification n'est pas active tant qu'un redémarrage QA contrôlé n'a pas été réalisé ; aucune qualification de scénario supplémentaire n'en résulte. Voir qa-dual-client-2026-11-09.md.


## Qualification Windows complémentaire — 09/11/2026 10:37–10:55 (sans spoilers)

**PASS QA ciblés :** deux clients graphiques réels NeoForge 21.1.248 reconnectés et deux JARs identiques par empreinte SHA-256 (Quest 540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A ; Director FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C). Client Laptop remplacé proprement, relancé et reconnecté à 10:44:49. Journal texte réémis sur les deux GUI : en-têtes techniques P0/P1/P2/P3/A1/C1/C2/C3 dans les deux logs. catch_up invoqué après reconnexion. Tentative d'enregistrement de fragment d'un témoin tiers rejetée depuis Laptop. NBT Quest QA présent sur disque, copie décompressée et vérifiée. Build Gradle r4 et GameTests r4 : codes 0, 22/22 (08:14, vérifiés sans relance pendant cette passe).

**PARTIEL / NON VALIDÉ :** confidentialité perceptive complète, acoustique, partage et votes en jeu à deux, restauration après redémarrage serveur, raccordement factuel Director réel, E2E graphique complet. Aucun 100 %, aucune modification du live. Preuves et limites : docs/quest/qa-dual-client-2026-11-09.md.



## QA FIXE + Laptop — reprise persistante après redémarrage réel (09/11/2026, 12:17–12:31)

**PASS ciblés nouvellement démontrés :** arrêt serveur de test propre après `/save-all flush` (12:21:55), sauvegarde de toutes les dimensions puis `/stop` (12:22:15) ; backup froid du seul monde QA ; redémarrage serveur sur le même monde (12:23:15) ; les deux clients graphiques reconnectés (12:27:03 / 12:27:47). Le fichier `proutlost_quest_v1.dat` conserve son SHA-256 `0393E21A70C8AB7090772A11198C4DE08068CD5C1DF892B4E8C2394D935BE77D` et les deux vrais clients affichent à nouveau exactement huit en-têtes techniques de carnet (`P0`, `P1`, `P2`, `P3`, `A1`, `C1`, `C2`, `C3`) après commandes réellement exécutées (12:28:14 / 12:28:19). Cette preuve sur deux clients comble le volet *persistance du journal après redémarrage QA*, mais **ne valide pas** les conséquences de choix, votes, confidentialité globale, acoustique ou progression P0→F5 en GUI.

**Build/GT courants :** Gradle r5 sortie 0 (`BUILD SUCCESSFUL in 6s`), GameTests r5 sortie 0 (22/22 et `BUILD SUCCESSFUL in 29s`). JAR Quest SHA `540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A` et Director SHA `FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C` identiques sur FIXE et Laptop QA. Voir `qa-dual-client-2026-11-09.md` pour horaires, logs, empreintes et limites. Aucun changement live, aucune clôture globale.

## QA confidentialité et partage FIXE + Laptop — 09/11/2026 12:45–12:55 (sans spoilers)

**PASS cible de nouveaux points de KNOW-003/004 et INT-002 :** deux journaux issus de deux vraies GUI montrent chacun son propre fragment prive trial 3 et ne montrent pas celui de l'autre avant partage (12:50). Un partage explicite unique FIXE → Laptop donne au Laptop l'acces a ce seul fragment (12:51:53). Deux actions de non-proprietaire (`record_perception` et nouveau `share`) sont refusees (12:52:58/12:53:01). Le journal FIXE confirme a 12:55:18 que le fragment prive du Laptop reste invisible. `/save-all flush` 12:54:15 et lecture NBT montrent 8 observations, 7 partages contre 6 avant action ; l'operation est serialisee sur disque. **PASS ciblés, pas validation exhaustive de confidentialite**, pas de test acoustique humain, pas de resultat de vote reel, pas de preuve de parcours E2E en GUI ni de fait issu de Director. Build/GT r5 avant ce passage : sorties 0, 22/22 ; aucun code modifie. Voir `qa-dual-client-2026-11-09.md` et `build/quest-private-qa-20261109/qa-evidence/qa-privacy-share-20261109-1255.json`. L'acceptation globale reste **PARTIELLE** ; aucune livraison live.
## Passe QA du 09/11/2026, 13:01-13:10 (FIXE + Laptop, sans spoilers)

- **QA-SHARED-RESTART PASS cible :** `save-all flush` accepte par le serveur de test a 13:01:33, `stop` propre a 13:02:01 ; port QA libere, tous les mondes sauvegardes. Copie froide du seul monde QA : `qa-evidence/qa-world-cold-post-share-20261109-1302.zip`, SHA-256 `449B5FC1DFCF09C8E749794A3A459BCC84AD17845C312ADF445018E63EDDC8BBF`. NBT Quest avant restart SHA-256 `391F7D5DF9921917EE48681A0A44D2132E404AA2DB8991E7BDD772FBF7AA8F24`.
- **QA-SERVER-RESTART PASS cible :** redemarrage du meme serveur QA FIXE Tailscale :25577 avec Gradle, `Done` 13:02:58 ; les deux clients GUI autorises reconnectes manuellement via l'adresse QA verifiee, entrees `joined the game` a 13:07:46 et 13:07:51, deux sockets Established.
- **QA-JOURNAL-AFTER-SHARE-RESTORE PASS cible :** commandes `/proutlost quest journal` reelles sur FIXE a 13:08:37 et Laptop a 13:08:41. Huit en-tetes techniques sur chacun ; FIXE voit son propre fragment mais PAS celui toujours prive de Laptop ; Laptop voit le sien ET le fragment precisement partage par FIXE. Aucune erreur de commande. Ceci prouve la persistance de la selection de partage et la non-fuite ciblee apres rechargement du monde.
- **QA-SAVEDATA-RESTORE PASS cible :** lecture du fichier NBT post-restart valide (schema campagne 1, session perception essai 3) ; les deux fragments existent et sont enregistres ; le fragment FIXE a 1 destinataire, celui du Laptop en a 0. Empreinte post-restart NBT `2CEBF5BC9549247FE438898A23CB23551D5331140A034A7D4E60F8267E2BBF18` (ne pas attendre identite binaire des sauvegardes apres nouveau passage). Preuve structuree : `qa-evidence/qa-share-restart-20261109-1310.json`.
- **NON VALIDES :** perception sonore par humain, votes D3/F3 en GUI, campagne E2E graphique, fait reel World Director -> Quest Core WDQ-001..003. Aucune revendication de 100 %. JARs Quest/Director inchanges et harmonises, build et GameTests r5 precedemment verts (non relances durant cette passe). Aucune modification des sources, du worktree tracked, du serveur live ou des installations normales. Controle horaire maintenu.
## Passe QA du 09/11/2026, 14:30-14:41 (FIXE + Laptop, sans spoilers)

- **QA-ENV/DISTRIBUTION PASS :** serveur QA isole FIXE Tailscale :25577 en ecoute, exactement deux connexions graphiques etablies. JAR Quest SHA-256 540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A identique sur FIXE/Laptop ; Director SHA-256 FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C verifie sur FIXE et deja verifie sur Laptop.
- **QA-RECALL-POSTRESTART-2GUI PASS cible :** commande recall_perception executee depuis la veritable GUI QA FIXE (reponse serveur journalisee a 14:36:40), puis depuis la GUI QA Laptop (reponse journalisee a 14:38:17). La re-emission d'une instruction technique de collecte volontaire est prouvee sur chaque GUI. Aucune collecte automatique ne doit etre inferee du rappel.
- **QA-NO-AUTO-COLLECT PASS cible :** save-all flush QA accepte apres ces deux rappels. SavedData NBT schema 1 reste a 8 observations, 7 partages, 12 faits, 6 etapes resolues et 2 acteurs actifs : nombres inchanges depuis le snapshot avant ces commandes. Aucun fait ni partage supplementaire cree par simple rappel. Pas de validation auditive humaine.
- **QA-BUILD-R6 PASS :** Gradle --offline test build code 0, BUILD SUCCESSFUL. GameTests --offline runGameTestServer code 0, 22/22. Les deux JARs ont leurs empreintes anterieures.
- **Toujours NON VALIDES :** effet auditif percu humainement par chaque client ; votes D3/F3 des deux GUI ; trajet graphique integral ; fait reel producteur World Director -> Quest Core avec WDQ-001..003. Les commandes synthetiques de test ne remplacent pas un tel fait reel. Pas de 100 %.
- **Protection :** aucun patch source, aucun CB-213 relance, aucune modification live ou instance normale. Scripts ajoutes uniquement dans la QA isolee. Preuve JSON : build/quest-private-qa-20261109/qa-evidence/qa-recall-postrestart-20261109-1441.json. Logs : clients QA FIXE/Laptop et build/quest-qa-20261109-*-r6.log/.exit.

## Passe QA du 09/11/2026, 15:13-15:17 (FIXE + Laptop, sans spoilers)

- **QA-ENV/DIST PASS :** Serveur QA seul en écoute sur Tailscale 100.126.186.44:25577, deux connexions graphiques simultanées. Les JAR Quest et Director correspondent toujours aux SHA-256 déjà vérifiés ; aucune synchronisation supplémentaire requise.
- **QA-VOTE-STATUS-NO-SESSION-2GUI PASS ciblé :** Les commandes techniques `/proutlost quest choice D3 status` et `/proutlost quest choice F3 status` ont été émises réellement via les deux GUI QA (4 commandes, 4 rejets contrôlés) ; chaque acteur reçoit la réponse générique prévue en l'absence de session de vote préparée. Ce contrôle ne démontre NI ouverture NI confirmation NI dépouillement du vote.
- **QA-VOTE-NO-MUTATION PASS :** Après `/save-all flush` confirmé à 15:16, le NBT Quest du seul monde QA reste valide et identique en SHA-256 (2CEBF5BC9549247FE438898A23CB23551D5331140A034A7D4E60F8267E2BBF18). Comptage `votes=0`, `facts=12`, `observations=8`, `shares=7`, `resolved=6`, inchangé. Aucun dossier, choix ou progression créé par les commandes de statut.
- **QA-BUILD/GT PASS récent vérifié :** r6 sur FIXE (14:39) : Gradle code 0, GameTests code 0, 22/22 réussis ; pas de nouveau build ni de modification source pendant ce passage.
- **NON VALIDÉS :** votes D3/F3 réels (`dossier/open/confirm/close`), WDQ-001..003 avec producteur Director réel, audio humain et parcours graphique E2E. Ne pas convertir ce sous-test en PASS des votes.
- **Protection :** scripts et preuve exclusivement dans l'instance QA ; aucune intervention sur le monde/serveur live, aucune modification Git source, aucun nouveau job CB-213. Preuve `build/quest-private-qa-20261109/qa-evidence/qa-vote-status-20261109-1516.json`.
## Verification technique cible 09/11/2026, 18:41-18:46

**PASS :** Gradle hors ligne code 0, 23 GameTests sur 23 code 0, deux empreintes SHA r4 inchangees et coherentes sur disque FIXE/Laptop QA, serveur QA isole FIXE en ecoute avec une GUI FIXE connectee.

**NON VALIDE :** le client FIXE en memoire predatant r4, la GUI Laptop QA fermee pendant ce passage, les votes multiclients complets, l'audio humain, P0-F5 en E2E graphique et WDQ-001..003 avec vrai producteur durable Director. Le flag Director nativeLab est absent du serveur QA ; le GameTest de jointure ne cree qu'une preuve synthetique. Aucune modification source, live ou installation normale. Preuves : `qa-dual-client-2026-11-09.md`, `wdq-001-003-verification-2026-11-09.md` et `build/quest-private-qa-20261109/qa-evidence/qa-checkpoint-20261109-1846.json`. Acceptation globale toujours **PARTIELLE**.


## Controle QA 09/11/2026, 19:43–19:49 (sans spoilers)

JARs Quest/Director FIXE et Laptop QA verifies sur leurs empreintes r4 (4293FBE8...DD6B7E, 01673C9A...334774). Serveur QA :25577 en ecoute, 1 GUI FIXE presente mais ancienne (chargee avant r4) ; client QA Laptop ferme pendant qu'un autre jeu non-QA etait actif. Dernier build Gradle/GT du candidat : PASS 0/0, 23/23, 18:45. Preparation d'un LAB natif strictement separe port 25578, configuration Gradle PASS, mais LAB **NON DEMARRE**. Audit : **0 producteur** du prefixe explicite Quest dans src/director* ; WDQ-001..003 n'a pas de preuve J04 reelle vers Quest Core. Votes multiclient, audio humain, E2E deux GUI r4 **NON VALIDES**. Voir wdq-001-003-verification-2026-11-09.md et qa-dual-client-2026-11-09.md. Acceptation globale **PARTIELLE**, aucun deploiement live, Git non commite preserve.


## Contrôle du 09/11/2026 21:17 (PASS/FAIL techniques, sans spoilers)

Gradle `--offline test build` **PASS code 0** ; `runGameTestServer` **PASS code 0 (23/23)**. Les deux JARs r4 conservent des SHA-256 identiques sur FIXE/Laptop. Serveur Quest QA :25577 toujours opérationnel, Laptop GUI QA fermé ; aucun nouveau résultat deux GUI r4. Essai Director LAB :25578 (jetable, whitelist, sans joueur) : démarrage serveur **PASS**, ouverture stockage J04 **FAIL_CLOSED** par `AccessDeniedException` sur répertoire `checkpoints` (synchronisation de répertoire Windows). Aucun événement natif ni rendu perceptif n'a été prouvé. WDQ-001..003 réel, vote D3/F3, audio humain et P0→F5 graphique = **NON VALIDÉS**. Aucun déploiement live ni source modifié. Preuve : `qa-evidence/qa-native-lab-fsync-20261109-2117.json` sous le serveur QA.

## Extension laboratoire neutre du 09/11/2026 21:56

**PASS headless uniquement :** deux tests JUnit `NeutralQuestSandboxTest` executes (2/2, aucun echec) avec vrais QuestCore / PerceptionSession / CollectiveVote sur definitions et preuves purement factices QA-00..QA-05 ; progression, confidentialité, inversion et votes de simulation, restauration serialization verifices. `--offline test build` PASS code 0 ; `runGameTestServer` PASS 23/23 code 0. Sources de production, JARs et serveur QA inchanges. Preuve `build/quest-neutral-campaign-qa/evidence/qa-neutral-headless-20261109-2156.json`; protocole `docs/quest/qa-neutral-perception-lab.md`.

**NON VALIDES :** mini-campagne effectivement jouable (fixtures seulement), deux GUI QA r4, effets natifs Director, WDQ-001..003 issus de faits J04 reels, votes multiclients de campagne et validation graphique P0→F5. Ce PASS headless n'augmente **aucune** acceptation du parcours narratif réel. Statut global PARTIEL, contrôle horaire maintenu.
