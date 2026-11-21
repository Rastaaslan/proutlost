# P008 — recette distincte GROUP avec deux clients réels (TEST_ONLY)

État à la livraison de cette proposition : parsing Java 21 et suites pures exécutés ; compilation Minecraft/NeoForge et exécution native **NOT_RUN** par cette proposition. Aucun PASS client n'est annoncé. L'intégrateur construit, installe et conduit le serveur/les clients QA. Aucun monde conservé QA00–05 n'est réutilisé.

## Critère observable défini avant exécution

Sur un nouveau monde QA explicitement marqué, deux objets ServerPlayer connectés, non FakePlayer, survival et négociés J06 appartiennent aux teams natives distinctes `p008_rg_a` et `p008_rg_b`. Un événement régional HYBRID public fermé déclenche le vrai driver natif `camp_growth` sur une cellule STONE déjà chargée et inoccupée ; cette cellule devient MOSS_BLOCK après PREPARE durable et permit. La completion confirmée conservant exactement WORLD/REGION, sans PLAYER, passe par le vrai lifecycle et le récepteur Quest commun. Chaque joueur constate le journal GROUP résolu dans son vrai client, puis envoie son nonce de confirmation depuis ce client. Le SavedData réel (roster, core), la scoreboard native et le WAL privé sont sauvegardés et relus depuis disque. Une nouvelle JVM du même monde retrouve le même fait, même événement, mêmes UUID/teams et état GROUP, sans réinitialiser la cellule, reconstruire le core ou réassigner les teams avant vérification. Les deux sessions J06 doivent être nouvelles ; chaque client renouvelle sa lecture/confirmation. La preuve comporte les deux receipts, les binaires manifestés et les logs/captures associés de chaque client.

Le public régional est produit par une définition à rôle vide admissible, jamais par suppression de scopes après coup. La région/mémoire, les capacités de sélection, budgets et pacing sont des entrées QA synthétiques déclarées. La cellule, le binding, les joueurs/sessions, teams, admission, J04, lifecycle et Quest/SavedData sont natifs. Cela ne qualifie ni capture naturelle de région, ni canon auteur, ni perception humaine, ni charge, ni cleanup/release/inverse/restauration physique. La cellule MOSS_BLOCK et l'ownership DIRECTOR_ACTIVE restent conservés comme preuves.

## Composition et gardes

- Nouvelles identités : `QA_REAL_GROUP_CLIENTS_READ`, `proutlost_director:qa_real_group_clients/observed`, fait `proutlost_director:qa_real_group_clients_observed`, SavedData `proutlost_quest_real_group_clients_qa_v1.dat`, répertoire `real-group-clients-qa`.
- Catalogue TEST_ONLY fermé : `a19aa60c2fb11db62d61214f775d67b04b0fc08ea2ade8ddffda7ecb18cb8b28` ; la factory refuse une fixture homonyme modifiée.
- La factory/lifecycle zéro client garde sa fence originelle. Ses sources `PublicGroupQaRuntime`, `PublicGroupQaFixture`, `DirectorPublicGroupQaCatalog`, `DirectorPublicGroupQaFence` sont inchangées. Les constructeurs normaux gardent leurs limites neuf événements/deux chaînes et leurs contraintes PRODUCTION.
- Monde QA distinct, port 25579, whitelist active, au plus deux joueurs ; aucun SavedData canonique, neutral ou zéro-client. Le marqueur régulier non-symlink du monde `REAL_GROUP_CLIENTS_TEST_ONLY` contient exactement `PROUTLOST_REAL_GROUP_CLIENTS_QA_V1` (newline final autorisé).
- Le serveur peut démarrer vide : seules les gardes de monde/flags s'appliquent au ServerStarted. `produce` et `reopen` attendent deux vrais participants déjà connectés/négociés ; une perte de paire ou une occupation de l'enveloppe cellule ferme le forward live. Le drain d'un RESULT déjà en vol reste possible.
- `QuestServerTeams` est partagé par le tick Quest normal et cette recette. Il ne prend aucun groupe en payload ; seul `ServerPlayer.getTeam()` décide. Le roster SavedData est relu au froid ; aucune identité joueur n'est reconstruite depuis un receipt.
- Les offres de lecture sont liées au tuple SessionIdentity complet, UUID, core/fait et durée 1200 ticks. Un nonce étranger, périmé, antérieur au reconnect ou à la JVM suivante ne mute pas les confirmations. Ce mécanisme corrèle un contexte de commande ; il ne prouve à lui seul ni origine physique du paquet ni lecture humaine.

