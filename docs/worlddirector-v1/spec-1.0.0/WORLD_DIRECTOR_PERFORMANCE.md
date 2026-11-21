# WORLD_DIRECTOR_PERFORMANCE

Contrat 1.0.0. Toutes les valeurs initiales sont `DEFAULT_FOR_TESTING` et centralisées ; elles sont des hypothèses à qualifier sur une copie du pack, pas des mesures du serveur actuel.

## 1. Cadences et complexité

| Source | Stratégie | Profil de cadence/coût |
| --- | --- | --- |
| Pose/casse, décès, interaction, spawn/remove | hook local, compteur/ID O(1), agrégation par fenêtre | `observation.*` |
| Joueurs proches/isolés | position temporaire, hash spatial des joueurs de même dimension, voisins seulement | `sensor.player_ticks`, `spatial.player_cell_blocks` |
| Camps et habitudes | anchors connus et zones occupées, un sous-ensemble par tour | `sensor.camp_ticks`, `pattern.*` |
| Région et personnalité | decay paresseux, dirty scopes, work queue round-robin | `sensor.region_ticks`, `memory.*` |
| Risque géologique/météo | snapshots de surface mis en cache par cellules actives ; invalider zones modifiées | `sensor.risk_ticks`, `cache.*` |
| Entités | hooks et index des seules entités Director, compteurs serveur disponibles ; pas de getAllEntities permanent | `entity.*`, `sensor.entity_ticks` |
| Décision | catalogue indexé famille/tags/capabilités, candidats limités par fenêtre puis rotation équitable | `scheduler.*`, `selection.*` |
| Cleanup/expiration | min-heap d'échéances et mailbox prioritaire | chaque tick, sous budget dédié |
| Ownership | vérifier positions possédées sales et échantillon incrémental | `sensor.ownership_ticks`, `mutation.*` |

Pour P joueurs, A anchors actifs, C candidats réellement évalués, E entités Director chargées et M cellules possédées sales : coût d'un tour proportionnel à P + voisinages + fraction(A) + C + fraction(E+M), pas à la surface totale de la map. Les plus gros regroupements peuvent créer un voisinage dense ; plafonner le travail par tranche, réutiliser un groupe déjà calculé et marquer le résultat incomplet. Une estimation incomplète ne rend pas sûr un danger collectif.

Les routes persistées sont des compteurs de transitions entre cellules, pas un journal de déplacement. Les chunks non chargés ne sont pas ouverts simplement pour alimenter un score. Les capteurs de danger peuvent attendre une donnée de terrain absente ; ils ne la remplacent pas par une hypothèse favorable.

## 2. Budgets imbriqués

Un `BudgetVector` contient coût tick estimé, intensité, entités vivantes/persistantes, mutations en vol, IO en attente, perception client, payload bytes et charge de navigation. Réservations par monde, dimension, région et cible selon l'effet. Admission si toutes les dimensions applicables restent dans leur enveloppe ; pas de nombre arbitraire d'instances actives unique au serveur.

| Budget | Profil de test | Débit d'application |
| --- | --- | --- |
| CPU Director total sur server thread | `budget.tick_ms`, `budget.tick_p95_ms` | deadline monotone et compteurs d'opérations |
| Part critique cleanup/arrêt | `budget.critical_tick_ms` | priorité, mais tranches toujours bornées |
| Décision et snapshots | `budget.decision_ms`, `budget.snapshot_ops` | si épuisé, continuer au prochain tick |
| Mutations physiques | `mutation.blocks_per_slice`, `mutation.blocks_per_tick`, `mutation.prepared_inflight` | tranches par chunk/footprint |
| Faune | `entity.director_world_cap`, `entity.regional_cap`, `entity.server_live_cap`, `entity.spawns_per_tick` | vagues avec slots persistants |
| Navigation | `entity.navigation_updates_per_tick`, `entity.max_path_nodes` | navigation distribuée, arrêt si path trop coûteux |
| Réseau/rendu | `network.*`, `client.*` | paquet/effet/audience sous enveloppe |
| IO | `io.queue_bytes`, `io.batch_bytes`, `io.max_ack_ms` | priorité WAL critique, backpressure avant effet |

Une primitive expose estimation conservative et coût observé EWMA. L'estimation est corrigée après chaque tranche. Les limites JSON ne peuvent réduire une estimation sous le coût minimal du registre. Les callbacks voisins Minecraft peuvent coûter davantage qu'un simple nombre de blocs : limiter aussi leur complexité, mesurer le temps réel et exclure les recettes non bornables jusqu'à qualification. Une deadline ne préempte pas au milieu d'un appel vanilla ; c'est pourquoi chaque primitive doit avoir des unités suffisamment petites.

