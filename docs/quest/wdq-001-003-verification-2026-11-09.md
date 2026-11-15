# WDQ-001..003 -- constat de raccordement (09/11/2026)

## Faits verifies dans le code (sans spoilers)

- QuestRuntime.publish(server, WorldFact) est une API serveur existante, sur le thread Minecraft, avec ingestion et persistance dans Quest Core.
- QuestRuntime.routine(CampRoutineEvent) est le producteur HYBRID monde / quete actuel dans le module ambient. Cela ne constitue pas une preuve d'un fait emis par World Director.
- Le port Director QuestDirectorPort / DurableQuestAdapter est un port de demandes REQUIRE de Director, pas un adaptateur de flux d'observations vers Quest Core.
- La recherche de references compilees depuis src/director et src/directorPlatform vers fr.proutlost.quest.core.WorldFact / QuestRuntime.publish ne retourne aucun appel producteur. Aucune trace en jeu d'un producteur Director -> Quest Core n'a ete etablie.
- Le runtime DirectorStandaloneMod demarre en production sans capacite/provider actif (DirectorBootstrap.compose(PRODUCTION, liste vide)); le magasin natif LAB est conditionne par la propriete proutlost.director.nativeLab. DirectorNativeCatalogueInput, qui observe des DurableFact confirmes, est utilise dans la voie TEST_ONLY. Il ne remplace pas un pont de production.
- Les quatre parcours de campagne GameTest cote serveur montrent une logique Quest Core operante, mais ne prouvent pas WDQ-001..003 depuis un vrai producteur Director.

## Critere de cloture technique (sans nouvelle fonction au CDC)

1. Identifier un flux Director monde existant et autorise, effectivement produit en QA isolee, avec un fait confirme et durable a identite stable.
2. Raccorder son transport standardise WDQ-001 jusqu'a QuestRuntime.publish sans dependance inverse du mod Director vers le moteur narratif, sans doublon a la reprise, et sans publication de contenu personnel a un autre joueur.
3. Conserver l'independance AMBIENT WDQ-002 : un evenement monde peut se produire sans quete active.
4. Prouver WDQ-003 sur un fait monde reellement observe, puis consomme par Quest Core lorsqu'une condition legitime s'applique ; distinguer faits NARRATIVE / HYBRID / AMBIENT.
5. Passer build, GameTests, test de restauration, et une preuve runtime via les deux clients QA sur un monde jetable, puis consigner identifiants techniques et PASS/FAIL uniquement.

**Etat : non valide.** Ce document ne constitue pas une implementation ou une demande de modification du Director general. Aucun evenement synthetique ou mock ne peut etre utilise comme preuve runtime de WDQ-001..003.


## Reprise ciblee 09/11, 16:02-16:20 (technique, sans narration)

- AUDIT : 69 types de faits declares par le Quest Core sont en identifiants canoniques majuscules ; zero n'est directement compatible avec un ResourceId `FactType` Director en minuscules. Les 36 types Director litteraux inspectes ne comportent aucune correspondance exacte. Ce defaut de transport etait reel, pas un echec du scenario.
- CORRECTION MINIMALE du seul adaptateur `QuestDirectorFacts` : un fait Director confirme/durable de forme `proutlost_director:quest_fact/<identifiant_minuscule>` est converti vers l'identifiant canonique seulement s'il figure deja dans les observations du QUESTSPEC charge. Les faits generiques Director restent inchanges ; aucun evenement AMBIENT ne devient une preuve narrative sans opportunite accessible. Aucune fonctionnalite ou contenu de campagne ajoute.
- TEST D'INTEGRATION SYNTHETIQUE `durableDirectorFactEventReachesQuestCoreOnceWithPrivateScope` : transport par NeoForge EVENT_BUS, ingestion dans Quest Core quand la quete de fixture est accessible, respect de la portee personnelle, dedoublonnage et restauration SavedData. C'est un TEST DE JOINTURE, pas un fait produit par Director lui-meme.
- GRADLE/PACKAGE PASS r4, sortie 0 ; GAMETEST PASS 23/23, sortie 0 a 16:16, preuve `build/quest-qa-20261109-wdq-seam-r4-{build,gametest}.{log,exit}`. Les tests unitaires ciblent aussi l'acceptation/rejet strict des types reconnus.
- **NON VALIDE :** preuve d'une production J04 native reelle de Director portant un identifiant canonique utilisable par la campagne ; les faits generic Director ne prouvent pas une condition narrative. La voie complete WDQ-001..003 reste OUVERTE. Un test humain perceptif n'est pas inferable de ces GameTests.
- Le serveur QA FIXE en ecoute :25577 a ete demarre a 13:02 (avant cette correction), donc ne contient pas encore ce nouveau code en memoire. Ne pas annoncer un PASS runtime QA avant redemarrage propre suivi de trace reelle.
## Actualisation environnement 17:30 (9 novembre, sans spoilers)

