# WORLD_DIRECTOR_TECH_SPEC

Version normative 1.0.0 · 23 octobre 2026 · statut : conception livrable, implémentation future.

## 1. Périmètre et vocabulaire normatif

MUST = obligatoire ; SHOULD = recommandé avec justification documentée d'une dérogation ; MAY = facultatif. Les décisions produit de la mission jointe prévalent sur ce dossier. Ce document organise leur exécution ; il ne réapprouve pas des choix de contenu.

Le Director est le système autonome du monde. Il fonctionne sans quête et sans IA. AMBIENT, HYBRID, NARRATIVE sont des catégories d'origine, distinctes des familles de contenu. Les régions sont des identités logiques évolutives, distinctes des chunks. Une perception client n'est pas une modification physique. Un fait technique persistant n'est pas automatiquement une entrée de journal visible par un joueur.

Les nombres de réglage cités dans les contrats se lisent exclusivement depuis `DEFAULT_FOR_TESTING.json`. `@test:clé` désigne une valeur de ce profil. Les versions, énumérations, champs obligatoires, unités et règles de cohérence sont figés par la spec. Les valeurs du profil sont `DEFAULT_FOR_TESTING`, même lorsqu'un exemple semble directement jouable.

## 2. Invariants non négociables

| ID | Invariant | Contrôle d'implémentation |
| --- | --- | --- |
| INV-01 | Le Quest Core consomme le Director ; il ne le possède pas | Test de démarrage sans adaptateur quête |
| INV-02 | L'IA propose parmi des possibilités connues ; elle ne décide jamais de la sécurité | Port IA sans accès monde et revalidation après réponse |
| INV-03 | Toute lecture/écriture d'objet Minecraft vivant reste sur son thread autorisé | Architecture + assertions de thread |
| INV-04 | Plusieurs événements peuvent coexister | Réservations globales/régionales, pas de champ singleton `activeEvent` |
| INV-05 | REQUIRE ne contourne ni ownership, ni danger, ni cooldown de sécurité | Voie d'admission commune, requête durable en attente |
| INV-06 | Un joueur modifiant volontairement une trace en reprend le contrôle par défaut | Tombstone de cession et rollback conditionnel |
| INV-07 | Le physique est partagé ; le perceptif peut être asymétrique | Aucun faux bloc avec collision client divergente |
| INV-08 | Chaque événement/effet a une fin et une stratégie de reprise | Durée maximale, cleanup idempotent, deadline de récupération |
| INV-09 | Les marqueurs d'unicité, faits narratifs et conséquences durables ne s'effacent pas par un simple decay | Ledgers persistants distincts des caches |
| INV-10 | Pas de scan permanent du monde, ni de trajectoires précises persistées | Index de zones actives, compteurs agrégés |
| INV-11 | Les streamers n'ont aucun bonus implicite | Absence de statut streamer dans le scoring |
| INV-12 | Un événement hors quête n'alimente le carnet du joueur que sur son choix | Journalisation technique séparée du carnet |
| INV-13 | Le monde peut changer substantiellement, sous contrôle | Recettes physiques journalisées et testées, pas de réduction au cosmétique |
| INV-14 | Aucune simulation, force admin ou IA ne peut contourner les hard validators | API unique d'admission |
| INV-15 | Les grands dangers ont des signes lisibles et un temps de réaction | Phase WARN persistante avant IMPACT |

## 3. Socle et choix d'architecture

La cible de conception est Minecraft 1.21.1, NeoForge 21.1, Java 21. Le moteur utilise des records/interfaces Java sans classes client ni dépendance directe à un `ServerLevel`. Les ports portent des DTO immuables ; les adaptateurs sont les seuls à convertir les coordonnées, identifiants et snapshots Minecraft. Un serveur dédié doit pouvoir charger le JAR sans charger `net.minecraft.client`.

Les payloads sont enregistrés par les API NeoForge de la branche 1.21.1. Les données de chunk/entité utilisent des attachments sérialisés ; les informations globales peuvent être projetées dans un `SavedData` de l'Overworld, sans lui déléguer le journal critique. Ces choix sont fondés sur la documentation officielle 1.21.1 ; les détails du moteur, du WAL et des algorithmes ci-dessous sont des décisions de cette spécification, pas des garanties de NeoForge. Voir [sources](WORLD_DIRECTOR_ARCHITECTURE.md#sources-techniques).

