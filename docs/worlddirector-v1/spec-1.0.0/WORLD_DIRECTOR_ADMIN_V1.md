# WORLD_DIRECTOR_ADMIN_V1

Contrat 1.0.0. Le dashboard obligatoire de v1 est un écran **dans Minecraft**, ouvert par `/proutlost director ui`. Le backend est une façade serveur de requêtes/commandes typées. Ce choix évite de rendre le Director dépendant d'un service web ou de StreamDashboard.

## 1. Écran minimum et droits

| Onglet | Lecture | Actions |
| --- | --- | --- |
| Vue monde | run control, santé, rythme, tension, profil d'intensité, budgets et erreurs | pause, resume, intensité globale, tension temporaire |
| Actifs | événements avec phases/cibles, chaînes, catastrophes et WARN/IMPACT, ressources durables | inspecter, stop sûr, stabilize catastrophe |
| Mémoire | régions/anchors/personnalités/patterns, joueur opérationnel et significatif, cessions/conflits | recherche/pagination ; inspecter sans effacer |
| Décisions | liste, candidat retenu, rejets, scores/poids, IA/fallback, safety, mutations/résultat | replay du calcul et export audit |
| Laboratoire | sélectionner event/région/cible/groupe, contexte de simulation | score-only, force via admission normale, afficher raisons de refus |

Toutes les valeurs sont servies avec révision et âge. L'écran ne calcule pas une sécurité locale. Les mutations admin sont typées et passent dans la mailbox de l'acteur. Une action en attente affiche PENDING avec command_id ; l'ACK réseau n'est pas un succès d'effet. Une commande est idempotente par `(actor_session,request_id)`.

Droits techniques retenus : opérateur Minecraft niveau 4 pour administration complète, niveau 2 pour secours et inspection synthétique, joueur pour report et nettoyage perceptif personnel. Ces niveaux sont des décisions d'accès techniques ; une extension de permissions peut mapper les capacités sans rendre un modérateur MJ. Les détails de storyline et d'un joueur ne sont pas broadcast aux non-administrateurs. Le simple fait d'avoir le mod client n'accorde aucun accès.

## 2. Commandes canoniques

| Commande | Effet et résultat |
| --- | --- |
| `/proutlost status` | synthèse sans spoiler, santé Director et anomalie locale, disponible à tous |
| `/proutlost director status` | synthèse admin détaillée, mode et files |
| `/proutlost director ui` | ouvrir l'écran après validation de permission |
| `/proutlost director pause` | fermer nouvelles admissions ; entretenir fins/cleanup |
| `/proutlost director resume` | rouvrir si store sain ; afficher raison si refus |
| `/proutlost director drain` | fermer admissions et terminer/nettoyer les instances |
| `/proutlost director tension <enum> <durationTicks>` | override borné, avec expiry et audit ; pas de lancement d'événement |
| `/proutlost director intensity <profileId>` | appliquer profil validé, jamais charger code libre |
| `/proutlost director event list` | liste paginée des instances |
| `/proutlost director event inspect <instanceId>` | états/claims/mutations/effets/résultat |
| `/proutlost stop <instanceId|all>` | stop sûr puis compensation selon propriété ; aucune suppression aveugle |
| `/proutlost rescue <player>` | reset des perceptions, sortie des seuls états/contraintes Director ; pas de téléportation arbitraire |
| `/proutlost rescue self` | joueur : nettoyage perceptif personnel sans stopper monde ni obtenir un bonus |
| `/proutlost report <message>` | rapport borné lié aux instances/corrélations pertinentes, rate limited |
| `/proutlost trigger <eventId> region <regionId> [targets <players>]` | test via admission ; le refus expose les raisons, aucune option bypass |
| `/proutlost director simulate <fixtureId>` | évaluer un contexte synthétique enregistré, zéro effet |
| `/proutlost director replay <decisionId> [without_ai]` | recalculer depuis audit, sans rejouer le monde |
| `/proutlost director region <regionId>` | mémoire, traits, lineage, anchors et derniers événements |
| `/proutlost director player <uuid>` | opérationnel + faits significatifs Director autorisés |
| `/proutlost director chain <chainId>` | étapes, branches, faits consommés et prochaine condition |
| `/proutlost director disaster <disasterId> stabilize` | stabilisation sous même safety path, auditable |
| `/proutlost director audit export <decisionId>` | export JSON dans répertoire serveur dédié, chemin dérivé du serveur |
| `/proutlost debug` | alias d'inspection pour secours ; détails avancés réservés au niveau requis |

La liste de joueurs est un ensemble UUID résolu côté serveur. Le « groupe » fourni au labo est temporaire pour ce test ; il ne réécrit pas SocialMemory. Les commandes de contexte injecté modifient un DTO de simulation, jamais la santé réelle utilisée par l'admission. Le dashboard accepte seulement des profils de configuration compilés ; il n'est pas un éditeur arbitraire de fichiers/commandes système.

