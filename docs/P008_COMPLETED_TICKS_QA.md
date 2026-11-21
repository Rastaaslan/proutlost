# P008 — addon QA de durées de ticks serveur complétés

État : **proposition isolée ; tests purs sink/reader exécutés ; compilation native, JAR installé, handshake avec clients et capture Minecraft NOT_RUN**. Ce module TEST_ONLY supplémentaire n'est pas le candidat de production et ne ferme aucune exigence CDC. Aucun monde, source canonique, addon G0 ni processus distant n'est modifié par cette proposition.

## Scénario observable

Avant correction/installation : une exécution installée ne fournit pas encore un CSV de chaque durée vanilla complétée avec identité binaire et une fin contrôlée. Après installation sur un QA possédé explicitement marqué : les vrais `ServerTickEvent.Post` alimentent un sink borné, lisant uniquement `getTickCount()-1` dans `getTickTimesNanos()`. La CSV scellée et son receipt conservent nombre de lignes, SHA, plage, pertes/gaps/invalides, limites, identité monde/JVM/JARs et portée. L'analyse doit retrouver exactement les durées copiées, sans les estimer à partir de l'intervalle Post→Post ou des moyennes Minecraft périodiques.

Ce scénario attend encore la compilation et le passage natifs. Les tests locaux font varier un ring synthétique déclaré et le sink sur dossiers temporaires ; ils ne sont jamais une capture Minecraft.

## Portée exacte

- Seulement durée **du tick précédent finalisé**, copie d'une case scalaire du ring vanilla au Post courant. Le slot courant n'est pas encore réputé finalisé ; les deux premiers Posts non utilisables sont explicitement comptés comme startup skipped. Le dernier tick courant est intentionnellement hors segment.
- La colonne `players` est le nombre de ServerPlayer non FakePlayer **au moment de l'observation Post courante**. Elle ne prouve pas le membership pendant tout le tick précédent, notamment aux frontières connexion/déconnexion, ni une négociation J06 ou une activité client. Ces preuves restent corrélées par l'intégrateur aux vrais clients.
- Les colonnes CPU restent vides : ni CPU Director global, ni CPU critique, ni CPU worker n'est mesuré. Aucun listener Pre/Post n'est présenté comme attribution CPU Director.
- Le flag `proutlost.director.nativeLab` est celui réellement utilisé dans `DirectorStandaloneMod.java:37` et dans les fences QA existantes. Les classes candidates vérifiées sont réellement `fr.proutlost.Proutlost` et `fr.proutlost.director.neoforge.DirectorStandaloneMod` (packages/déclarations inspectés dans les sources).
- Cette fence garde **au plus deux** vrais ServerPlayer, port25579, whitelist et monde exact marqué. Elle ne lève aucune garde GROUP ni ressource. La capture peut contenir0/1/2 joueurs ; un segment revendiqué deux clients exige des preuves externes stables de deux connexions/activité.
- Aucun import d'API Director/WorldPrep, aucune ouverture J04, requête d'admission, permit, mutation, génération ou chargement de chunk. L'addon écrit seulement dans son dossier de preuves séparé du monde. Les vérifications de marker/JARs et la création du start receipt sont faites à ServerStarted : ce coût de préparation et le démarrage complet de Minecraft ne sont pas qualifiés par ce CSV.

## Intégration ciblée et build

Copier uniquement les fichiers listés par `P008_COMPLETED_TICKS_PROPOSAL_MANIFEST.json`. La voie recommandée utilise l’init-script explicite et ne modifie aucun `build.gradle` canonique. Si le propriétaire veut enregistrer durablement ces seules tâches QA, il peut à la place appliquer **une fois** cette ligne à la fin du build en conservant ses autres modifications :

```groovy
apply from: 'gradle/p008-completed-ticks-qa.gradle'
```

Le fragment ne registre pas de mod dans les runs ordinaires ; il ne modifie ni `jar` ni `directorJar`. Les tests purs compilent uniquement sink+reader+tests JDK. Le JAR QA compile son propre source set avec le classpath NeoForge/Minecraft du projet et ne contient que `fr/proutlost/p008qa/ticks/**` et ses métadonnées ; un contrôle de parité compare les classes compilées/embarquées.

