# P008 — vrai ProductionRuntime avec entrées QA déclarées

État du dépôt de proposition : code isolé seulement.17unités Java ont passé le parsing Java21 sans erreur ; typecheck Minecraft/NeoForge, compilation native, installation, démarrage, clients, effet, cleanup et mesure réelle : **NOT_RUN**. Les tests purs des collecteurs sont séparés : acteur29contrôles PASS ; vanilla76contrôles PASS. Aucun total de réception n'est calculé.

Le problème est précis : `nativeLab=true` utilise la boucle LAB et ne mesure pas DirectorProductionRuntime. La voie opérateur normale exige APPROVED. Cette proposition ajoute une factory fermée `DECLARED_SYNTHETIC`, sans construire de DTO Calibration.APPROVED, sans fabriquer d'attestation et sans passer par un faux manifeste opérateur.

## Frontière de preuve et architecture

L'objet exécuté est la vraie classe DirectorProductionRuntime. Les mêmes poll, actorBudget, unités critiques/ordinaires, productionStore, WORLD bootstrap, runtimeClock, administration, gameplay, envelope mondial, J04, lifecycle EVENT et fermeture sont utilisés. Les données DEFAULT_FOR_TESTING et le catalogue candidat neuf/2/10/13 restent TEST_ONLY. Le rapport NinePorts conserve les classes et modes réels des neuf ports. Il est **distinct** d'une Composition(PRODUCTION) ou Composition(TEST_ONLY). Les graphes normaux et DirectorReleaseDiagnostic ne sont pas assouplis. Le diagnostic QA conserve `DECLARED_SYNTHETIC_DEFAULT_FOR_TESTING` et `OPERATOR_APPROVAL_NOT_OBTAINED` ; aucune réception production n'en découle.

Le router physique, ServerEffectPort, les bindings PRODUCTION effectivement qualifiées, les permits J04, les ACK/readbacks, la validation WORLD, l'audience, les sorties et l'ownership restent inchangés. Le catalogue TEST_ONLY sélectionne dans le banc explicitement autorisé ; il ne devient pas catalogue de production. La branche TEST_ONLY du survey de risque est conservée : le risque générique basé sur la géométrie réelle n'est pas la qualification du calcul PRODUCTION normalisé pluie/saturation/pente/activité.

Le mode n'est pas un booléen reçu d'un paquet. Inputs a un constructeur privé ; open vérifie flag, monde réel séparé, marker, whitelist, port25579, au plus deux vrais ServerPlayer, aucun FakePlayer, absence LAB/GROUP/manifest opérateur, pins des sources/candidat/fixture et parité des classes chargées avec le Director JAR. Les API/constructeurs normaux conservent leurs gardes. Les commandes LAB courtes sont refusées par ce content QA : elles ne peuvent pas remplacer la route administrative/native ciblée.

Bootstrap zéro client : le même lifecycle ouvre productionStore sur `proutlost/director`, lié à DirectorSavedIdentity créé par le jeu. La factory attend ensuite deux vraies sessions J06 négociées, vivantes et dans l'overworld ; elle réutilise le survey existant des huit blocs chargés/praticables pour les références du catalogue. Elle ne fournit ni joueur, ni région, ni risk, ni route libre depuis un payload. Pour la reprise d'un travail physique existant, la factory lit les vraies evacuationRoutes des DisasterInstance J04 conservées, même cut/session/têtes complètes et limite128, afin que le cleanup ne dépende pas de reconnexions. La naissance du dossier et du DisasterInstance est atomique avant effet. Ces routes historiques sont des données de validation, jamais une nouvelle permission ; les lectures physiques et gates de reprise restent natives. Aucun sidecar ne contourne J04.

L'autosélection d'événements reste désactivée dans ce mode QA, y compris lors de RESUME et des transitions de phase. Les commandes manuelles traversent les validations existantes. Le catalogue9événements/2chaînes est présent ; la recette cible seulement controlled_landslide. Les autres événements, chaînes et leurs gardes APPROVED ne sont pas déclarés qualifiés.

## Huit oracles définis avant recette native