## 3. Protocole de perception

Tous les payloads de perception ont un header `{protocol_version, world_id, boot_epoch, connection_epoch, dimension, instance_id, effect_id, sequence, remaining_ttl_ticks}`. Les IDs UUID sont encodés comme UUID binaires ; les ticks/seq comme VarLong bornés. Le serveur envoie une durée restante, pas une supposition de synchronisation d'horloge client.

| Direction / payload | Corps et règles |
| --- | --- |
| S2C `SessionReset` | monde/boot/connection epochs, capacités autorisées ; vider anciens effets, sons et proxies |
| C2S `Capabilities` | protocol version, supported effect kinds/assets hashes ; uniquement des indications de compatibilité |
| S2C `PerceptionUpsert` | kind enregistré, transform bornée, asset id connu, paramètres typés, TTL ; idempotent par effect_id/sequence |
| S2C `PerceptionStop` | effect_id ou instance_id, stop sequence ; un tombstone empêche un upsert ancien de ressusciter |
| S2C `PerceptionReset` | scope joueur/dimension/instance ; arrêt et libération GPU/audio |
| C2S `PerceptionAck` | effect_id, session epoch, sequence, réception/erreur ; ne prouve pas une émotion ni un fait physique |
| C2S `RecordObservation` | source effect_id/fact_id reçu, texte joueur borné facultatif ; crée carnet sur choix explicite |
| C2S `AnchorInteraction` | instance_id, anchor_id, action_id autorisé ; serveur vérifie portée, état, rôle et contexte réel |

Un client ne reçoit que ses perceptions, jamais le plan des rôles invisibles des autres. Les effets physiques communs sont transmis par Minecraft normal. Les ghosts sont du rendu sans entité physique ni faux inventaire. TTL local monotone, cleanup au changement de dimension/déconnexion/respawn, et reset à la reconnexion garantissent la fin même si Stop est perdu. Un heartbeat renouvelle une perception longue dans son budget ; aucune durée infinie.

Une apparition suit un point/acteur seulement sur la durée déclarée, dans distance et empreinte admissibles ; pas de poursuite prolongée par renouvellement silencieux de TTL. Les nouvelles cibles ou le changement de dimension invalident le suivi et demandent une nouvelle admission, jamais un déplacement interdimensionnel client libre.

## 4. Protocole admin et backend

| Payload | Champs nécessaires |
| --- | --- |
| C2S `AdminQuery` | request_id, panel, filter typé, cursor opaque?, expected_snapshot_revision? |
| S2C `AdminSnapshot` | request_id, snapshot_revision, captured_tick, last_durable_seq, rows, next_cursor?, capabilities |
| C2S `AdminCommand` | request_id, operation enum, expected_revision, arguments fermés |
| S2C `AdminCommandResult` | command_id, status PENDING/SUCCEEDED/DEFERRED/REJECTED/FAILED_SAFE, reason_codes, new_revision?, result_ref? |
| S2C `AdminDelta` | subscription_id, from_revision, to_revision, changed rows ; trou → requery paginée |

La permission est revérifiée à chaque query, à l'admission et avant application différée. L'acteur provient de la session réseau, jamais du UUID fourni par le client. Curseurs liés au monde/snapshot et signés ou stockés serveur ; aucune lecture de fichier choisie par le client. Les tailles/page, fréquence de rafraîchissement, débit de commandes et longueurs de report figurent dans `DEFAULT_FOR_TESTING.json`.

Les réponses réseau sont des DTO bornés : l'audit complet se récupère par pages ou export serveur. Aucun dump du store, des secrets IA ou des conversations n'entre dans les payloads. Les limites d'application sont inférieures aux maxima transport NeoForge. Les handlers refusent version inconnue, champ inconnu, integer overflow, valeur non finie, dimension incohérente et session epoch ancien.

## 5. Forçage, simulation et secours

`force(eventId)` court-circuite uniquement la génération aléatoire du candidat ; il ne contourne pas les cooldowns de sécurité, les budgets, l'unicité, les pré-états, le WARN ou les protections. Le système peut présenter une variante plus légère seulement si elle existe dans le catalogue et l'admin la choisit explicitement. Un contexte simulé ne peut pas attester une capacité réelle manquante.

Le secours n'enlève pas des objets de joueurs et ne « restaure » pas une construction cédée. Un stop d'événement ne rétracte pas les faits réellement survenus. Tous les changements admin significatifs sont des DirectorEvents audités avec acteur, motif/commande, état précédent et résultat.

La v1 est acceptée lorsque les cinq onglets fonctionnent en serveur dédié, que pause/resume et l'intensité sont opérables sans logs manuels, qu'une catastrophe et une chaîne sont inspectables, et que refus/erreurs sont intelligibles. L'UI finale peut être simple ; aucun onglet obligatoire ne doit être reporté à une v2.