Un seul `DirectorRuntime` par serveur possède le store logique, les réservations et les files. Son acteur tourne sur le server thread par petites tâches. Un worker CPU travaille uniquement sur des copies ; un writer IO sérialise les transactions ; un transport IA est isolé. Aucun verrou bloquant ou `join()` n'est autorisé depuis le server thread.

## 4. Horloges et déterminisme

`simTick` est un compteur monotone du Director, indépendant du jour Minecraft, de `/time` et de l'horloge civile. Il avance avec les ticks du serveur, même sans quête. Il est stocké avec les transactions/checkpoints. Une panne peut perdre des ticks sans effet durable ; elle ne peut faire reculer sous le dernier tick durable. Les délais de gameplay sont des durées en `simTick` ; les timeouts IO/IA utilisent une horloge monotone process ; UTC sert seulement aux audits et diagnostics.

Les durées d'une instance n'avancent qu'en état RUNNING/WARN/IMPACT, suivant son contrat ; son âge global et la deadline de reprise restent bornés séparément. Le temps serveur arrêté n'accélère ni un incendie ni une migration. Le temps déconnecté d'un joueur ne crée pas de dette d'attention. Un changement de jour Minecraft peut influencer le type d'ambiance, jamais augmenter par défaut le débit nocturne.

Chaque décision stocke `decisionId`, état de l'algorithme, versions de catalogue/configuration, hash du contexte, ordre canonique des candidats, RNG `SplittableRandom` Java 21 alimenté par une seed enregistrée, tirages et contribution IA retenue. L'audit capture les features nécessaires au replay, pas le monde entier. Rejouer un audit signifie rejouer le calcul ; les effets restent interdits sans une nouvelle admission.

## 5. Boucle d'exécution

1. Les hooks normalisent les observations et mettent à jour des agrégats bornés.
2. L'acteur entretient les instances et les tâches de cleanup prioritaires à chaque tick, sans recalculer toute la sélection.
3. Aux cadences différenciées, les capteurs rafraîchissent les snapshots des seuls joueurs/zones actifs et les mémoires.
4. Le générateur énumère des triplets `(définition, région, attribution de rôles)` à l'aide d'index famille/tags/capabilités.
5. L'éligibilité élimine les conditions dures ; le scoring ordonne le reste.
6. Une sélection pondérée fournit un plan, avec assistance IA facultative dans une deadline bornée.
7. L'acteur revalide le contexte vivant, l'audience, les capacités, l'unicité, les claims et les budgets ; il réserve atomiquement les ressources logiques.
8. Le writer confirme le PREPARE durable ; l'acteur revalide encore avant la première action monde.
9. Le runner applique des tranches bornées, vérifie chaque effet et consigne leur résultat.
10. La transaction de résultat met à jour état, mémoire, unicité et outbox. Les consommateurs reçoivent ensuite les faits.

Un contexte expiré provoque `STALE_CONTEXT` et une nouvelle observation ciblée. Il ne justifie pas un chargement forcé du monde ni un accès worker au monde vivant. Une déconnexion entre sélection et effet invalide les rôles concernés. Une instance en échec ne bloque pas les instances indépendantes.

## 6. Éligibilité et ciblage

L'éligibilité vérifie : définitions actives et validées, dimension et chunks disponibles, capabilités client/serveur, nombre de rôles, état de récupération/combat/décès/téléportation, cooldowns de sécurité, unicité, protections explicites, budget prévisible et absence de conflit non résoluble. `PREFER` n'efface aucun rejet. Un `REQUIRE` ajoute une obligation durable à planifier ; il n'ajoute pas artificiellement une cible non admissible.

Les groupes proviennent d'une proximité agrégée récente, pas de Discord, du vocal ou d'une relation psychologique supposée. Les rôles sont attribués par recherche bornée sans doublon, sauf rôle explicitement partageable. Le ciblage d'un groupe utilise la moyenne plafonnée des scores d'équité, pas la somme, afin de ne pas évincer systématiquement le solo. Les rôles récents et l'intensité vécue entrent dans l'exposition personnelle.

La dette d'attention augmente durant les périodes de présence admissible sans manifestation significative, plafonne puis diminue lors d'une exposition confirmée. Un signal ACK client prouve une réception technique, pas que le joueur a regardé ou compris. Une micro-ambiance ne remet pas à zéro la dette de contenu significatif. Les cooldowns des joueurs s'appliquent aux audiences incidentes lorsqu'un danger physique commun les affecte.

## 7. Scoring et sélection pondérée

