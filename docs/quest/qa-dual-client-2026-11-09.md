# Proutlost S2 — Qualification graphique FIXE + Laptop (QA isolée, sans spoilers)
Date locale : 2026-11-09 (00:20–00:54, Europe/Paris). État : PARTIELLEMENT VALIDÉ, NON LIVRÉ 100 %.

## Environnement et intégrité
- FIXE : dépôt C:\WorldDirectorS2\work\main (modifications Git non commitées préservées).
- Serveur QA dédié : C:\WorldDirectorS2\work\main\build\quest-private-qa-20261109, monde neuf et indépendant du serveur live, port 25577 limité à l'interface Tailscale 100.126.186.44 ; aucune intervention sur dam-server.
- FIXE GUI : runClient Gradle, game dir build\quest-private-qa-client-20261109 ; Minecraft 1.21.1, NeoForge 21.1.248.
- Laptop GUI : instance CurseForge « Proutlost S2 - QA (sans spoilers) » (chemin C:\Users\damie\curseforge\minecraft\Instances\Proutlost S2 QA ISOLATED), distincte du profil Proutlost de jeu.
- Deux mods applicatifs nécessaires sur CHAQUE client : proutlost-0.1.0.jar (SHA-256 7244E86BA7B4F79F8FF53FE42BC6939852FC9ADA30290999CA29273EF4E77D36) ET proutlost-director-1.0.0.jar (SHA-256 FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C). Laptop également WorldEdit 7.3.7. Les hash des deux JAR ont été comparés après transfert.
- Configuration QA Quest Core enabled=true, testCommands=true ; ne PAS réutiliser cette configuration sur serveur live.

## Matrice QA sans spoilers
| Contrôle | État | Preuve / commentaire |
|---|---|---|
| JAR de quêtes disponible sur FIXE et laptop | PASS | SHA vérifié |
| JAR Director présent sur laptop | PASS APRÈS CORRECTION | Premier handshake rejeté : canaux proutlost_director manquants ; copie du Director JAR vérifiée |
| Compatibilité Minecraft / NeoForge | PASS | 1.21.1 / NeoForge 21.1.248 sur les deux clients |
| Démarrage serveur QA isolé | PASS | logs\latest.log : Done (4.217s) le 09/11 à 00:28:03 |
| Client FIXE : chargement écran graphique en jeu | PASS | connexion Dev le 09/11 à 00:42:40, confirmé par serveur |
| Client laptop : chargement écran graphique en jeu | PASS | connexion Rastaaslan le 09/11 à 00:47:46, confirmé par serveur |
| Deux connexions graphiques simultanées | PASS | deux connexions TCP established et deux messages joined the game dans les logs |
| Commande journal avec pages réellement affichées | NON VALIDÉ | commande tentée, mais aucune page visible/confirmée ; monde QA vierge sans emplacements préparés |
| Confidentialité des perceptions / sons ciblés | NON TESTÉ | nécessite sites préparés et progression contrôlée |
| Échanges d'observations / votes multijoueur | NON TESTÉ | nécessite conditions de campagne réellement satisfaites |
| Reconnexion en cours de progression | NON TESTÉ | prévoir séquence et sauvegarde |
| Restauration après redémarrage QA avec deux clients | NON TESTÉ | pas encore de progression probante |
| Parcours P0→F5 via DEUX vrais clients graphiques | NON TESTÉ | ne pas confondre les GameTests serveur antérieurs avec un essai graphique complet |
| Serveur / monde live inchangés | PASS (opérations de cette QA) | tous les fichiers et connexions visent l'environnement QA séparé |

## Incidents QA résolus
1. Le fichier TOML QA créé en UTF-8 avec BOM a été rejeté par NightConfig ; il a été réécrit sans BOM puis le serveur QA a été redémarré avec Quest Core enabled=true.
2. Premier essai laptop : JAR Director absent, les canaux réseau du mod étaient manquants. Ajout du JAR Director dans l'instance isolée et reconnexion multiclient réussie.
3. Essai d'adresse invalide à cause d'un champ Direct Connection déjà rempli : erreur de manipulation, corrigée sans changement du produit.

## Actions restantes
1. Préparer, sur le monde QA uniquement, des points d'essai conformes à QUESTSPEC en utilisant les commandes admin autorisées, puis vérifier que le journal ne divulgue que les étapes accessibles (ne pas afficher de spoils dans les rapports).
2. Tester perceptions personnelles, sons localisés, collecte volontaire et non-fuite entre deux clients graphiques.
3. Tester partage, votes, déconnexion/reconnexion et persistance après arrêt propre et redémarrage du serveur QA.
4. Réexécuter les tests pertinents après tout correctif et conserver un compte rendu PASS/FAIL technique sans narration.
5. Documenter la distribution en paire des deux JAR et la configuration finale hors QA (testCommands=false).
6. Ne pas déclarer la saison complètement validée tant que les points non testés ci-dessus ne sont pas prouvés.