Les coûts durables ne disparaissent pas lorsque l'événement se termine : animaux installés, objets physiques et zones sous surveillance restent comptés dans les indices appropriés. Un slot d'entité UNLOADED n'est pas libéré pour permettre un nouveau spawn.

## 3. TPS/MSPT guard

Mesurer les temps réels de tick du serveur, EWMA et percentiles glissants ; ne pas déduire la santé uniquement d'un compteur TPS saturé. NORMAL → DEGRADED si seuil de MSPT ou retard mailbox/IO persistants ; DEGRADED → NORMAL seulement après une période de récupération sous seuil inférieur. CRITICAL si seuil grave, disque critique ou perte d'ACK durable ; passer READ_ONLY_SAFE pour les opérations nécessitant persistance.

| Mode | Sélection | Activités déjà engagées |
| --- | --- | --- |
| NORMAL | toutes recettes admissibles dans budgets | budgets habituels |
| DEGRADED | suspendre coûteuses, réduire capteurs lourds, IA facultativement coupée ; micro possible si sûr | stabilisation prioritaire, cohortes suspendues ou nettoyées selon contrat |
| CRITICAL / READ_ONLY_SAFE | fermer nouvelles admissions critiques | arrêter propagation, cleanup borné, conserver preuves et alertes |
| PAUSED | aucune nouvelle scène automatique | finir/nettoyer selon commande ; maintenance critique continue |

Une catastrophe en IMPACT ne peut simplement « arrêter de ticker » et laisser de l'eau/du feu sans surveillance. Son `stabilize` est dans la file critique. Les primitives physiques utilisent des propagation masks persistants et n'entretiennent pas de simulation autonome hors contrôle. Si le moteur ne peut garantir ce comportement avec un mod météo/fluide tiers, cette intégration n'obtient pas sa capability.

## 4. Backpressure et files

Files séparées : critical recovery, durable ACK, lifecycle/cleanup, observations agrégées, évaluations, admin, IA. Chaque file a une capacité et un âge maximal. En saturation : coalescer observations anodines par clé ; annuler vieux calculs sur snapshots périmés ; refuser nouvelles admissions ; ralentir consommateur admin. Ne jamais jeter une cession joueur, une intention physique déjà autorisée, un fait durable ou un ACK de commit.

Le writer IO ne bloque pas le server thread. Une réservation expirée avant ACK est invalidée par génération ; un ACK tardif ne redonne pas implicitement un permis d'effet. Les jobs CPU devenus inutiles sont annulés coopérativement. Le transport IA ne doit pas monopoliser un worker CPU. Les consumers du bus ont chacun une fenêtre/queue ; un addon lent ne provoque pas une avalanche synchrone de listeners.

## 5. Caches et capacité

Clés de cache : monde, scope, révision, catalogue, configuration, sensor generation. Invalidation sur mutation, déplacement de scope, reload et changement de dimension. Les caches sont LRU bornés ; l'éviction n'efface pas les ledgers durables. Les données froides sont paginées ; le nombre d'anciennes régions n'impose pas leur chargement au tick.

Le catalogue peut dépasser le nombre de candidats évalués par fenêtre : rotation déterministe de partitions, priorité aux exigences prêtes et aux scopes sous-exposés. L'audit note les partitions parcourues et reportées ; aucun événement n'est définitivement oublié parce que son ID est en fin de liste. Les claims et versions détectent les décisions évaluées trop tard.

## 6. Métriques et qualification future

Métriques : `director.tick_ms`, `snapshot_ms`, `candidate_count`, `eligibility_reject{reason}`, `queue_age_ticks`, `wal_ack_ms`, `wal_queue_bytes`, `active_intensity{region}`, `director_entities{state}`, `mutation_cells{state}`, `recovery_conflicts`, `outbox_lag`, `micro_deferred`, `ai_fallback{reason}`, `client_effect_count`, `client_cleanup_lag`, `unique_possible`, `require_wait_ticks`. Les UUID/coordonnées ne sont pas labels de séries, pour éviter cardinalité illimitée.

Benchmarks futurs : serveur de base puis même charge avec Director ; comparer MSPT médian/p95/p99, mémoire après GC, file IO, coût réseau et frame time client. Tester groupes et solo, régions dormantes, fortes modifications, grandes cohortes et plusieurs catastrophes compatibles. La qualification ne consiste pas à promettre une valeur fixe de TPS indépendamment du matériel. Aucun benchmark Minecraft n'a été lancé pour cette livraison ; les seuils du profil attendent mesure.