Après hard filtering, chaque feature normalisée est dans `[0,1]`, sauf les pénalités séparées. Une donnée inconnue utilise le neutre déclaré par feature et ne vaut jamais une validation de sécurité. Les composantes sont : `contextCompatibility`, `memoryRelevance`, `regionPersonality`, `playerSuitability`, `globalTension`, `localIntensityFit`, `novelty`, `cooldownReadiness`, `storyPreference`, `chainRelevance`, `performanceFit`.

Formule normative :

```text
z(c) = log(baseWeight(c)) + Σ weight[f] × (2 × feature[f,c] − 1)
       − repetitionPenalty(c) − habitationPenalty(c)
score(c) = exp(clamp(z(c), score.log_min, score.log_max))
p(c | pool) = score(c)^(1 / temperature) / Σ score(j)^(1 / temperature)
```

Les poids, bornes, température, taille du top K, masse d'exploration et demi-vies figurent dans le profil de calibration. `baseWeight` doit être strictement positif. Aucun NaN/infini n'entre dans un tirage. Le premier pool est le top K admissible avec seuil relatif au meilleur ; à probabilité `selection.exploration_probability`, le pool devient le reste admissible au-dessus du plancher de score. Si ce reste est vide, utiliser le premier pool. Le tirage reste pondéré dans les deux cas. Une contribution IA modifie seulement des log-poids dans une amplitude bornée avant le tirage, jamais l'appartenance admissible.

`repetitionPenalty` utilise des expositions décroissantes par événement, famille et rôle, sans interdiction jusqu'à épuisement du catalogue. Les cooldowns durs sont filtrés ; `cooldownReadiness` récompense ensuite la distance au dernier cooldown, sans pouvoir le raccourcir. `habitationPenalty` monte dans les zones très fréquentées pour certaines familles puis favorise d'autres familles compatibles ; il ne coupe pas toute vie au camp. Le détail de chaque composante est audité.

Pour une obligation REQUIRE prête, le scheduler réserve une opportunité dans la file de service équitable des obligations, sans supprimer la file ambiante. Les obligations sont servies par priorité narrative explicite puis temps d'attente admissible, avec vieillissement plafonné. Le besoin de quête n'autorise ni unicité déjà consommée ni multiplication de danger. Une requête impossible retourne un motif stable ; elle reste visible au Quest Core, qui peut proposer un contrat alternatif explicite.

Le partage des opportunités utilise un crédit : à chaque fenêtre avec une obligation admissible, ajouter `scheduler.required_service_share`, plafonner à une opportunité. Si crédit atteint l'unité, prendre la plus ancienne obligation prête selon le tuple de priorité, puis décompter l'unité ; sinon servir l'ambiant. Si aucun candidat ambiant n'existe, une obligation sûre peut utiliser l'opportunité disponible sans consommer un droit à contourner safety. Si aucune obligation n'est prête, conserver au plus un crédit, sans accumulation infinie. Ainsi REQUIRE progresse lorsque son contexte redevient admissible et ne peut monopoliser indéfiniment le monde vivant.

## 8. Rythme, tension et présence minimale

`PacingState = REST, SUBTLE, ACTIVE, PEAK, RECOVERY` gouverne les enveloppes de débit et d'intensité ; `GlobalTension = CALM, UNUSUAL, TENSE, EXCEPTIONAL` biaise le contenu. Les transitions ont hystérésis, bornes temporelles et tirage seedé ; aucun horaire rigide n'est injecté. Les facteurs narratifs ne peuvent modifier que les biais bornés déclarés. Ils ne peuvent lancer un événement ni maintenir artificiellement PEAK.

La présence minimale suit le temps d'exposition utile de chaque audience active. Si aucun événement régulier n'est admissible, un `SAFE_MICRO_EVENT` séparé peut être candidat. Ce dernier reste sujet aux validations, aux cooldowns micro et aux canaux disponibles. En mode PAUSED, sans client capable, ou en panne critique de persistance, une présence minimale ne constitue pas une obligation de jouer un effet. L'état est audité `MIN_PRESENCE_DEFERRED`, pas maquillé en succès.

L'humour, le solo et le social sont de vraies familles indexées ; ils ont des poids et compteurs d'exposition propres. La tension narrative réduit le poids d'humour selon calibration, sans rendre cette famille structurellement absente. Aucun coefficient général `night_event_rate > day_event_rate` n'existe.