1. Nouveau monde QA distinct, seed connu, UUID né par le jeu, ancien a02/a01 non copié ; même storeId réellement enregistré dans SavedIdentity/J04. Bootstrap zéro client, puis deux sessions J06 indépendantes. Les pins/main/Director/extra-addon et les classes chargées correspondent au candidat.
2. Référence au vrai objet ProductionRuntime, rapport QA9ports réels et leurs modes, WORLD PAUSED puis RUNNING seulement après l'ACK/readback administratif et santé mesurée. `composition()` production reste absente en QA et le diagnostic opérateur reste bloquant.
3. Région/lieux nécessaires reçus par la vraie admission G0/J04, puis lus dans J01/mémoire/J03 au cut cohérent. Le contrôleur conserve provenance et données QA explicites. Les régions ne sont pas inventées par cette factory ; une région/risk absent reste un refus exact à corriger par un travail QA légitime. Pas de choix auteur/canon ajouté.
4. Commande administrative réelle controlled_landslide → capture acteur → worker B07 → audit durable → envelope mondial → admission → WARN J06 réel. Pas d'effet avant PREPARE/permit/ACK ; vrai header/epoch/audience/lead-time. Les logs/captures des clients sont joints aux actions exactes, sans transformer un reçu transport en perception humaine.
5. DisasterStage.IMPACT réellement durable et véritable effet/RESULT relus. Pendant travail ordinaire borné et décision en vol, STOP/stabilize authentifié conserve la progression critique et interdit de nouveaux forwards. Les dépassements non préemptibles restent visibles.
6. STABILIZE puis terminal, RESULT, mutations et ownership relus dans J04 ; état physique/inverse/cession réellement observé selon la politique. Un ACK de commande n'est pas ce résultat. Aucune remise forcée au PRE ne fabrique la preuve.
7. Nouvelle JVM identifiée, même monde/store, nouveau boot ; lecture des données persistées et cleanup sans nouvel impact. Les clients reconnectés utilisent leurs nouvelles sessions. Un arrêt brutal n'est permis qu'au contrôleur qui possède cette JVM QA, aux frontières préparées, avec la copie restaurable conservée.
8. Baseline et régime utilisent le même observer extra. CSV vanilla et CSV CPU acteur séparés, jointure par tick natif exact, queues/dropped/erreurs/end durable explicitement vérifiés. CPU=-1 reste indisponible, pas zéro. Les contrôles santé utilisent les mesures natives et une pression réelle annoncée ; aucun enum/ACK/tempsvanilla injecté. Deux clients et DEFAULT_FOR_TESTING n'attestent ni20–25joueurs, ni endurance CDC, ni calibrations acceptées.

## Build et packaging

Intégrer seulement le manifeste ciblé, puis compiler le candidat main/Director via le build normal. L'addon est opt-in :

```text
--no-configuration-cache -I gradle/p008-production-runtime-qa.init.gradle p008ProductionRuntimeActorPureTest p008ProductionRuntimeVanillaPureTest p008ProductionRuntimeQaJar -Pp008RunId=<nouvelle-identité>
```

L'addon ajoute quatre classes sources et ses classes imbriquées sous `fr/proutlost/p008qa/production/` ; aucun test n'est embarqué. Le JAR task vérifie inventaire/parité exacte des classes. Il n'est pas dans directorJar/mainJar et n'ajoute aucun payload ou obligation aux clients. Serveur de recette :53mods du pack + cet addon QA54eJAR, clients53mods. Le tick-addon LAB antérieur reste gelé, n'est pas élargi et n'est pas installé pour cette recette nativeLab=false. L'effet et le coût de cet observer supplémentaire restent une limite déclarée.

Fixture statique calculée avec le vrai CanonicalJsonV1 pur : **fe942278ef25cad731dd0ca6fc97fe05a40c96cb3b48a3b5c300634772722d41**. Elle lie DEFAULT_FOR_TESTING rawSHAa0ba94df…cd81d et candidat-manifestSHA5091965a…83358. Le loader natif doit encore confirmer cette valeur ; une différence est un refus, pas une permission de changer la pin.

## Démarrage préparé par l'intégrateur

Créer une nouvelle copie/template de candidat sans monde/level.dat ni ancienne identité, namespace réseau privé distinct, ressources mesurées compatibles et nouveaux répertoires de preuves. Ne pas modifier les helpers fresh/lowheap gelés ; adapter une voie distincte si leur mode source ne correspond pas à ce nouveau store. Lancer Minecraft avec seed QA connu pour créer identité/monde, puis sceller le couple monde/J04 avant clients. Aucun UUID ou champ NBT n'est injecté. Configurer whitelist et max-players=2 sur ce nouveau QA. Pas de déploiement live.

Flags obligatoires factory, sans nativeLab et sans productionManifestSha256 :

