# P008 — contrat des identités du collecteur natif de qualification

## Défaut observé et cause démontrée

Le root a obtenu le vrai rapport neuf ports R6. La tentative de capture du 20 novembre à 07:58:07 UTC a créé uniquement `P008-PRODQA-R6-PAIR-A01_nine-ports.json`, puis la commande a échoué sans créer les flux acteur/vanilla. Ce fichier et l'essai restent conservés ; une capture n'a pas démarré.

Cause déterministe : `ProductionRuntimeQaObserverMod.started()+begin()` fournit 22 clés d'identité, dont huit contiennent `sha256`. Les deux collecteurs imposaient `[a-z_]{1,40}`, qui interdisait les chiffres. `CompletedTickCapture` rejette donc `main_jar_sha256` avec `IllegalArgumentException: bounded identity entry` avant création du répertoire de capture. `ActorFrameCapture` porte le même défaut. Le rapport neuf ports est écrit avant cette validation, ce qui correspond précisément aux fichiers observés.

Les anciens tests utilisaient uniquement les clés `test_scope` ou `scope` et n'exerçaient pas le contrat réel transmis par l'observateur. Aucune défaillance de `directory.force(true)` n'est nécessaire pour expliquer ce défaut.

## Correction et portée

Quatre fichiers seulement : deux collecteurs, chacun avec l'unique remplacement de regex par `[a-z_][a-z0-9_]{0,39}`, et leurs deux classes de tests existantes. La première lettre reste minuscule ASCII ou underscore ; les chiffres sont permis ensuite. Les noms existants restent acceptés. Les bornes **32 clés, 40 caractères par clé, 1 024 caractères par valeur**, les limites des queues, lignes, durée et stockage, la parité d'identité, les digests et les critères d'END ne changent pas.

Les deux tests sont déjà inclus dans `p008ProductionRuntimeQaTest` par le Gradle courant. Aucun nouveau framework, import Gson, tâche ou fichier de production supplémentaire. Aucun changement des permissions, de la factory, des profils, du monde/J04 ou du wire. Aucun résultat canonique/humain n'est créé.

Le manifeste fournit les quatre préimages SHA exactes. Le root intègre séquentiellement ces fichiers dans sa base R7 courante après vérification des préimages, conserve le candidat/essai R6, puis reconstruit l'addon de qualification avec les nouvelles sources. Les hashes des binaires réels et leur correspondance avec le candidat courant doivent être repris dans un nouveau plan ; ne pas modifier les helpers R5/R6 déjà figés ou substituer le nouvel addon derrière leur ancien pin.

## Régression vérifiable

Les nouveaux tests construisent **les 22 noms exacts** de `started()+begin()`, avec des valeurs explicitement déclarées fixtures JDK. Ils ouvrent chaque collecteur, envoient une vraie ligne de fixture au sink, attendent son END et vérifient chaque clé/valeur dans les manifestes début et fin. Ils conservent les anciens tests de pertes, erreurs, durabilité, collisions, limites et disponibilité CPU.

Contrôles supplémentaires : cardinalité 32 acceptée et 33 refusée, clé de 40 caractères acceptée et 41 refusée, valeur de 1 024 acceptée et 1 025 refusée, noms vides/majuscule/punctuation/Unicode/premier chiffre/newline refusés, valeur nulle refusée. Tout refus doit arriver avant création du répertoire de preuve.

- RED sur les collecteurs canoniques non corrigés : les deux nouvelles régressions échouent précisément avec `bounded identity entry`. Journaux conservés.
- GREEN Java21 strict, après les deux seules corrections de regex : `CompletedTickCaptureTests` **183 contrôles PASS** ; `ActorFrameCaptureTests` **136 contrôles PASS**, portées distinctes. Le message stderr de collision finale volontaire de la suite vanilla appartient à son ancien test négatif et est conservé.
- Les six sorties de fixture succès sont relues indépendamment avec le parseur JSON Python : identités complètes égales, nombres de champs attendus, digests CSV exacts et END complets. Ce sont des fixtures, pas une capture Minecraft ni une attribution CPU réelle.
- Revue indépendante des quatre fichiers et du contrat natif, sans blocage identifié.

## Acceptation native à définir et exécuter sur la nouvelle JVM

1. Préserver intégralement l'essai R6 échoué, son fichier neuf ports et son monde fermé. Utiliser un nouveau plan, une nouvelle racine traçable et les vrais pins du candidat/addon courants. Ne supprimer aucun fichier et ne rejouer aucun run ID pour transformer cet essai en succès.
2. Connecter deux nouveaux profils clients réels courants, observer les deux J06, le vrai runtime et le rapport neuf ports. Dans cette voie non cohorte, l'autosélection d'événements reste désactivée ; PAUSED est une phase admise pour démarrer le collecteur.
3. Fixer le minimum de durée **avant** la capture. Une fenêtre configurée de 180 s produit nécessairement une plage commune entre lignes légèrement inférieure à 180 s à cause des bornes discrètes. Si le critère retenu exige 180 s communes, choisir dans le **nouveau** plan un preset de 190 s et `--minimum-seconds 180`, avec les mêmes plafonds contractuels ; ne pas abaisser le critère après les résultats ou modifier les arguments en vol.
4. Envoyer une seule fois `p008_production_capture start` par la commande réelle OP4, liée à la JVM possédée. Exiger `P008_PRODUCTION_QA_CAPTURE_STARTED`, les deux répertoires et les vrais `run-start.json`. L'apparition du seul fichier neuf ports ne suffit pas.
5. Observer la progression native. Si la recette inclut un changement de contrôle, utiliser `/proutlost director resume` depuis le vrai client OP4, attendre SUCCEEDED et le WORLD/J04/phase RUNNING relus ; garder les phases réellement mesurées. Le succès neuf ports ne prouve pas les effets des neuf événements.
6. Attendre les **deux** `run-end.json` : `end_observed=true`, `end_incomplete=false`, plage contiguë/drainée, aucune perte/invalid/gap, CSV/hash/comptes exacts. Les identités doivent conserver les vrais pins et la géométrie/session observées. CPU `-1` reste indisponible.
7. Exécuter l'analyse gelée de jointure par tick natif avec le minimum préfixé ; garder un résultat partiel/refusé si les critères ne réussissent pas. Puis sauvegarde, arrêt normal identifié, ACK natifs, fin pidfd et lectures fermées. Conserver tout le monde/J04 et les preuves.

Le critère de fin de ce défaut est la création et la clôture réelles des deux flux sur les binaires courants, avec leurs identités exactes et leur analyse intègre. Une compilation, une fixture ou le rapport neuf ports seul ne le ferme pas. Effets, charge 20–25, canon et réception humaine restent des scénarios distincts.