Le contrôleur de rythme est semi-markovien : à l'entrée d'un état, tirer uniformément une échéance entre ses bornes de profil et la stocker. REST expire vers SUBTLE ; SUBTLE vers ACTIVE ; ACTIVE vers PEAK avec `pacing.active_to_peak_probability` si santé NORMAL et budgets disponibles, sinon RECOVERY ; PEAK vers RECOVERY ; RECOVERY vers REST. Un décès récent, un épisode de forte activité extérieure ou une santé dégradée peuvent imposer RECOVERY, sans tirer une nouvelle échéance chaque tick. Les transitions imposées gardent leur cause en audit. REST et RECOVERY diminuent l'enveloppe d'intensité, sans supprimer la présence micro sûre.

L'enveloppe d'intensité active est `budget.global_intensity × pacing.<état>_intensity_factor`, idem pour les régions. Une transition vers un facteur plus faible n'annule pas arbitrairement une catastrophe commencée : elle ferme de nouvelles admissions et laisse les réservations existantes se réduire aux points sûrs. PEAK utilise une échéance tirée entre l'entrée et son maximum de profil ; le contrôleur ne réamorce pas une succession de pics à la demande d'une quête.

La tension scalaire est une EMA de l'intensité mondiale normalisée, du risque régional maximal connu et des faits significatifs récents, pondérés également avant le biais narratif plafonné. Les seuils `pacing.tension_*_enter`, l'hystérésis et la demi-vie sont dans le profil ; les données absentes sont neutres pour l'ambiance, jamais favorables à safety. Le passage vers un état inférieur exige le score sous le seuil d'entrée moins hystérésis pendant la fenêtre de récupération. Ce calcul influe les affinités d'événements et ne remplace aucune admissibilité locale.

## 9. Régions, mémoire et habitudes

Le `RegionService` maintient un index spatial de supports d'anchors et de zones observées ; la cellule d'index est un accélérateur, pas l'identité d'une région. Les nouveaux camps, routes et lieux sociaux créent des régions à partir d'évidence agrégée. Les IDs sont stables ; fusion et scission produisent des alias/relations de filiation. Les ledgers d'unicité héritent de cette filiation, sans pouvoir rejouer un événement unique en renommant une zone.

Les compteurs et les traits ont des decay distincts ; les faits durables, sinistres durables et tombstones d'ownership n'en ont pas. Les détails significatifs sont archivables, pas silencieusement supprimés. `CAMP_ROUTINE` agrège fréquentation, occupation approximative, secteurs relatifs et plages d'activité ; aucune trajectoire ni conversation n'est stockée. Les algorithmes et structures sont définis dans le modèle de données.

## 10. Concurrence et conflits

Chaque plan produit des claims typés : régions/intensité, canaux perceptifs par joueur, cellules de mutation, ressources météo dimensionnelles, entités et slots d'unicité. Les plans prêts d'une même fenêtre d'arbitrage sont comparés par priorité de sécurité, obligation explicite, pertinence de chaîne, équité/âge puis tie-break seedé. Ce n'est pas du premier arrivé.

L'acteur réserve tous les claims d'un plan ou aucun. Un conflit avec un effet commencé utilise une politique déclarée : SHARE pour canaux compatibles, MERGE avec fonction associative testée et ownership composite, DEFER, ou PREEMPT_BEFORE_EFFECT. Une mutation déjà commise ne peut être préemptée comme un verrou vide : il faut sa saga de compensation/terminaison. Un REQUIRE n'est pas une priorité supérieure au cleanup de sécurité.

Les réservations ont une génération et une expiration ; leur coût est recalculé aux changements de phase. Libération uniquement après cleanup ou transfert explicite en état durable. Les mutations d'un même bloc utilisent des epochs CAS ; aucune chaîne ne peut détenir éternellement une zone vide entre deux étapes.

L'arbitre compare un tuple lexicographique : urgence de stabilisation/cleanup ; obligation REQUIRE prête ; continuation de chaîne pertinente ; priorité de contenu déclarée ; âge admissible plafonné ; score ; tie-break seedé. Un événement DANGER candidat n'obtient pas l'urgence réservée à une catastrophe déjà en cours. `YIELD_BEFORE_COMMIT` signifie qu'un perdant peut céder ses réservations tant qu'aucun effet n'est commencé ; après le premier effet il ne cède qu'à un point sûr via sa saga. SHARE exige des canaux distincts ou un mélangeur explicitement compatible ; MERGE exige une fonction associative/idempotente enregistrée et une compensation par contributeur.

## 11. Physique, faune, météo et catastrophes

