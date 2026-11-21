# QA-NEUTRAL-POV v1 — laboratoire de rendus fictifs (sans spoilers)

**Statut : protocole prêt, exécution/implémentation du déclencheur NON FAITES.**
**Périmètre : QUESTSPEC v1.0, perception asymétrique, événements cosmétiques et raccordements; jamais campagne réelle.**
**Environnement : monde QA jetable, serveur isolé FIXE, deux clients QA uniquement, versions et SHA contrôlés avant chaque exécution.**

## Contrat de sécurité
- Les entrées et sorties du laboratoire sont exclusivement des valeurs factices (« ALPHA », « BRAVO », « CHARLIE », « SIGNE-A », « SIGNE-B », « SIGNE-C »), sons de démonstration non narratifs et identifiants `QA_POV_*`. Aucun chargement de `PerceptionContent.load()`, dossier de campagne, scène ou ressource P0→F5.
- Aucune écriture dans les états de campagne réels, `QuestSavedData`, `WorldFact` narratif ni J04 de production. Utiliser un état QA distinct et éphémère ou un monde jetable explicitement réinitialisé; ne pas émettre `QuestRuntime.publish` pour simuler la preuve WDQ.
- Un déclencheur de laboratoire devra être explicitement réservé aux opérateurs sur serveur QA (`TEST_ONLY` + propriété JVM + autorisation); il doit refuser toute exécution en serveur live, même pour un opérateur.
- Deux clients graphiques Minecraft 1.21.1 / NeoForge 21.1.248, deux JAR obligatoires chargés en mémoire dans **chaque** client; pas seulement présents sur disque. Ne pas capturer le focus d'une session non-QA sur Laptop.
- Aucune installation normale, monde live, branche active annexe ou job CB-213 modifié. État Git non commité conservé.

## Séquence neutre prévue
1. **QA_POV_01 — divergence.** Déclencher simultanément deux perceptions complémentaires : A reçoit 3 notes neutres; B reçoit 3 glyphes neutres. Vidéo/capture Minecraft réelle pour A et B, et contrôle réseau/serveur pour absence de fuite.
2. **QA_POV_02 — rôles inversés.** Modifier physiquement les positions des deux témoins et rejouer : A et B doivent changer de famille perceptive. Tester aussi le refus lorsque les positions ne changent pas suffisamment.
3. **QA_POV_03 — recueil.** Vérifier qu'une perception non enregistrée n'entre pas automatiquement dans le carnet; commande de collecte volontaire seulement pour son destinataire; refuser le vol d'identifiant.
4. **QA_POV_04 — partage et comparaison.** Partager uniquement la donnée choisie par A; B ne doit voir aucune autre donnée. Comparer deux données de familles complémentaires, avec un indicateur neutre de résultat. Refuser fragment expiré, mauvaise session et preuve inaccessible.
5. **QA_POV_05 — déconnexion/reprise.** Quitter B avant collecte, reconnecter et rappeler l'information uniquement à B; tester la conservation du caractère privé, puis un redémarrage propre du monde QA avec preuves de persistance dans le bac à sable.
6. **QA_POV_06 — galerie effets.** Ciel, météo, obscurité, particules, son spatialisé, éventuel texte individuel et effets Director TEST_ONLY neutres; capturer les deux vrais rendus. Distinguer strictement **vanilla/commandes QA** de **déclenchement natif Director**. Aucun PASS Director natif sans runtime de démonstration opérationnel.
7. **QA_POV_07 — impact de progression fictive.** Un objectif `QA_ONLY` sans contenu narratif passe de `WAITING` à `READY` uniquement après la combinaison valide des deux contributions; il ne débloque aucune étape de campagne. Ne pas compter ceci comme validation P0→F5 ni WDQ-001..003 réel.

## Matrice de preuves, à remplir après essais
| ID | Critère attendu | Résultat actuel | Preuve |
|---|---|---|---|
| QA_POV_01 | A et B reçoivent des rendus complémentaires privés | NOT_TESTED | — |
| QA_POV_02 | Redistribution après déplacement + refus sans mouvement | NOT_TESTED | — |
| QA_POV_03 | Collecte explicite et protection par destinataire | NOT_TESTED | — |
| QA_POV_04 | Partage sélectif et comparaison protégée | NOT_TESTED | — |
| QA_POV_05 | Rappel privé et persistance sur monde jetable | NOT_TESTED | — |
| QA_POV_06 | Captures/sons réels et provenance renderer identifiée | NOT_TESTED | — |
| QA_POV_07 | Résultat factice dépendant des deux perceptions | NOT_TESTED | — |

**Important :** des sous-tests proches ont historiquement réussi sur le système de campagne, mais ils ne valident pas automatiquement CE laboratoire ni les rendus r4. Ce protocole n'est ni un test exécuté, ni un nouveau sous-système de quêtes. Réutiliser le transport et les gardes existants via un petit harness QA isolé; aucun contenu narratif ne doit transiter.

**Prérequis bloquants constatés le 09/11 :** Laptop QA arrêté avec autre session Minecraft non-QA en cours; FIXE QA graphique antérieur à r4; Director native LAB échoue sur la synchronisation de répertoire Windows J04 (FAIL_CLOSED). Ne pas contourner la durabilité pour obtenir artificiellement un PASS.

