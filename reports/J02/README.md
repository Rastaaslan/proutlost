# J02 — catalogue spec 1.0.0

Noyau catalogue : IMPLEMENTE_EN_SOURCE / COMPILE / TESTE, accepté et intégré par Work au commit externe `f3ac80f333a83e7923d5aed8cda796c890d66fd5`. Complément runtime candidat : IMPLEMENTE_EN_SOURCE / COMPILE / TESTE sur contrats de test. Projection scoring et publication atomique API 1.0.1 : IMPLEMENTE_EN_SOURCE / COMPILE / TESTE. Overlay commun exact importé sur autorisation Work ; aucune modification indépendante de contrat. Le HEAD managé reste `9d895ecf335f2cc7770f7f89b328c48e5e3173d3` ; l'archive G0 importée est une baseline immuable distincte, pas un commit créé dans ce worktree.

## Composition

`DefinitionLoader` implémente `CatalogPort`. L'intégrateur lui fournit un `ConfigView` issu du registre de configuration validé, un `PrimitiveRegistry`, un `ContentReferences`, le `ProviderMode` et des `Limits` techniques explicites. Les DTO de configuration constituent une entrée de confiance de composition ; les JSON de contenu ne peuvent créer ni clé de configuration, ni provider, ni capability. Le catalogue ne dépend que du JDK21 et des DTO/ports API, sans import d'implémentation voisine.

`ContentReferences` est une copie immuable des registres disponibles : références séparées par type, profiles de mutation/WARN/dégâts, types de dégâts enregistrés (`Kind.DAMAGE_TYPE`), capabilities et noms des resolvers de claims. L'enregistrement signifie disponibilité attestée par la composition ; le catalogue n'exécute pas et ne qualifie pas les effets. J05/J06 doivent fournir leurs vrais providers et preuves de qualification. Les descriptors supplémentaires G0 restent enregistrables ; les JSON v1 acceptés suivent exactement les onze variantes du schéma normatif. Les six contrats physiques complémentaires G0 ne sont pas présentés comme des recettes JSON supplémentaires déjà qualifiées.

Appeler `readDocument` sur un worker IO et `reload` sur un worker CPU borné. Aucune de ces opérations n'appartient au tick acteur. `current()` est une simple lecture atomique. Les inputs portent le chemin `data/<namespace>/director/events|chains|scoring/<path>.json`, vérifié contre l'ID. Les fixtures authentiques conservent leurs IDs `proutlost:*` ; cela ne renomme pas le mod technique `proutlost_director`.

Le chargement contrôle octets/documents/nœuds/profondeur, UTF-8, doublons, schéma fermé, références typées, paramètres, rôles, graphes, durée, budgets cumulés, capabilities, ownership, recettes et WARN. Les erreurs portent fichier, JSON pointer et `CATALOG_INVALID`. Aucun catalogue partiel n'est publié. Les étapes sont ordonnées topologiquement ; les transitions de chaîne sont résolues/validées dans le même catalogue. Les dimensions IO/payload/navigation absentes du JSON sont prises dans les coûts minimaux du registre, jamais oubliées.

La publication utilise un CAS unique. Échec ou course perdue : aucun remplacement par le candidat invalide/périmé. Les anciennes références `CatalogSnapshot`/`EventDefinition` restent immuables ; le runtime les conserve pendant les instances et J04 conserve leur encodage content-addressed pour recovery. Le catalogue n'est pas un store de recovery. Les révisions identiques avec contenu différent et les régressions sont rejetées ; les tombstones de révision sont gardés pendant la vie du loader, y compris après retrait. Une borne explicite refuse leur accumulation excessive. Un reload identique conserve génération et identité du snapshot.

## Catalogue MVP et production

Les neuf événements et deux chaînes authentiques sont dans `src/directorTest/resources/catalog`, avec couverture des dix familles, de la présence micro, des trois catégories et des quatre scopes d'unicité. Ils restent les fixtures authentiques de qualification, séparées du contenu runtime candidat désormais livré ; aucun des deux ne constitue un gameplay approuvé. Les onze goldens de DTO compilés ont été construits séparément à partir des fixtures/résolutions et du contrat d'encodage G0, puis comparés octet pour octet dans la suite Java.

Les providers de `CatalogFixtures` sont exclusivement des contrats synthétiques `TEST_ONLY` en source de test. Ils n'appliquent aucun effet. Aucun provider de test ni testkit n'a été ajouté aux sources gameplay. Le contenu runtime candidat est maintenant présent dans `src/director/resources/data/proutlost_director/director/{events,chains}/candidate/` ; ses neuf événements et deux chaînes gardent les recettes/IDs de primitives normatifs et la provenance DEFAULT_FOR_TESTING. Le manifeste candidat donne les hashes et la copie byte-exacte du profil original. Les IDs de définitions et leurs références internes utilisent `proutlost_director:candidate/*`. La production refuse configuration/contenu DEFAULT_FOR_TESTING et providers/registres TEST_ONLY. La calibration et les capabilities physiques/client réelles restent des dépendances d'intégration, pas des fonctionnalités remplacées par un no-op. Le schéma de chaîne normatif 1.0.0 et son DTO G0 n'acceptent que DEFAULT_FOR_TESTING ; J02 respecte cette restriction et n'invente pas une approbation production de chaîne.

## Validation exécutée

Depuis la racine du worktree :

```sh
python3 src/directorTest/java/fr/proutlost/director/catalog/run-tests.py
python3 src/directorTest/java/fr/proutlost/director/catalog/verify-inputs.py
git diff --check
```