Redemarrage propre du seul serveur QA, apres sauvegarde et archive froide, vers le nouveau candidat WDQ r4. GUI FIXE reconnectee, journal technique et compteurs SavedData coherents. WDQ-001..003 reste NON VALIDE en flux reel : aucun fait J04 confirme/durable de type canonique emis par Director puis consomme par Quest Core n'a ete observe. Le nativeLab Director n'est pas actif dans le processus QA ; la jointure du GameTest reste synthetique. GUI Laptop QA fermee, sessions non-QA intactes. Voir qa-evidence/qa-quest-seam-reboot-20261109-1730.json.

## Controle complementaire 09/11, 18:46 (sans spoilers)

- L'emetteur reel `DirectorNativeEventLifecycle.poll` est identifie : il poste `DirectorCommittedFactEvent` sur un fait de completion J04 confirme et durable, apres lecture de la chronologie validee, mais ce lifecycle est reserve au `nativeLab` TEST_ONLY dans le bootstrap actuel.
- L'actuel serveur QA :25577 (PID 11148) ne porte PAS la propriete `-Dproutlost.director.nativeLab=true`. Ne pas assimiler son seul demarrage et le GameTest synthétique a une preuve de production J04.
- Reverification 18:44-18:45 : build Gradle PASS code 0, GameTests PASS code 0 et 23/23, SHA r4 inchanges. Aucune preuve nouvelle de producteur de type `proutlost_director:quest_fact/...` sur le runtime actuel. WDQ-001..003 reste ouvert.
- Pour une preuve valable : produire un fait Director valide dans un autre monde LAB jetable sans toucher a la QA principale ; inspecter l'identite et le type exacts du fait durable puis tracer sa consommation autorisee par Quest Core, sans deduire de succes narratif d'un type Director generique.


## Controle de jointure 09/11, 19:49 (sans spoilers)

- QuestDirectorFacts ne traduit un identifiant Director en observation canonique que sous proutlost_director:quest_fact/, et si l'observation est declaree dans la campagne. Les faits Director generiques ne sont pas des preuves narratives.
- Recherche litterale des producteurs dans src/director et src/directorPlatform : **0 reference** au prefixe canonique ; DirectorNativeEventLifecycle.poll peut notifier un fait J04 confirme/durable, mais demeure LAB TEST_ONLY avec flag absent du serveur QA actuel. **WDQ-001..003 : NON VALIDE en flux Director reel.** Ne pas confondre le test d'adaptateur synthetique (GameTest 23/23) avec le producteur.
- Un laboratoire distinct a ete prepare dans build/quest-native-lab-20261109, port Tailscale 25578 et liste blanche active, avec init Gradle build/quest-native-lab.init.gradle testee par help (PASS 0). **Pas demarre et aucun fait J04 emis** durant ce passage. Aucun couplage AMBIENT -> campagne ni modification du Director general.
- Criteres futurs inchanges : J04 valide reel + identite stable + portee privee + consommation par Quest Core en monde jetable, sans fuite et sans double ingestion. Preuve du controle : build/quest-private-qa-20261109/qa-evidence/qa-hourly-integrity-20261109-194x.json.


## Essai natif isolé du 09/11/2026, 21:12–21:17

Le serveur LAB indépendant sur :25578 a réellement démarré avec `-Dproutlost.director.nativeLab=true` et Minecraft/NeoForge 1.21.1/21.1.248, sans client. **J04 FAIL_CLOSED** dès l'ouverture de `director-lab-standalone/checkpoints` : `java.nio.file.AccessDeniedException`. Inspection locale : le chemin est bien un répertoire, une écriture simple dans son parent fonctionne, et `DurableFiles.directory(Path)` tente la synchronisation de répertoire via `FileChannel.open(dir, READ).force(true)`, refusée sur ce Windows. Aucun fait J04 confirmé n'a été publié ; aucune qualification WDQ-001..003 ou rendu natif ne peut être déduite de ce boot. Le processus LAB uniquement a été terminé après tentative de `stop` non prise en compte et vérification de zéro connexion ; :25578 libre. Ne pas contourner la fail-closed durability dans le code Director général pour les tests. Build Quest PASS 0 et GameTests 23/23 PASS 0 à 21:11, sans preuve supplémentaire de jointure réelle. Détail machine : `build/quest-private-qa-20261109/qa-evidence/qa-native-lab-fsync-20261109-2117.json`.