Le moteur peut transformer plusieurs chunks par tranches et créer de vrais groupes d'animaux. Les limites de travail par tick réduisent le débit, pas l'ampleur fonctionnelle autorisée. Une migration réelle utilise des entités réelles dans les corridors chargés, des étapes de navigation bornées et un comptage incluant les entités sauvegardées hors charge. Elle ne téléporte pas arbitrairement un troupeau à travers des chunks inconnus. Une continuation en dehors du corridor attend ou se clôt selon son contrat ; elle ne déclenche pas une génération forcée.

Les entités Director portent un token unique, un mode de propriété et une politique de sortie. Domestication, nommage ou adoption observée peuvent céder la propriété selon la définition ; une entité cédée n'est jamais supprimée par le cleanup de l'ancien événement. Le plafond vivant du serveur et le coût de pathfinding peuvent réduire une cohorte puis la faire avancer par vagues, sans remplacer une migration par une simple particule.

La météo réelle de base est dimensionnelle ; un claim exclusif la contrôle. La météo locale d'ambiance est un effet client spatial, distinct de pluie physique et de fertilité réelle. Une catastrophe locale physique utilise des primitives enregistrées : eau contrôlée sans propagation hors enveloppe, combustion dirigée sans embrasement autonome extérieur, débris bornés, déplacement de terrain journalisé. Les dégâts réels sont appliqués uniquement par une primitive de danger approuvée et bornée, précédée de WARN. Le moteur ne promet pas de rollback d'une mort ou du temps de jeu ; ses effets non inversibles sont explicitement déclarés et testés.

Une catastrophe est une recette à états `WATCH → WARN → IMPACT → STABILIZE → RESOLVE`, dont les facteurs de risque sont calculés depuis des snapshots connus. La recette doit fournir une enveloppe, des zones de sortie, un budget, un masque de propagation et un plan d'arrêt. Une construction joueur n'est pas globalement immunisée par une simplification de la spec ; les événements pouvant l'affecter doivent porter un `ConstructionImpactContract` explicite avec signes, ampleur, exclusions et mécanisme de compensation. Les valeurs de dégâts/ampleur ne sont pas validées par cette mission technique.

## 12. États persistants, chaînes et unicité

Les chaînes courtes sont des graphes explicites ; les chaînes longues utilisent un horizon de planification borné renouvelable, sans longueur totale arbitraire. Chaque transition consomme des faits identifiés et note sa causalité. Une continuation peut bifurquer, s'interrompre ou céder en état régional durable. L'absence de quête n'inhibe aucun de ces chemins.

L'unicité est une réservation puis un ledger durable par joueur, région canonique ou monde. Elle est consommée au point d'exposition/conséquence déclaré, pas à la simple sélection. Une exécution partielle irréversible peut consommer le scope pour éviter une double expérience ; son résultat n'est pas mensongèrement SUCCEEDED. Les événements REPEATABLE conservent cooldowns et historique. L'ABA, les redémarrages et les fusions de régions sont traités dans les contrats de stockage.

## 13. Administration, erreurs et déploiement futur

Le dashboard v1 est un écran Minecraft réservé aux opérateurs, adossé à une façade serveur et aux mêmes commandes que la CLI en jeu. Aucun HTTP public n'est requis. Il expose état, tension, intensité, événements/chaînes/catastrophes, décisions, mémoire régionale, joueur et outils de simulation. Le forçage passe par les mêmes hard validators. Les erreurs sont des résultats typés ; une recette en échec est isolée, sans `catch` silencieux ni arrêt du serveur.

L'implémentation future suit G0 à G5 ; le déploiement attend tests sur copie, compatibilité de sauvegarde, profiling et calibration. Cette mission ne touche aucun JAR actif. Les dépendances binaires actuelles et les capacités des mods externes sont des préconditions d'intégration à mesurer ; la spec définit déjà le comportement en cas de capacité absente.

## 14. Revue et conflits

Aucun conflit fonctionnel insoluble n'a été identifié au stade de la conception. La non-atomicité des sauvegardes Minecraft est une limite technique réelle, résolue par journaux, preuves locales et mise à l'écart des écritures ambiguës ; une transaction physique globale instantanément atomique n'est pas revendiquée.

Si l'implémentation découvre une impossibilité :

```text
TECHNICAL_CONFLICT
Decision affected:
Reason:
Alternatives:
Gameplay impact:
Recommended resolution:
```

Le composant affecté est isolé ; les autres lots continuent. Les tests de contrat, la matrice de traçabilité et les décisions techniques sont détaillés dans les neuf documents complémentaires. Le « 100 % » de cette livraison signifie couverture de conception de la mission, jamais fonctionnement du mod déjà prouvé.