## Binaire et préconditions nécessaires

L'intégrateur compile avec le toolchain épinglé du chantier, au minimum :

```text
./gradlew --no-daemon compileJava compileDirectorPlatformJava realGroupClientsQaCatalogTest test --tests fr.proutlost.quest.RealGroupClientReadFenceTest jar directorJar
```

Les tâches exactes du build en vigueur et les empreintes installées font foi. La suite de corrélation pure ne remplace pas le passage native ci-dessous. Installer les JARs main + Director correspondants sur le serveur et les deux clients ; enregistrer le manifeste complet client/serveur et le SHA source testé. La propriété de manifeste client est une attestation externe contrôlée par l'intégrateur, pas une inspection automatique des machines clientes.

Utiliser un nouveau monde QA isolé, traçable et restaurable ; ne pas copier en remplaçant un monde conservé. Préparer une cellule STONE existante déjà chargée, sans block entity, dans l'Overworld, dedans le world border et hors de toute autre mutation QA. Le harness ne charge ni ne génère des chunks et ne pose pas de STONE. Les deux joueurs restent hors de l'AABB cellule gonflée de deux blocs. La garde de ressources de l'intégrateur reste applicable.

Options JVM (chemins et SHA à renseigner avec les valeurs réellement vérifiées) :

```text
-Dproutlost.director.nativeLab=true
-Dproutlost.quest.realGroupClientsQa=true
-Dproutlost.quest.realGroupClientsQaRoot=<chemin-absolu-réel-du-monde>
-Dproutlost.quest.realGroupClientsQaMainJar=<chemin-absolu-jar-main-installé>
-Dproutlost.quest.realGroupClientsQaDirectorJar=<chemin-absolu-jar-director-installé>
-Dproutlost.quest.realGroupClientsQaSourcesSha256=<64hex-source-vérifiée>
-Dproutlost.quest.realGroupClientsQaClientManifestSha256=<64hex-manifeste-clients-vérifié>
```

Ne pas activer `proutlost.quest.publicGroupQa` ni `proutlost.quest.neutralLab`. La configuration Quest garde `enabled=false` et `testCommands=false` ; le mode distinct installe seulement son propre récepteur/commandes. Whitelist des deux comptes QA et autorité opérateur explicite pour la préparation/commande administrative. Il n'est pas nécessaire d'accorder OP aux clients pour `journal` et `confirm_read`.

## Passage produit et reprise froide

1. Démarrer le serveur sur cette nouvelle copie QA et les deux vrais clients ; conserver PID+start, commande de lancement, manifestes et log J06. Vérifier qu'ils sont les deux connexions réelles négociées. Par les commandes vanilla administratives, créer deux teams natives et y joindre chacun des joueurs réels :

```text
team add p008_rg_a
team add p008_rg_b
team join p008_rg_a <nom-réel-client-A>
team join p008_rg_b <nom-réel-client-B>
gamemode survival <nom-réel-client-A>
gamemode survival <nom-réel-client-B>
proutlost real_group_qa produce <nom-réel-client-A> <nom-réel-client-B> <x> <y> <z>
proutlost real_group_qa status
```