## Extension demandee : mini-campagne QA autonome, en parallele (09/11/2026)

**Decision de conception :** remplacer la seule scene de demonstration par un **faux fil de quetes QA-00 a QA-05**, sans aucune narration, ressource ni etat de la vraie campagne. Il ne faut **pas** ajouter de quetes fictives a `P0-F5`, aux tables `cb213campaign`, a `PerceptionContent.load()` ni aux donnees de production.

### Fil factice
| ID | Objet technique | Critere de preuve |
|---|---|---|
| QA-00 | demarrage neutre et entree journal | journal client A/B, statut WAITING |
| QA-01A/01B | experience asymetrique sur les deux clients | son A / symbole B, confidentialite et inversion apres mouvement |
| QA-02 | collecte et mise en commun | pas de collecte automatique; partage selectif; comparaison avec controle de provenance |
| QA-03 | evenement dynamique non narratif | rendu sur chaque client, trace provenance vanilla/Director; ecart des points de vue |
| QA-04 | prise de decision collective neutre | votes distincts, rejet doublon, cloture, reprise apres reboot |
| QA-05 | etat final strictement QA | journal et consequence factice durables; remise a zero sandbox |

### Architecture retenue pour implementation future
- **Moteur reel, jeu de donnees fictif :** reutiliser `QuestCore`, `PerceptionSession`, controles d'acces, moteur de votes, routage de livraison et renderer existant. Pas de copie de logique ni d'API privee inventee.
- **Isolation forte :** instance serveur test dediee a la mini-campagne, repertoire et monde jetables distincts de `build/quest-private-qa-20261109`, port propre, liste blanche, JVM `TEST_ONLY`, sauvegarde, et state store distinct. Desactiver l'activation des donnees de campagne narrative dans ce runtime; refuser le chargement d'un vrai chapitre. Un simple prefixe de nom de quete ne suffit pas a isoler les etats.
- **Client :** conserver deux installations QA et versions identiques. Ne pas reprendre les sessions non-QA, ne pas changer le focus Laptop sans validation d'inactivite. Le journal de demonstration ne doit jamais exposer la campagne.
- **Director :** raccord via la voie TEST_ONLY dans un laboratoire autonome si J04 peut s'initialiser correctement sur la plateforme; ne pas substituer une commande vanilla au rendu natif sans etiquette distincte. Ne pas affaiblir le stockage fail-closed J04.
- **Aucune modification de code de production sans necessite prouvee.** Les adaptations necessaires doivent etre regroupees en harnais QA conditionne a l'environnement isole, avec tests de non-regression. Pas de refonte Director.
- **Validation :** Gradle et GameTests verts, mais aussi clips/captures Minecraft reelles A et B, traces serveur, controles de confidentialite et sauvegarde, redemarrage, double vote, puis essais d'exclusion en serveur normal.
- **Ordre :** realiser l'isolation et l'audit des points de reutilisation; implementer le plus petit harnais de donnees factices; tests headless; deux GUI; audit final. Garder la campagne Proutlost S2 comme tache prioritaire de livraison, et ne pas creer de job CodexBridge concurrent sur la branche active.

**Etat de cette extension au moment de la creation : SPECIFIEE UNIQUEMENT.** Aucun code, JAR, client ou monde modifie et aucun PASS de simulation revendique. Maintenir les PASS historiques de campagne distincts des futurs QA-00..05.

## Qualification headless du 09/11/2026 a 21h55-21h56 (sans spoilers)

**PASS local, distinct des tests graphiques :** ajout du seul test JUnit QA `src/test/java/fr/proutlost/quest/NeutralQuestSandboxTest.java`, sans changement des sources de production. Les 2 tests JUnit passent (0 erreur/0 echec), build Gradle `--offline test build` PASS code 0, GameTests NeoForge PASS **23/23** code 0. Empreintes des deux JAR inchangees. Preuve `build/quest-neutral-campaign-qa/evidence/qa-neutral-headless-20261109-2156.json`.

Le test utilise le **vrai QuestCore**, la vraie `PerceptionSession`, le vrai `CollectiveVote` et une chaine factice QA-00 / QA-01A / QA-01B / QA-02 / QA-03 / QA-04 / QA-05. Le moteur headless verifie verrous et progression apres contributions, perceptions complementaires, protection avant partage, collecte par le titulaire, partage volontaire, inversion apres mouvement, refus sans mouvement, restauration de la session et du Quest Core, vote avec deux participants et anti-doublon, puis etat final factice. `QA_RENDERED` est uniquement un fait synthétique du test et ne prouve aucun rendu.

**Limites importantes :** fixtures toujours DATA_ONLY_NOT_EXECUTABLE dans le vrai serveur Minecraft; pas de nouvelle mini-campagne jouable, pas de test sur deux clients r4, pas de rendu natif Director, pas de fait J04 reel, pas de vote de campagne sur deux GUI ni de P0→F5 E2E. Maintenir QA_POV_01..07 a NOT_TESTED pour les criteres Minecraft in-game jusqu'a captures et traces réelles. Le laboratoire Director J04 sous Windows reste fail-closed a l'ouverture du stockage; ne jamais affaiblir cette propriete. Aucun serveur, instance, client live ou monde existant modifie; CB-213 non relance.