```text
./gradlew --no-daemon --no-configuration-cache -I gradle/p008-completed-ticks-qa.init.gradle p008CompletedTickPureTest -Pp008RunId=<nouvel-id>
./gradlew --no-daemon --no-configuration-cache -I gradle/p008-completed-ticks-qa.init.gradle p008CompletedTicksQaJar
```

La tâche pure refuse de réutiliser son ancien dossier de preuves ; prendre un identifiant neuf. Le toolchain cible vérifié dans `gradle.properties` est Minecraft1.21.1, NeoForge21.1.248, Java21. Binaire attendu : `build/libs/proutlost-p008-completed-ticks-qa-1.0.0-TEST_ONLY.jar`. Contrôler les entrées, versions et SHA avec la parité des JARs candidats main/Director réellement installés.

## Installation QA séparée, clients optionnels

Installer **uniquement côté serveur QA**. Les métadonnées ont `displayTest=IGNORE_ALL_VERSION`, dépendances SERVER, et aucun payload/channel/renderer/capability enregistré. Le constructeur refuse le côté client et l'absence de flag TEST_ONLY. Les deux profils clients à53mods ne reçoivent pas ce JAR ; l'addon observateur ajoute un JAR serveur (54e si aucun autre addon QA n'est déjà présent ; davantage si l'observateur G0 séparé y est aussi installé). La connexion/négociation avec ce JAR serveur supplémentaire reste **à vérifier nativement**, pas déduite de ces métadonnées.

Baseline et essai Director doivent employer le même addon/configuration/contrôleur et déclarer son overhead. Le scénario strict de la fence exige les JARs candidats et nativeLab ; une baseline sans Director peut laisser son JAR chargé avec ses fonctions d'activité désactivées, si ce protocole est explicitement établi. Ne pas appeler une baseline ainsi préparée « sans aucun JAR Director ».

Le propriétaire crée dans le monde QA exact le fichier régulier non-symlink `P008_COMPLETED_TICKS_TEST_ONLY` contenant `PROUTLOST_P008_COMPLETED_TICKS_QA_V1` (newline final autorisé). Il choisit un output parent existant absolu, non-symlink, séparé du monde, et un runId neuf. Aucun QA00–05 conservé ou identifiant consommé n'est réinitialisé.

Options JVM (valeurs concrètes vérifiées par le propriétaire) :

```text
-Dproutlost.director.nativeLab=true
-Dproutlost.qa.completedTicks=true
-Dproutlost.qa.completedTicks.worldRoot=<monde-QA-absolu>
-Dproutlost.qa.completedTicks.outputParent=<preuves-absolues-hors-monde>
-Dproutlost.qa.completedTicks.runId=<identifiant-neuf-1-a-64-caracteres>
-Dproutlost.qa.completedTicks.mainJar=<jar-main-installe-absolu>
-Dproutlost.qa.completedTicks.mainSha256=<sha256-main>
-Dproutlost.qa.completedTicks.directorJar=<jar-director-installe-absolu>
-Dproutlost.qa.completedTicks.directorSha256=<sha256-director>
-Dproutlost.qa.completedTicks.collectorJar=<jar-addon-installe-absolu>
-Dproutlost.qa.completedTicks.collectorSha256=<sha256-addon>
-Dproutlost.qa.completedTicks.sourcesSha256=<sha256-sources-qualifiees-externe>
-Dproutlost.qa.completedTicks.clientManifestSha256=<sha256-manifeste-clients-verifie-externe>
-Dproutlost.qa.completedTicks.maxPlayers=2
-Dproutlost.qa.completedTicks.durationSeconds=900
-Dproutlost.qa.completedTicks.maxSamples=650000
-Dproutlost.qa.completedTicks.maxBytes=134217728
-Dproutlost.qa.completedTicks.queueCapacity=4096
```

L'addon recalcule SHA des trois JARs bornés64Mio et compare les ressources `.class` réellement chargées à leur entrée dans les JARs nommés. Source/client manifest SHA sont des références externes vérifiées par l'intégrateur, pas une inspection automatique de clients distants. Aucun autre client ni attribution humaine n'est inventé.

## Bornes, comptage et fin