2. Attendre le POST MOSS_BLOCK et la completion réelle (stage5). Depuis la GUI de **chacun des deux clients réels**, envoyer `/proutlost real_group_qa journal`. Le journal affiché comporte le UUID propre, la team et l'état RESOLVED ; la réponse fournit le nonce propre au contexte J06 et au core/fait courant. Chaque client envoie ensuite sa propre commande `/proutlost real_group_qa confirm_read <nonce-propre>`. Conserver logs et captures de chaque action/affichage avec la version. Une commande console `execute as`, un appel direct du dispatcher ou une simulation de contexte joueur est **interdit comme preuve de cette recette**. Le receipt seul n'atteste pas que l'action a été émise par la fenêtre cliente.
3. Attendre `REAL_GROUP_CLIENTS_QA_PRODUCE_PASS` et `done=true`. Vérifier `world/real-group-clients-qa/produce.json`, le SavedData réel, `data/scoreboard.dat`, cellule et WAL. Le harness force la sauvegarde, attend l'IO puis relit le NBT réel (roster + core) ; il compare les deux memberships disque aux vrais profils. Conserver ces fichiers, les logs et leurs hashes. Arrêter proprement cette seule JVM QA identifiée et déconnecter les clients. Ne pas réexécuter `produce` sur ce monde.
4. Démarrer une **nouvelle JVM** avec exactement monde, flags et binaires manifestés. Reconnecter les deux mêmes comptes réels, attendre deux J06 actuels négociés et garder les teams natives sauvegardées. Ne pas réassigner/recréer les teams et ne pas toucher la cellule. Exécuter `proutlost real_group_qa reopen`. Le constructeur exige déjà le SavedData et vérifie le core, le roster, les groupes avant toute synchronisation, le scoreboard disque, les UUID et les deux sessions nouvelles ; le WAL rouvre avec boot supérieur et l'horloge vient du gameTime monde conservé.
5. Depuis chaque client, envoyer à nouveau `journal` puis **le nouveau nonce propre** `confirm_read`. Attendre `REAL_GROUP_CLIENTS_QA_REOPEN_PASS`. Le fichier `reopen-<pid>-<start-ms>.json` doit pointer sur le process produit différent, le même fait/événement/core et ownership J04, les mêmes UUID/teams et deux nouvelles SessionIdentity. Aucun template GameTest ni reset n'est exécuté au reopen.

Lecture seule supplémentaire effectuée sur le JAR Minecraft épinglé : `ScoreboardSaveData` (classe SHA-256 `0d7b9d16402af0d24d36a72aaf7b60316cc39427b3ae3488022ee541dd028990`) charge/sauve bien les clés `Teams`, `Name`, `Players`. `javap` a été borné à 128 Mio/1 CPU ; cela valide les noms du codec, pas la compilation native ni l’exécution du scénario scoreboard. Sortie conservée dans `build/p008-real-group-clients/scoreboard-api-readonly.log`.

## Sorties et limites de réception

Le receipt ajoute le chemin monde/WAL, worldId, PID+start, source/JARs/catalogue/manifeste-client, gameTime/boot J04, scopes initiaux, POST physique, ownership et core, scoreboard sémantique relue disque ainsi que les UUID/noms/teams/session et nonce-hash de chaque confirmation. La propriété `external_client_manifest_sha256` doit être reliée au fichier authentique contrôlé par l'intégrateur. Les logs/captures des deux clients et les preuves natives restent obligatoires. Le champ `human_reading_or_perception_accepted` reste à `false` même si les commandes sont automatisées dans de vrais clients.

La suite pure catalogue exécute 18 contrôles de génération/admissibilité/replay et refus adverses. La suite pure corrélation exécute 12 contrôles exact-session/core/fait/temps. Ces nombres restent séparés ; ils ne constituent pas un total de livraison et ne ferment pas les négatifs natifs privé/prérequis fermé ni une recette fullpack. La qualification native et la recette humaine restent ouvertes tant que les actions et observations correspondantes n'ont pas été réellement effectuées et liées aux binaires.