## Références de preuve locales
- Serveur QA : build\quest-private-qa-20261109\logs\latest.log
- FIXE client QA : build\quest-private-qa-client-20261109\logs\latest.log
- Laptop client QA : C:\Users\damie\curseforge\minecraft\Instances\Proutlost S2 QA ISOLATED\logs\latest.log
- Source technique : docs\quest\manual-two-client-qualification.md ; docs\quest\acceptance-windows-2026-11-08.md.
- Copies d'écran QA internes disponibles sur le FIXE dans build\quest-private-qa-20261109 et sur le laptop dans C:\Users\damie\quest-qa ; ne pas les publier à l'utilisateur pour éviter tout spoiler.

## Passe autonome du 09/11/2026 — 02:52–03:05 (Europe/Paris, sans spoilers)

- **FIXE QA / Laptop QA : PASS disponibilité** — deux processus graphiques Minecraft 1.21.1 / NeoForge 21.1.248 ouverts, chacun dans le monde QA, avec connexions TCP simultanées au serveur isolé Tailscale :25577. Serveur QA à l'écoute ; deux entrées de connexion dans son journal, aucune déconnexion constatée sur ce passage.
- **Intégrité de distribution : PASS** — deux JARs sur le Laptop QA identiques aux candidats FIXE : `proutlost-0.1.0.jar` SHA-256 `7244E86BA7B4F79F8FF53FE42BC6939852FC9ADA30290999CA29273EF4E77D36` ; `proutlost-director-1.0.0.jar` SHA-256 `FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C`. Client FIXE démarré en mode développement sur les classes locales avec `proutlost_director`.
- **Build Gradle : PASS (sortie 0)** — `gradlew.bat --offline test build --no-daemon --console=plain`, log `build/quest-qa-20261109-gradle-r2.log`, message `BUILD SUCCESSFUL`. **GameTests : PASS 22/22, sortie 0** — `gradlew.bat --offline runGameTestServer --no-daemon --console=plain`, log `build/quest-qa-20261109-gametest-r2.log`. JARs aux mêmes empreintes après build.
- **Commande journal, deux clients : NON VALIDÉE fonctionnellement** — saisies non narratives dans les fenêtres Minecraft QA. Le premier automate a envoyé une ligne de chat (défaut d'injection clavier du banc de test, pas du mod) ; une correction a permis de visualiser la commande correctement préparée sur FIXE et de tenter son exécution sur les deux clients. Aucune page de carnet confirmée. Le monde QA ne possède pas encore les sites préparés ; `QuestRuntime.register` n'émet aucun message pour `journal` si aucune page n'est visible. Ne pas attribuer PASS à ce test ni déduire un bug applicatif.
- **Captures de preuve internes (ne pas publier)** : FIXE `build/quest-private-qa-20261109/qa-journal-input-confirm-fixe.png` et `qa-journal-result-fixe.png` ; Laptop `C:/Users/damie/quest-qa/qa-journal-result-laptop-r2.png`.
- **NON TESTÉ** : confidentialité et sons ciblés, collecte et partage réels, comparaisons à deux témoins, votes, déconnexion/reconnexion après progression et persistance au redémarrage avec deux clients graphiques. Le mapping et les conditions réelles manquent encore.
- **Protection** : aucun code changé pendant ce passage, worktree Git non commité préservé, ni serveur ni monde live modifiés. CB-213 non relancé, aucun job parallèle créé.

## Passe autonome complémentaire — 09/11/2026, 03:49–04:05 (sans spoilers)

- ENVIRONNEMENT FIXE + Laptop : PASS technique — deux GUI Minecraft 1.21.1 / NeoForge 21.1.248 encore ouvertes. Deux connexions TCP Established vers le serveur QA dédié :25577, PID serveur FIXE 3844 et client Laptop PID 36176. Aucun serveur/monde live sollicité.
- DISTRIBUTION : PASS — proutlost-0.1.0.jar SHA-256 7244E86BA7B4F79F8FF53FE42BC6939852FC9ADA30290999CA29273EF4E77D36 et proutlost-director-1.0.0.jar SHA-256 FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C sur les deux postes ; JAR FIXE identiques après build.
- OUTIL DE SAISIE QA : PASS pour la commande neutre /help. Le script dédié build/quest-private-qa-20261109/qa-safe-input.ps1 (copie à la racine de l'instance QA laptop) vérifie PID, titre de fenêtre Minecraft et premier plan ; restaure le presse-papiers. Il refuse les commandes non autorisées par une liste fermée. /help a produit des réponses [System] [CHAT] dans les logs des DEUX clients, à 03:54:54 sur FIXE et 03:55:10 sur Laptop. C'est une preuve du chemin clavier -> client -> serveur -> retour chat, mais pas d'une quête.
- RECTIFICATION IMPORTANTE : les textes du 03:00:11 et 03:00:16 commençant par « t » étaient du CHAT ordinaire, pas des commandes. Ne pas les présenter comme une preuve de carnet. Les nouvelles saisies /proutlost quest journal et /proutlost quest catch_up ont été émises par le nouvel outil sur les deux clients mais aucune page de carnet observable n'a été reçue dans ce monde QA vierge : état AFFICHAGE JOURNAL = NON VALIDÉ, pas de PASS ni de FAIL applicatif.
- BUILD : PASS sortie 0 — gradlew.bat --offline test build --no-daemon --console=plain, journal build/quest-qa-20261109-gradle-r3.log, BUILD SUCCESSFUL.
- GAMETESTS : PASS sortie 0, 22/22 — gradlew.bat --offline runGameTestServer --no-daemon --console=plain, journal build/quest-qa-20261109-gametest-r3.log, marqueur à 03:58:18.
- CONFIDENTIALITÉ GRAPHIQUE, perceptions, échanges/votes, reconnexion et persistance après progression : NON VALIDÉS. Aucun site préparé n'a été confirmé dans la copie QA ; il faut mettre en place des sites et droits administratifs sur cette seule copie avant de tester une progression authentique. Aucun fait synthétique ne sera assimilé à une validation scénaristique. Aucun redémarrage brutal du serveur QA.
- PROTECTION : worktree non commité préservé, aucune relance de CB-213 ni job parallèle ; monde et serveur live inchangés. Qualification globale PARTIELLE, suivi horaire conservé.

### Préparation technique additionnelle — droits administratifs QA, non activés

- Uniquement dans build/quest-private-qa-20261109/ops.json : entrée opérateur niveau 4 pour l'acteur de test FIXE Dev, UUID résolu depuis le usercache.json réel. Le second client QA reste non opérateur. Fichier antérieur [] sauvegardé en ops.json.pre-qa-backup. Écriture JSON UTF-8 sans BOM et relecture vérifiées.
- **État : PRÊT POUR RESTART QA, NON ACTIVÉ / NON TESTÉ.** Le serveur QA courant tourne toujours avec sa liste d'opérateurs chargée au démarrage ; aucun accès administrateur ni site lié ne doit être présumé avant un arrêt propre, redémarrage isolé et vérification des droits. Ne jamais appliquer cette entrée au serveur live. Prévoir reconnexion des deux clients QA puis bindings de sites uniquement dans ce monde jetable.


## Passe QA du 09/11/2026, 10:37–10:55 (Europe/Paris ; zéro spoiler)

- **QA-ENV PASS** — Serveur FIXE isolé PID 37088, Tailscale 100.126.186.44:25577 ; deux vrais clients graphiques simultanément connectés. Laptop reconnecté à 10:44:49, confirmé par journal serveur et socket TCP Established. Aucun accès live.
- **QA-DIST PASS** — Les deux JARs obligatoires sont présents et leurs empreintes SHA-256 concordent entre FIXE et Laptop : proutlost-0.1.0.jar = 540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A (523805 octets) ; proutlost-director-1.0.0.jar = FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C (3023259 octets). Ancien JAR Laptop sauvegardé hors mods, sous C:\Users\damie\quest-qa\jar-backup-20261109. Fermeture propre de la seule instance QA Laptop à 10:41:07, copie vérifiée, relance PID 16856, reconnexion.
- **QA-JOURNAL-TEXT-2C PASS ciblé** — /proutlost quest journal réexécuté réellement dans chacune des deux GUI après synchronisation. ChatComponent produit sur les deux clients huit en-têtes techniques P0, P1, P2, P3, A1, C1, C2, C3, et quatre IDs techniques d'évidence déjà existants. Aucune erreur de commande. Ceci atteste les pages textuelles, non le rendu humain/acoustique ni la confidentialité de nouveaux fragments non partagés.
- **QA-RECONNECT PASS ciblé** — Client Laptop proprement quitté, relancé puis reconnecté ; pages présentes après reconnexion. /proutlost quest catch_up exécuté côté Laptop, messages de retour consignés dans son log. Cela ne vaut PAS preuve d'un redémarrage du serveur.
- **QA-PRIVATE-RECORD-DENY PASS ciblé** — Depuis le Laptop, tentative de collecte d'un ID technique de perception appartenant au témoin FIXE (qa0737_perception:2:380df991-f603-344c-a090-369bad2a924a) : rejet contrôlé « scène indisponible » sans erreur technique. Le contrôle ne couvre pas tout le circuit de confidentialité ni l'acoustique.
- **QA-DISK-SNAPSHOT PASS technique** — Snapshot lecture seule world/data/proutlost_quest_v1.dat vers build/quest-private-qa-20261109/qa-evidence/saveddata-20261109-1053.dat (SHA-256 0393E21A70C8AB7090772A11198C4DE08068CD5C1DF892B4E8C2394D935BE77D), décompression NBT valide avec champs campaign, sites et marqueurs techniques attendus. Redémarrage serveur non testé.
- **QA-BUILD/GT PASS antérieur vérifié** — build/quest-qa-20261109-gradle-r4.log et gametest-r4.log, fichiers .exit de valeur 0, BUILD SUCCESSFUL et 22/22 GameTests. Exécution à 08:14 sur le candidat courant ; pas de nouveau build pendant cette passe.
- **NON VALIDÉ** — Confidentialité exhaustive pré-partage, son entendu par chaque client, vote réel D3/F3, redémarrage serveur QA avec récupération de progression et deux clients, raccordement factuel d'un producteur Director réel, vérification perceptive humaine et fin de parcours E2E graphique. Aucun 100 %.
- **Protection** — Aucun source modifié dans cette passe ; worktree non commité préservé, CB-213 non relancé, monde live et installations usuelles intacts. Liaison QA qa0615_shore tentée et refusée car identifiant déjà existant (aucune nouvelle liaison).



## Passe autonome du 09/11/2026, 12:17–12:31 (Europe/Paris ; strictement sans spoilers)

- **QA-ENV PASS avant et après** — Contrôle préalable des vrais clients FIXE (PID 39876) et Laptop (PID 16856), et du seul serveur QA FIXE (`build/quest-private-qa-20261109`, Tailscale `100.126.186.44:25577`). Deux connexions Established initiales. Après redémarrage serveur : PID 33364, client FIXE reconnecté à **12:27:03**, Laptop reconnecté à **12:27:47** ; connexions simultanées confirmées par les logs et sockets.
- **QA-DIST PASS** — Les DEUX mods sont toujours présents et identiques par SHA-256 : `proutlost-0.1.0.jar` = `540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A`, `proutlost-director-1.0.0.jar` = `FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C` sur FIXE/Laptop. NeoForge `21.1.248`, Minecraft `1.21.1`.
- **QA-SERVER-RESTART PASS (réel et propre)** — La commande admin QA `/save-all flush` du client FIXE a obtenu « Saved the game » à **12:21:55**. `/stop` du même client à **12:22:15** : arrêt propre prouvé par `Stopping server`, sauvegarde des joueurs et de toutes les dimensions, port 25577 libéré. Copie froide du monde QA seul : `build/quest-private-qa-20261109/qa-evidence/qa-world-cold-before-restart-20261109-1222.zip` (5 501 795 octets, SHA-256 `5CBB56ACF6FD39A43239C1D6FE00513C6320F22B42288CBD7A4F5FA471ED0258`). Nouveau serveur QA lancé par `gradlew.bat --offline -I build/quest-private-qa.init.gradle runServer --no-daemon --console=plain`, `Done (1.222s)` à **12:23:15**. Aucun redémarrage/accès du live.
- **QA-PERSISTENCE-DISK PASS ciblé** — `world/data/proutlost_quest_v1.dat` : SHA-256 avant/après arrêt et redémarrage identique `0393E21A70C8AB7090772A11198C4DE08068CD5C1DF892B4E8C2394D935BE77D`. Snapshot NBT complet du contrôle précédent valide. **Ne prouve pas à lui seul chaque état après chargement.**
- **QA-JOURNAL-RESTORE-2C PASS ciblé (progression existante relue)** — `/proutlost quest journal` a été réellement exécuté dans les DEUX GUI après redémarrage à **12:28:14** (FIXE) et **12:28:19** (Laptop). Huit IDs d'en-têtes techniques confirmés dans chacun des logs : `P0`, `P1`, `P2`, `P3`, `A1`, `C1`, `C2`, `C3`. Aucun titre, contenu, indice ou choix narratif dans ce rapport. `/proutlost quest catch_up` exécuté à nouveau sur Laptop à ~12:30 sans erreur de saisie ; pas de changement de progression revendiqué.
- **QA-BUILD-R5 PASS** — `gradlew.bat --offline test build --no-daemon --console=plain`, code `0`, `BUILD SUCCESSFUL in 6s`, `build/quest-qa-20261109-gradle-r5.log` et `.exit`.
- **QA-GAMETEST-R5 PASS** — `gradlew.bat --offline runGameTestServer --no-daemon --console=plain`, code `0`, `All 22 required tests passed`, `BUILD SUCCESSFUL in 29s`, `build/quest-qa-20261109-gametest-r5.log` et `.exit`. Les GameTests serveur ne constituent pas une qualification perceptive humaine.
- **NON VALIDÉ / prochaine qualification** — Confidentialité exhaustive avant et après partage, écoute audio humaine ciblée, partage/comparaison et votes réels D3/F3 via deux GUI, parcours graphique intégral, raccordement WDQ-001..003 depuis un véritable producteur World Director. Reconnexion + journal sont PASS ciblés, PAS preuve globale de la campagne. Aucun 100 %.
- **Protection et suites** — Worktree Git non commité préservé ; aucun patch source ni modification du serveur/monde live ; CB-213 non relancé, aucune branche concurrente. Contrôle horaire conservé, qualification toujours PARTIELLE. 

## QA ciblée — 09/11/2026, 12:45–12:55 (FIXE + Laptop, aucun spoiler)

- **QA-ENV PASS** — Avant action, serveur QA isole PID 33364 ecoute Tailscale 100.126.186.44:25577 ; deux connexions TCP Established et deux clients Minecraft graphiques presentes, FIXE PID 39876 et Laptop PID 16856. Aucun acces au live.
- **QA-DIST PASS** — JAR Quest identique FIXE/Laptop : SHA-256 `540DE47ADF15E05E96C8B0D167757C99C5C3DA55D4B8B276E028F380B415EC6A` ; JAR Director identique : `FAD0C3980C5BED3A8209EF95E813A7FABCC6E01812BC752DE39D8AE699C06E5C`. Versions de l'instance QA inchangees (Minecraft 1.21.1, NeoForge 21.1.248).
- **QA-PRIVACY-BEFORE-SHARE PASS cible** — `/proutlost quest journal` emis par les deux vraies GUI vers 12:50:23/12:50:27. Chacune affiche 7 identifiants techniques de relevés, dont son propre fragment prive `qa0737_perception:3:<UUID>` mais PAS celui de l'autre acteur. Etat source NBT confirme : 8 observations, 6 partages, 1 fragment prive non partage par acteur.
- **QA-EXPLICIT-SHARE PASS cible** — Action de partage volontaire d'un seul fragment technique trial 3 par l'acteur FIXE vers l'acteur Laptop depuis la GUI FIXE, avec controle de fenetre et connexion QA. Journal Laptop relu a 12:51:53 : 8 identifiants, dont le fragment selectionne de FIXE ET son propre fragment. Aucune preuve de publication des autres fragments par cet acte.
- **QA-OWNERSHIP-DENY PASS cible** — Depuis la GUI Laptop, `record_perception` du fragment dont FIXE est temoin refuse (12:52:58) ; tentative de repartage de ce meme fragment par Laptop (non auteur) refusee (12:53:01). Retours de refus captures dans le journal graphique ; aucun crash.
- **QA-OTHER-PRIVATE-STILL-HIDDEN PASS cible** — Le journal FIXE est relu a 12:55:18, contient 7 identifiants ; le fragment prive non partage de Laptop n'y apparait pas, alors que le fragment propre de FIXE y reste.
- **QA-SAVE-SHARES PASS disque** — `/save-all flush` depuis FIXE a donne un ACK reel dans le journal serveur a 12:54:15. Fichier `world/data/proutlost_quest_v1.dat` de la seule QA : NBT valide, schema 1, 8 observations et exactement 7 partages apres l'unique partage reussi (6 auparavant), le fragment prive Laptop restant non partage. SHA-256 du snapshot sur disque `391F7D5DF9921917EE48681A0A44D2132E404AA2DB8991E7BDD772FBF7AA8F24`. Les refus n'ont pas cree de nouvelle observation. Ceci prouve la sauvegarde, pas un nouveau rechargement.
- **QA-BUILD/GT PASS precedent revérifie** — Artefacts `build/quest-qa-20261109-gradle-r5.exit=0`, `...gametest-r5.exit=0`, 22/22 GameTests lors du dernier passage. JARs encore inchanges, aucun rebuild ni patch source pendant ce controle.
- **LIMITES** — Controles ciblés de confidentialite et de partage PASS ; non-equivalence avec une validation exhaustive ou sonore. Reste NON VALIDE : audio entendu humainement sur deux clients, votes reels D3/F3, consequence et finale graphique integrale, rechargement apres CE nouveau partage (le redemarrage QA precedent et la restauration du journal sont deja PASS ciblés), production d'un fait reel World Director consomme par Quest Core (WDQ-001..003). Ne pas attribuer 100 %.
- **Protection** — Monde et serveur live inchanges, aucun job CB-213 relance, aucune modification source et aucun commit/merge/push du Git worktree. Outils de saisie et inspection uniquement sous `build/quest-private-qa-20261109` et dans l'instance QA Laptop. Preuves synthetiques resumees en `qa-evidence/qa-privacy-share-20261109-1255.json`.
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

## Controle autonome 09/11/2026, 16:02-16:20 (sans spoilers)

- ETAT REEL : QA FIXE 25577 en ecoute (PID 27352), un seul client graphique FIXE connecte. Client QA Laptop ferme proprement a 15:24:40, aucun `java/javaw` actif au controle. L'autre activite de bureau Laptop n'a pas ete interrompue ; aucune validation a deux nouvelles GUI revendiquee.
- DISTRIBUTION PASS : nouveau JAR Quest FIXE `4293FBE8FC1CD898A3DDE9F5D1360FE5D1FAAE1C474F4FCC3C91B11B72DD6B7E`, 529997 octets. Transfer Tailscale vers Laptop avec SHA-256 verifie, ancienne version sauvegardee DANS l'instance QA `qa-candidate-20261109-1617`. Le JAR Director est identique sur les deux postes, SHA-256 `01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774`. Aucune installation normale modifiee.
- BUILD / GAMETEST : PASS code 0 / PASS 23/23 code 0 sur FIXE, apres correction du mapping d'identifiant technique des faits Director dans l'adaptateur Quest Core. Journal r4 a 16:16, `build/quest-qa-20261109-wdq-seam-r4-*`.
- CE QUE CELA NE PROUVE PAS : aucun vrai fait J04 Director consomme dans Quest Core, aucun vote D3/F3 effectivement depouille sur deux clients, aucune audition humaine du son, ni parcours E2E graphique. QA globale PARTIELLE ; pas de 100%.
- Aucune action serveur live ; serveur QA courant preserve, pas de restart brutal, worktree non commite preserve, CB-213 non relance, aucun job concurrent.
## Reprise QA 09/11/2026 17:26-17:30 (sans spoilers)

- QA-SAFE-REBOOT PASS : serveur QA ancien PID 27352, un client GUI FIXE seulement, sauvegarde /save-all flush acceptee a 17:26:01, /stop propre puis port libere. Backup froid du monde QA uniquement, SHA-256 7907D38D4B41FDB7D69FA1AB64B99223E493F06D1410780085C4E70B3F9DC59F. Nouveau serveur QA PID 11148, demarre a 17:27:09, Done a 17:27:27 sur Tailscale 100.126.186.44:25577. Aucun acces au live.
- QA-NEW-BUILD PASS : le serveur a charge le candidat de 16:16 par Gradle runServer. Quest SHA-256 4293FBE8FC1CD898A3DDE9F5D1360FE5D1FAAE1C474F4FCC3C91B11B72DD6B7E et Director SHA-256 01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774. Les deux JAR sont synchronises avec le Laptop mais sa GUI QA reste fermee : autres activites non-QA preservees.
- QA-RECONNECT-FIXE PASS cible : client PID 39876, connexion GUI reelle au nouveau serveur et socket Established a 17:29:15.
- QA-JOURNAL-AFTER-REBOOT-FIXE PASS cible : commande /proutlost quest journal emise dans la GUI, huit IDs techniques P0/P1/P2/P3/A1/C1/C2/C3 releves dans le journal client. Ne vaut pas test 2 GUI pour cette build.
- QA-PERSISTENCE PASS cible : SavedData contient apres reboot 12 faits, 8 observations, 7 partages, 6 etapes resolues, 0 votes. Aucun changement indéduit du journal.
- BUILD/GAMETEST PASS candidats precedents : r4 du 09/11 a 16:16, code 0, GameTests 23/23. Non relances pendant cette passe.
- WDQ-001..003 : le pont est present dans les classes chargees du serveur QA, mais aucun vrai fait J04 de Director de type Quest canonique consomme par Quest Core n'a ete produit pendant cette passe. Le chemin de production Director native est sous nativeLab non active ici. Toujours NON VALIDE E2E.
- Non valides : perception sonore humaine, votes D3/F3 reels dans deux GUI, completude graphique P0 a F5 et WDQ runtime. Aucun 100 %, aucun job CB-213 relance, aucun changement des installations usuelles.
- Preuve JSON : build/quest-private-qa-20261109/qa-evidence/qa-quest-seam-reboot-20261109-1730.json.

## Checkpoint QA du 09/11/2026, 18:41-18:46 (sans spoilers)

- **ENV FIXE PASS cible :** serveur QA isole sur Tailscale `100.126.186.44:25577`, PID 11148 (demarre 17:27), une connexion TCP etablie pour la GUI FIXE PID 39876. Pas de restart ni de modification du monde durant cette passe.
- **LAPTOP QA A L'ARRET :** instance isolee presente et deux JARs verifies, mais aucun client graphique QA actif ; une autre session Minecraft non-QA PID 31496 est en cours. Aucun focus capture, fermeture ni relance de cette session.
- **SHA PASS :** Quest `4293FBE8FC1CD898A3DDE9F5D1360FE5D1FAAE1C474F4FCC3C91B11B72DD6B7E` et Director `01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774`, identiques sur disque FIXE et dans l'instance QA Laptop. Le serveur est en dev runServer, pas avec les JAR sous son dossier `mods`.
- **BUILD PASS / GAMETEST PASS :** nouvelles executions Gradle `--offline test build` code 0 (18:44:44), `--offline runGameTestServer` code 0 (18:45:20), 23/23. Logs `build/quest-qa-20261109-1845-{build,gametest}.log` et fichiers `.exit`. Les empreintes des deux JARs restent identiques apres compilation.
- **INTEGRITE CLIENT FIXE NON CERTIFIEE pour r4 :** le processus graphique PID 39876 a ete lance a 00:29, avant la reconstruction r4 (16:15). Bien qu'il reste connecte et reponde, une instance dev chargee avant ce patch ne prouve pas que les classes client en memoire correspondent au candidat r4. Il faudra un redemarrage propre et controle de cette seule GUI QA lorsque son inactivite de test sera certaine.
- **WDQ-001..003 NON VALIDE en flux reel :** audit de `DirectorNativeEventLifecycle.poll` : l'evenement `DirectorCommittedFactEvent` est poste sur la reconnaissance d'un fait de completion J04 confirme et durable, mais le lifecycle est actuellement garde par le flag `nativeLab` et les types de completion Director generiques ne sont pas une preuve Quest canonique. Le processus QA actuel ne contient pas `-Dproutlost.director.nativeLab=true`. Le GameTest de jointure demeure explicitement synthetique. Aucune preuve runtime d'un fait J04 Director consomme par Quest Core.
- **NON VALIDES ce passage :** nouveau vote D3/F3 reel a deux GUI, perception acoustique humaine, parcours E2E P0-F5 sur deux clients et rendus natifs Director. Les PASS graphiques anterieurs restent historiques et ne sont pas transferes au nouveau candidat sans test.
- **PROTECTION :** aucune modification des sources, du Git worktree non commite, du monde live ou des installations normales ; CB-213 non relance. Automatisation a conserver tant qu'une suite technique autonome reste possible.


## Checkpoint autonome — 09/11/2026, 19:43–19:49 (sans spoilers)

- **ENV PASS ciblé :** FIXE QA serveur PID 11148, Tailscale :25577 en ecoute et une connexion Established ; client graphique FIXE PID 39876 présent. Le processus FIXE a ete lance avant r4 : il ne constitue PAS une preuve graphique de la build r4. Laptop QA non lance au controle 19:43 ; une autre session Minecraft non-QA y reste active, intacte et sans prise de focus. Aucune qualification a deux GUI r4 ne peut etre revendiquee.
- **SHA PASS :** Quest 4293FBE8FC1CD898A3DDE9F5D1360FE5D1FAAE1C474F4FCC3C91B11B72DD6B7E et Director 01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774 sur les JARs FIXE, identiques aux JARs verifies a 19:43 dans l'instance QA Laptop. Le serveur FIXE et le client FIXE utilisent les classes de developpement, pas des JARs dans le sous-dossier mods du serveur.
- **BUILD / GT PASS anterieur revalide :** logs du 09/11 a 18:44–18:45, sorties 0 / 0, 23/23 GameTests. Aucun nouvel essai multiclient ni resultat audio perceptif ne decoule de ce controle.
- **WDQ-001..003 NON VALIDE :** le producteur DirectorNativeEventLifecycle.poll demeure LAB TEST_ONLY (flag absent du PID 11148). Audit literal du code src/director + src/directorPlatform : aucun producteur de type proutlost_director:quest_fact/. Le test de jointure NeoForge est synthetique ; aucune preuve J04 native de fait canonique consomme par Quest Core.
- **PREPARATION LAB SEPAREE :** build/quest-native-lab.init.gradle et build/quest-native-lab-20261109/server.properties crees uniquement sous build (Tailscale :25578, whitelist active), afin d'eviter toute interference avec le QA principal :25577. Verification de configuration Gradle help PASS, code 0. **LAB non demarre, aucun effet natif teste, aucune capture native qualifiee** ; suite seulement dans ce monde jetable, avec acteurs QA autorises et sans toucher aux sessions non-QA.
- **MATRICE :** journal, partage volontaire, protections de confidentialite et persistance conservent leurs PASS cibles historiques ; votes reels D3/F3, audio humain, boucle graphique P0→F5, WDQ J04 reel et deux GUI r4 restent NON VALIDES. Evidence machine : build/quest-private-qa-20261109/qa-evidence/qa-hourly-integrity-20261109-194x.json.
- **PROTECTION :** aucun changement dans les sources ou branches, worktree Git non commite preserve (16 entrees au statut), aucune intervention sur monde/serveur live ni installation normale, aucune relance CB-213. Qualification globale PARTIELLE, controle horaire maintenu.


## Contrôle ciblé 09/11/2026 21:07–21:17 — intégrité et LAB isolé (sans spoilers)

- **QA-ENV PASS ciblé** : serveur QA :25577 PID 11148, une connexion FIXE PID 39876 ; client QA Laptop arrêté, session non-QA Laptop conservée. Processus FIXE graphique antérieur au candidat r4 : rendu r4 **NON VALIDÉ**. Aucun essai multiclient additionnel revendiqué.
- **QA-DISTRIBUTION PASS** : empreintes r4 identiques FIXE/Laptop, Quest `4293FBE8FC1CD898A3DDE9F5D1360FE5D1FAAE1C474F4FCC3C91B11B72DD6B7E`, Director `01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774`.
- **QA-BUILD PASS / GAMETEST PASS** : nouveaux essais Gradle 21:10–21:11, sorties 0/0, **23/23 GameTests**. Preuves `build/quest-qa-20261109-210x-{build,gametest}.{log,exit}`. Aucun code source modifié ; worktree 16 entrées non commitées préservé.
- **QA-NATIVE-LAB START PASS / J04 FAIL_CLOSED** : laboratoire séparé :25578, monde jetable, whitelist active, flag `proutlost.director.nativeLab=true`. Démarrage Minecraft NeoForge réussi à 21:12:45 ; initialisation du WAL J04 **en échec** : `AccessDeniedException` sur le répertoire `director-lab-standalone/checkpoints`. Diagnostic local : `DurableFiles.directory` tente un `FileChannel.open(directory, READ).force(true)`, non disponible dans cette configuration Windows ; son répertoire parent est inscriptible. Ne pas supprimer le garde-fou de durabilité pour obtenir un faux PASS.
- **LAB sécurisé** : aucune connexion ni commande scénaristique ; arrêt demandé sur console sans effet, puis seul processus LAB jetable PID 41416 terminé après vérification de zéro client. Port :25578 libéré, serveur principal :25577 toujours en écoute et client FIXE maintenu. **RENDERER NATIF = NOT_TESTED**, aucune capture native revendiquée.
- **WDQ-001..003 = NON VALIDÉ** : aucun fait Director J04 réel consommé par Quest Core. Votes réels, audio humain, deux GUI r4, E2E graphique toujours NON VALIDÉS. Rapport `build/quest-private-qa-20261109/qa-evidence/qa-native-lab-fsync-20261109-2117.json`. Aucun serveur live/installation normale touché.


## Qualification finale — Work Ultra du 9 novembre 2026

Le candidat corrigé a terminé P0 → F5 sur deux clients graphiques réels. Les sauvegardes, les deux décisions et la résolution F4 après redémarrage avec un contributeur déconnecté ont été vérifiées. Le laboratoire fictif QA-00 → QA-05 a également été rechargé au nouveau format NBT, sans doublon Director.

Résultats courants, empreintes et réserves : [qualification finale sans spoilers](qa-work-ultra-final-2026-11-09.md). Installation et commandes : [laboratoire fictif jouable](qa-neutral-playable-2026-11-09.md). Les preuves historiques ci-dessus restent conservées et gardent leur périmètre initial.