Un seul acteur producteur ; queue non bloquante bornée1–4096 samples ; un writer distinct possède le CSV. Aucun IO dans offer()/lecture au Post. Valeurs admises : durée1–32400sec ;1–650000samples ; sortie totale bornée131072–134217728octets, réserve métadonnées comprise. Les durées sont en nanosecondes exactes ; les métriques CPU non observées restent vides, jamais zéro. `System.nanoTime` fournit les timestamps monotones d'observation ; ils ne remplacent pas la durée vanilla.

`source_samples` compte les observations distinctes consommées, y compris une durée invalide ; `queued_samples` les lignes réellement acceptées ; `written_samples` les lignes écrites ; pertes de queue/invalides/doublons/gaps et acceptés non écrits restent séparés. Une queue pleine ne bloque pas le tick : elle incrémente dropped et rend le segment incomplet. Régression de tick/horloge, ring absent, IO ou dépassement stockage empêchent une fin complète. Si un STOP normal est suivi d'un drain tronqué par la limite de stockage, le motif STORAGE_LIMIT reste visible. Un output existant n'est jamais volontairement remplacé ; la directory de run est créée exclusivement.

Le start receipt est `RECORDING_OR_INTERRUPTED`, `end_incomplete=true`. Le writer force le CSV, puis force un receipt temporaire avant publication atomique du end receipt. La durabilité matérielle du rename dépend du filesystem/OS ; aucune garantie de panne électrique supérieure à la plateforme n'est revendiquée. Le writer est **daemon** : l'exit de la JVM n'en garantit pas le flush.

Procédure requise pour un segment complet :

1. Laisser le scénario échantillonner ses actions réelles et conserver leur timeline/logs/captures.
2. Exécuter l'OP4 `/p008_tick_capture stop` (ou attendre sa borne), puis `/p008_tick_capture status` et contrôler `run-end.json` effectivement publié, hash/lignes/identity et `end_incomplete=false` **avant d'arrêter la JVM**.
3. Si la JVM s'interrompt avant ce contrôle : conserver start/CSV/`.pending` et déclarer le segment interrompu/incomplet. Un `run-end.json` absent, invalide, collisionné ou SHA/compte incohérent ne devient jamais un PASS.

`end_incomplete=false` signifie seulement plage de ticks **observés** contiguë, valide et queue drainée ; cela n'atteste ni durée de charge demandée ni576000ticks/8h. Le end receipt conserve aussi `capture_wall_elapsed_ns` et âge de la dernière observation : un gel/stall terminal sans nouveau Post ne doit pas disparaître en calculant uniquement le débit entre première/dernière ligne. Comparer la durée murale du segment et la timeline du contrôleur ; `full_wall_window_coverage` reste non établie par les seules lignes observées. Le dernier tick courant n'est pas inclus.

CSV compatible `analyze_l6_capture.py` :

```text
completed_tick,wall_monotonic_ns,tick_duration_ns,players,director_actor_cpu_ns,director_worker_cpu_ns,critical_cpu_ns,decision_cpu_ns
```

Avant analyse, valider receipt, SHA, lignes et identités. L'analyse R2 refusera une durée absente et signalera toutes les colonnes CPU `UNMEASURED`. Ne pas appeler le tick rate entre observations une moyenne TPS couvrant un stall terminal ou toute la phase non observée. Le CPU global/critique/worker et le plateau mémoire après GC restent des instruments/qualifications séparés.

## Qualification locale obtenue et recette restante

Compilation Java21 stricte du sink/reader/test JDK : PASS. Suite pure76contrôles : PASS, couvrant slot précédent contre courant, copie immutable, unsigned tick, démarrage, ordre/doublons/gaps, durée invalide, producteur unique, borne durée/samples/queue/stockage, IO et preuve existante conservée, comptage/digest/colonnes CPU vides. Un RED réel a précédé la correction du motif STORAGE_LIMIT après soft stop ; dossiers et logs antérieurs conservés. Ces nombres ne sont pas additionnés aux suites P007/B07 et ne qualifient ni Minecraft ni clients.

Restent : compilation/parité JAR native ; installation server-only sur copie QA ; connexion des deux clients53mods ; capture d'une séquence connue de ticks réels avec changement de charge/sauvegarde ; STOP puis end scellé avant exit ; validation de l'overhead/CPU/IO du collecteur ; contrôle d'un segment réellement interrompu sans résultat fabriqué. Aucun FULLSTACK_ACCEPTED ni scénario20–25joueurs n'est revendiqué.