Résultats du noyau conservé : javac Java21 `--release 21 -Xlint:all -Werror` PASS ; T-CAT/goldens/invalides PASS, **364 assertions et 83 cas nommés** ; inputs/intégrité PASS, **1296 contrôles**, dont la baseline G0 historique et les 62 fichiers du nouvel overlay autorisé ; whitespace PASS.

Le runner ne compile que J02 contre le JAR API 1.0.1 de SHA-256 imposé. Chaque javac/JVM est séquentiel, nice19, ionice idle, affinité CPUs10,11, ActiveProcessorCount=1, heap 384MiB/256MiB. RAM>=3GiB, disque>=8GiB et PSI IO full avg10<15 sont revérifiés avant chacun. Commandes exactes, ressources mesurées, hashes des sources et logs : `TEST_RESULTS.json`. Contrôles d'origine et baseline : `INPUT_AND_INTEGRITY.json`.

Le premier lancement du helper avait une erreur de calcul du chemin racine et s'est arrêté avant compilation ; la tentative de création hors worktree a été refusée en lecture seule. Le chemin a été corrigé. Aucun test n'a été déclaré passé sur ce lancement. Toutes les suites Java réellement lancées ensuite sont passées ; les extensions de tests et le durcissement du code ont été requalifiés.

NOT_RUN : Gradle, serveur NeoForge, GameTests, clients natifs, effets physiques réels, campagne commune G1/architecture. Aucun slot Work demandé ni présumé. Les fixtures préparent la qualification des capabilities, mais aucun GameTest serveur n'est présenté comme une preuve de rendu client. Ce lot pur n'ajoute pas d'adaptateur plateforme nécessitant une compilation MC/NeoForge.

## Intégration

Intégrer uniquement les fichiers J02 listés dans `HANDOFF.json`. Ne pas réintégrer la baseline importée. L'intégrateur raccorde le loader au bootstrap, fournit les registres de production qualifiés et enregistre le point d'entrée `fr.proutlost.director.catalog.CatalogTests` dans la campagne commune. Le runner local inclut `src/director/resources` pour les schémas ; les tâches communes doivent conserver ce classpath de ressources. Aucune migration de sauvegarde ni modification de build n'est fournie par J02.

## Correction de revue — itération 1

`DefinitionValidator` résout désormais chaque entrée de `DamageProfile.damageTypes` dans le registre typé `DAMAGE_TYPE`. Un ID inconnu, même mélangé à un type connu ou présent dans un autre registre, provoque `CATALOG_INVALID` au JSON pointer `/steps/1/args/damage_profile` de la fixture éboulement ; le détail identifie le type non résolu. Les tests installent d'abord un catalogue non vide, puis vérifient que le reload invalide conserve son identité, sa génération et ses événements. Le cas positif avec `minecraft:falling_block` enregistré reste couvert. La composition doit fournir les types de dégâts réellement disponibles dans ce registre ; aucune API commune G0 n'est modifiée.

Validation après correction : compilation Java21 stricte PASS ; 364 assertions / 83 cas nommés PASS ; 1120 contrôles d'intégrité PASS ; `git diff --check` PASS. Commandes et nouveaux digests conservés dans les mêmes rapports. Les validations natives restent NOT_RUN.

## Compléments demandés par Work après acceptation du noyau

Le contenu runtime candidat comporte 11 définitions chargées depuis le classpath de production par `RuntimeCandidateTests`. Les fixtures de spec originales et leurs goldens restent séparés et inchangés. Les valeurs quantitatives sont toujours des `@test:` vers le profil original copié byte pour byte sous `director/profiles/`. Le manifeste `director/candidate-manifest.json` est explicitement CANDIDATE, non approuvé production. Ce manifeste et les profils ne sont pas des événements à passer au loader.

`RUNTIME_BINDINGS.json` donne à Work les IDs de descriptors, capabilities, recettes, assets et autres références attendus. J02 ne lit ni n'importe les implémentations J05/J06. Les 86 assertions runtime valident les ressources livrées avec des registres de contrats TEST_ONLY et leur rejet lorsque providers/assets manquent. Elles ne qualifient pas les providers réels. La compatibilité effective J05/J06, l'audio, les effets physiques et le serveur restent NOT_RUN.

Le compagnon de scoring est décrit dans `SCORING_COMPANION.md`. Le loader compile les profils vers DefinitionScoringProfile et publie catalogue, profils et historiques de révisions par un seul CAS. Les contextes exigent une liaison explicite Condition/poids ; les références inconnues échouent. La conservation des anciennes générations, les changements combinés et metadata-only, les rejets et les writers concurrents sont testés.

Dernière validation : compilation stricte contre le JAR API 1.0.1 PASS, noyau 364 assertions/83 cas PASS, ressources runtime 86 assertions PASS, scoring/projection/publication 101 assertions PASS, soit 551 assertions Java réellement exécutées. Vérification inputs/intégrité : 1296 contrôles PASS. Commandes et digests dans TEST_RESULTS.json. Le contenu reste un CANDIDAT de démonstration, sans qualification gameplay/native ni calibration finale.

L'overlay 1.0.1 a été importé sur autorisation explicite Work, sans recompiler ni modifier les contrats communs. Les 62 fichiers exacts et leurs empreintes sont dans API_BASELINE_ACK_1.0.1.json ; les archives G0 restent intactes. La qualification Work rapportée n'est pas comptée comme une exécution locale. HEAD reste BASE_COMMIT ; aucun merge, commit, push ou déploiement.