```text
-Dproutlost.director.productionRuntimeQa=true
-Dproutlost.director.productionRuntimeQaRoot=<monde-qa-absolu-réel>
-Dproutlost.director.productionRuntimeQaFixtureSha256=fe942278ef25cad731dd0ca6fc97fe05a40c96cb3b48a3b5c300634772722d41
-Dproutlost.director.productionRuntimeQaSourcesSha256=<archive-sources-qualifiée>
-Dproutlost.director.productionRuntimeQaDirectorJar=<Director-JAR-absolu>
-Dproutlost.director.productionRuntimeQaDirectorSha256=<SHA256-exact>
```

Le monde contient seulement le marker `DECLARED_SYNTHETIC_RUNTIME_TEST_ONLY` avec le texte `PROUTLOST_DECLARED_SYNTHETIC_RUNTIME_QA_V1`. Ne pas créer d'operator-manifest, de preuve humaine ou de receipt G0/GROUP. Les pins sont liées au manifeste de build et ne valent pas une approbation.

Après bootstrap, connecter les deux vrais clients, attendre leurs négociations J06 et les placer sur de vraies sorties chargées/praticables. Fournir la région/lieux et le relief QA nécessaires par les vrais outils d'admission/collecte (pas d'injection d'aggregate/fact/IMPACT). Le relief et le risque doivent satisfaire les validators actuels ; sinon enregistrer le refus exact puis préparer un cas physique légitime. Les valeurs de plafond ne sont pas relevées.

Flags obligatoires observer (en plus de la factory) :

```text
-Dproutlost.qa.productionRuntime=true
-Dproutlost.qa.productionRuntime.mainJar=<main-JAR-absolu>
-Dproutlost.qa.productionRuntime.mainSha256=<SHA256-exact>
-Dproutlost.qa.productionRuntime.directorJar=<Director-JAR-absolu>
-Dproutlost.qa.productionRuntime.directorSha256=<même-pin-factory>
-Dproutlost.qa.productionRuntime.observerJar=<JAR-addon-absolu>
-Dproutlost.qa.productionRuntime.observerSha256=<SHA256-exact>
-Dproutlost.qa.productionRuntime.sourcesSha256=<même-pin-factory>
-Dproutlost.qa.productionRuntime.clientManifestSha256=<vrais-clients-qualifiés>
-Dproutlost.qa.productionRuntime.outputParent=<répertoire-existant-hors-monde>
-Dproutlost.qa.productionRuntime.runId=<id-neuf-max56caractères>
-Dproutlost.qa.productionRuntime.durationSeconds=<durée-bornée1..32400>
```

Les limites par flux sont650000lignes,128Mio et4096éléments de queue ; elles peuvent seulement être baissées via maxSamples/maxBytes/queueCapacity dans leurs bornes. Les presets ne garantissent pas un essai d'endurance : un cap de stockage peut terminer plus tôt avec résultat incomplet.

Attendre le runtime, ses vrais ports, les readbacks WORLD et un warmup mesuré. Démarrer explicitement `/p008_production_capture start`, consulter status, appliquer la recette manuelle authentifiée, puis `/p008_production_capture stop`. Attendre **les deux run-end.json durables avant l'arrêt de JVM**. Les writers daemon ne garantissent aucun flush à la sortie ; sans ces fichiers ou avec erreur/dropped, conserver l'interruption/incomplétude.

Le stream `<id>_vanilla/ticks.csv` contient la durée du précédent tick vanilla finalisé. Le stream `<id>_actor/actor-frames.csv` contient uniquement cpuStatus de la vraie boucle, indexé par `Integer.toUnsignedLong(server.getTickCount())` dans le candidat B08. Joindre frame_native_tick à completed_tick, jamais le temps mur. Des bornes décalées d'un tick sont normales selon l'ordre des listeners ; un manque intérieur ou une plage CPU nettement plus courte doit refuser la couverture commune. `phase_at_observation`, `health_at_observation` et players décrivent l'instant du Post, pas toute la frame précédente. Les p95 fenêtre embarqués sont des diagnostics ; recalculer les quantiles de campagne depuis les lignes brutes. Pas d'attribution CPUworker, criticalCPU ou serveur entier depuis ces fichiers.

Un helper joint distinct est la prochaine livraison d'analyse après gel des sources ; il ne modifie pas les outils capture/analyseR2 gelés. Les captures ne sont pas une preuve du nettoyage : joindre les états/ACK/ownership/lectures physiques et la timeline clients. Les réceptions humaines/canoniques et le verdict fullstack restent ouverts.
