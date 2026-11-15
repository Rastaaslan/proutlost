# WORLD_DIRECTOR_DATA_MODEL

Contrats 1.0.0. Les champs listés sont obligatoires, sauf `?`. Une collection absente n'est pas équivalente à une collection vide : absence autorisée seulement si indiquée. Les durées/calibrations proviennent du profil de test central.

## 1. Types fondamentaux et encodage

| Type Java logique | Représentation persistante/JSON | Règle |
| --- | --- | --- |
| `WorldId`, `RegionId`, `LocationId`, `AnchorId`, `EventInstanceId`, `ChainId`, `DisasterId`, `DecisionId`, `FactId`, `RequestId`, `MutationId`, `OwnerToken` | UUID minuscule | Un ID n'est jamais dérivé de coordonnées susceptibles de changer |
| `DefinitionId`, `DimensionId`, `PrimitiveId`, `TagId`, `FactType` | `namespace:path` | ASCII minuscule ; path sans segment vide ni traversal |
| `Tick`, `Sequence`, `Revision`, `Epoch` | entier signé Java long ; chaîne décimale non négative en JSON d'état | Aucune conversion via float/JavaScript Number |
| `DurationTicks` | entier non négatif de configuration | Résolu avant l'admission ; aucune chaîne évaluée comme script |
| `BlockPosition` | `{dimension,x,y,z}` entiers | Coordonnées monde validées contre border et limites de hauteur |
| `Aabb` | dimension et six bornes inclusives/exclusives explicitement nommées | Min inclus, max exclu ; volume vérifié sans overflow |
| `PositionSnapshot` | dimension + position quantifiée + tick | Éphémère pour sampling ; jamais archive de trajectoire |
| `Fingerprint` | SHA-256 hex | Canonicalisation du blockstate, données BE/composants autorisés et registry IDs |
| `UnitValue` | double fini `[0,1]` | Une valeur manquante porte un état UNKNOWN séparé |
| `ScopeRef` | union `{kind:WORLD|REGION|PLAYER|LOCATION|EVENT,id}` | WorldId toujours présent dans l'enveloppe |
| `ActorIdentity` | SERVER, PLAYER(uuid), SYSTEM(module) | Le client ne choisit pas son identité d'autorité |

JSON canonique : UTF-8, clés triées, absence de NaN/Infinity, UUID/IDs normalisés, tableaux conservés si leur ordre est sémantique. Les hashes utilisent les octets canoniques du codec `CanonicalJsonV1`, dont les tests golden sont partagés ; pas un `toString()` Java. Les noms des propriétés persistantes sont `snake_case`. Les signatures Java dans les docs utilisent `camelCase`.

Pour un pré/post-état physique, le hash inclut toutes les données couvertes par la primitive. Une primitive qui ne sait pas capturer/réconcilier une donnée de bloc tiers n'a pas le droit de prétendre restaurer ce bloc. Le registre peut fournir un codec spécialisé ; sinon le plan est inadmissible pour cette cible, et non silencieusement approximé.

## 2. Agrégats et propriétaires

Chaque agrégat mutable porte `{schema_version, id, revision, last_sequence, updated_tick}`. Sa révision progresse dans une transaction logique, et non à chaque lecture.

| Agrégat | Champs métier | Autorité et index |
| --- | --- | --- |
| `DirectorWorldState` | world_id, store_generation, sim_tick, run_control, health_mode, pacing_state, tension, intensity_profile_id, catalog_hash, config_hash, boot_epoch, last_checkpoint_seq | acteur ; clé world_id |
| `RegionRecord` | region_id, dimension, status, support_shape, anchor_ids, importance, parent_ids, aliases, membership_revision, personality, memory_refs | RegionService via acteur ; index spatial par dimension |
| `LocationRecord` | location_id, source_namespace, external_id, dimension, bounds, tags, anchor_ids, import_revision, active | registre de lieux ; `(source_namespace,external_id)` unique |
| `AnchorRecord` | anchor_id, location_id?, region_ids, dimension, position, radius_profile, tags, origin, evidence, active | WorldPrep importé, découverte ou camp ; index position/tag |
| `RegionMemory` | composantes du tableau suivant, last_visit_tick, last_event_tick, persistent_state_refs, durable_fact_refs | MemoryReducer ; region_id |
| `PlayerOperationalMemory` | player_uuid, eligible_online_ticks, last_region_id?, last_target_tick?, event_exposure_ring, role_exposure_ring, cooldown_refs, attention_debt, recent_activity, recent_death_until?, transition_epoch | UUID joueur ; bornée en RAM et en archive opérationnelle |
| `PlayerSignificantMemory` | memory_id, player_uuid, event_id, event_definition_id, role, perception_status, outcome_ref, region_id?, location_id?, consequence_refs, observable_reaction_codes, recorded_tick | archive append-only, index UUID/tick ; pas de diagnostic psychologique |
| `EventInstance` | instance_id, definition_id/revision/hash, catalog_generation, state, phase, role_assignments, region_ids, context_ref, step_states, reservation_refs, effect_refs, mutation_refs, chain_id?, requirement_ids, started_tick?, elapsed_active_ticks, max_duration_ticks, recovery_policy, outcome? | runtime ; state/region/target/expiry |
| `ChainInstance` | chain_id, recipe_id/revision/hash, mode, state, current_nodes, consumed_fact_ids, lineage_refs, planned_horizon, branch_history, regional_state_ref?, relevance, next_evaluation_tick | ChainRunner ; state/region/next tick |
| `DisasterInstance` | disaster_id, event_instance_id, recipe_id, stage, risk_factors, risk_score, envelope, warning_evidence, affected_audience, evacuation_routes, propagation_mask_ref, mutation_refs, stabilization_state, durable_consequence_refs | DisasterRunner ; dimension/envelope/stage |
| `EntityOwnershipRecord` | owner_token, entity_uuid, instance_id, cohort_id, slot_id, phase, disposition, last_known_chunk, last_seen_tick, persist_mode, cleanup_policy, adopted_by?, generation | OwnershipService ; token unique + UUID + région |
| `OwnershipRecord` | resource_key, owner_tokens, epoch, disposition, creation_mutation_id, expected_post_hash, player_override_epoch, exception_contract_id? | propriété Director ; index par cellule/chunk/token |
| `MutationRecord` | mutation_id, instance_id, slice_id, state, primitive_id/version, footprint, pre_state_ref/hash, post_state_ref/hash, owner_token, owner_epoch, read_set_versions, noninvertible_effects, reconciliation_evidence, compensation_ref?, durable_seq | MutationCoordinator ; instance/chunk/state |
| `CooldownRecord` | key, kind, reason, not_before_tick, source_instance_id, safety_class, release_condition? | acteur ; `(definition/family,scope,reason)` |
| `UniqueUseRecord` | definition_id, scope_kind, canonical_scope_id, status, reservation_token?, instance_id, consumption_evidence?, origin_scope_ids | acteur ; clé composite unique |
| `DirectorRequestState` | request_id, producer_id, producer_key, kind, selector, scope, constraints, fulfillment, status, eligible_wait_ticks, expires_tick?, result_fact_ids, last_reason_codes | adaptateur quête via inbox ; producer/key unique |
| `AuditDecision` | voir section Audit | archive décision ; décision/tick/instances |

`EventInstance` conserve le contenu compilé ou son blob content-addressed tant qu'il est actif/récupérable. Un reload ne modifie jamais sa définition en cours. Les nouvelles admissions utilisent la nouvelle génération ; l'arrêt d'urgence d'une définition vise explicitement aussi les anciennes instances.

## 3. Mémoires et decay

Chaque composante décroissante contient `{value, observed_tick, half_life_profile, evidence_count, confidence}`. À lecture/réduction, `value(now)=value(at)×exp(-ln(2)×deltaTicks/halfLife)` ; appliquer ensuite l'évidence additionnelle, plafonner et fixer `observed_tick=now`. Le clamp et le seuil d'oubli sont des réglages de test. Pas de boucle exécutée chaque tick sur toutes les régions.

| Composante | Contenu agrégé | Politique |
| --- | --- | --- |
| VisitMemory | occupation, visites espacées, dernière présence, approx de diversité | décroissance propre ; frontière de session pour éviter de compter chaque sample comme visite |
| CampMemory | anchors feu, routine, abandon, usages récurrents | decay lent ; une absence courte ne détruit pas un camp |
| RouteMemory | transitions de cellules/régions et histogramme de direction | compteurs agrégés sans séquence de coordonnées |
| ActivityMemory | pose/casse, travail, exploitation, combat, repos | fenêtres glissantes/décroissance rapide |
| EventMemory | expositions par famille/événement, intensité récente | decay des répétitions ; références significatives archivées |
| WorldChangeMemory | traces temporaires, mutations durables, cessions | TTL pour traces temporaires ; durable pour conséquence/cession |
| SocialMemory | taille approximative et durée des rassemblements | buckets, pas de graphe d'amitié ou de confiance |
| DisasterMemory | risque récent, catastrophe, dégâts, récupération | risque décroissant ; conséquence majeure persistante |
| NarrativeFactMemory | faits acquis, conditions de branche, références de quête | pas de decay implicite ; rétractation explicite et versionnée |
| RegionPersonality | familiar/social/wild/quiet/strange/active/damaged/recovering | EMA lente sur features et impulsions bornées d'événements majeurs |

La mémoire opérationnelle joueur est bornée par anneaux/fenêtres configurables. La mémoire significative est détaillée par interaction importante mais limitée aux événements Director ; les archives froides permettent de garder l'information sans charger tous les joueurs. Une limite RAM ne doit pas effacer un fait durable nécessaire. Les positions y sont des lieux/régions, sauf un point précis indispensable à un événement significatif.

`observable_reaction_codes` contient seulement des faits comme `EXAMINED_TRACE`, `INTERACTED_ANCHOR`, `LEFT_FOOTPRINT`, `TOOK_EVENT_OBJECT`. Ni vocabulaire psychologique, ni chat/vocal, ni lecture de stream. La relation « joueur le moins cru » citée comme idée ancienne du Concept ne devient pas un capteur : cette caractéristique n'est pas observable selon le périmètre autorisé.

## 4. Régions dynamiques et CAMP_ROUTINE

L'index spatial est une grille uniforme sparse par dimension dont la résolution est une calibration. `support_shape` est une union de disques/boîtes d'anchors et de segments de routes quantifiés. Les tests étroits utilisent cette géométrie, jamais seulement les chunks. `RegionId` reste stable lorsque les supports bougent.

Une évidence persistante de camp/travail/social ou un import de lieu crée une région. La valeur d'importance est la combinaison normalisée des visites, persistance d'usage, diversité agrégée et conséquences. Une région insignifiante sans fait durable peut devenir dormant, puis son résumé peut être archivé. Une région contenant un ledger/fait significatif ne disparaît pas de l'identité historique.

Pour un point appartenant à plusieurs régions, choisir une région primaire par pertinence du type d'activité, importance et tie-break sur ID ; enregistrer le compteur une seule fois. Les régions secondaires peuvent recevoir un lien et une influence fractionnaire de personnalité, jamais la même unité de fréquentation intégrale. Les audiences d'événement sont un ensemble dédupliqué.

Fusion : proposer lorsque supports et similarité d'activité persistent au-dessus des seuils de test ; l'acteur valide sous révision. Le survivant est la plus ancienne identité stable ; les autres deviennent alias aplatis. Compteurs fusionnés après déduplication des fenêtres d'évidence ; les faits/événements sont des ensembles par ID. Un événement ONCE_PER_REGION déjà consommé dans une région d'origine l'est dans la fusion. Scission : enfants nouveaux, parent historique conservé, attribution spatiale des anchors ; les enfants héritent des consommations attachées au lieu/parent afin d'empêcher un reset artificiel. Une exception de contenu exige un changement explicite de scope dans la définition.

`CAMP_ROUTINE` : à partir des seules observations près d'un anchor feu/camp connu, accumuler temps occupé, visites indépendantes, taille de groupe par bucket, histogramme de secteurs/anneaux relatifs et plages du jour Minecraft. Les positions exactes sont jetées après réduction. La confiance est `1−exp(−evidence/evidence_scale)`, diminuée par la variance et l'âge ; elle ne dépasse le seuil de reconnaissance qu'après plusieurs épisodes indépendants. Un feu posé puis retiré ne crée pas instantanément une routine. Les chemins/raccourcis sont des flux de transitions agrégés entre supports, sans conserver l'ordre des parcours individuels. Le détecteur d'abandon utilise la chute de fréquentation avec hystérésis et temps depuis dernière visite.

Personnalité : `trait_next = trait + alpha(dt)×(evidence_target−trait) + bounded_major_impulse`, avec clamp `[0,1]`. Les vecteurs ne sont pas des étiquettes exclusives ; une région peut être sociale et étrange. Les tags historiques issus d'un import décrivent le lieu, pas une personnalité scénarisée immuable.

## 5. Features de décision et équité

| Feature | Définition normalisée |
| --- | --- |
| contextCompatibility | moyenne pondérée des affinités facultatives disponibles ; contraintes dures déjà filtrées |
| memoryRelevance | pertinence des faits/états compatibles avec la définition, plafonnée sans compter plusieurs fois le même fait |
| regionPersonality | produit scalaire normalisé des affinités de définition et traits de région |
| playerSuitability | moyenne des aptitudes contextuelles et dette d'attention, diminuée des expositions/rôles récents |
| globalTension | table d'affinité définition × état global, légèrement biaisée par le narratif |
| localIntensityFit | `1−clamp(abs(projected_intensity−desired_intensity)/scale)` |
| novelty | inverse normalisée du cumul décroissant des expositions événement/famille/rôle |
| cooldownReadiness | temps depuis la fin du cooldown dur, normalisé par horizon, puis clamp |
| storyPreference | somme bornée des PREFER correspondants avec TTL et decay déclarés |
| chainRelevance | adéquation à un nœud prêt et pertinence encore positive de la chaîne |
| performanceFit | minimum des headrooms normalisés CPU, entités, IO, réseau applicables au plan |

Les neutral values, poids et échelles sont dans le profil central. Une lecture de sécurité inconnue refuse/ajourne l'action correspondante, même si la feature de score utilise une valeur neutre.

`attentionDebt_next = clamp(debt + eligibleOnlineDelta×gain − confirmedExposureCost, 0, debtCap)`. L'importance de l'exposition est celle de la définition et du rôle, bornée par le profil. La dette ne crée pas de droit à un danger. La sélection de rôles minimise les répétitions récentes sous les contraintes de la définition ; égalité résolue avec RNG enregistré.

## 6. États et transitions persistantes

| Machine | États et règles |
| --- | --- |
| Event | PROPOSED → RESERVED → PREPARING → READY → RUNNING → RESOLVING → SUCCEEDED/PARTIAL/ABORTED_SAFE/FAILED_SAFE ; toute phase exécutée peut aller vers CLEANING ; recovery passe par RECOVERING |
| Step | WAITING → PREPARED → APPLIED → CONFIRMED ; ou SKIPPED_SAFE/CONFLICT/FAILED ; pas de retry sans même operation token |
| Chain | WAITING, READY, ACTIVE, SUSPENDED, BRANCHED, SETTLED_AS_WORLD_STATE, COMPLETED, ABORTED_SAFE |
| Disaster | WATCH, WARN, IMPACT, STABILIZE, RESOLVED, ABORTED_SAFE ; IMPACT exige preuve de WARN suffisante |
| Mutation | PLANNED → PREPARED_DURABLE → APPLIED_UNCONFIRMED → VERIFIED → COMMITTED ; compensation via COMPENSATING → ROLLED_BACK, YIELDED ou CONFLICT_ISOLATED |
| Ownership | DIRECTOR_ACTIVE, DIRECTOR_DURABLE, SHARED_EXPLICIT, YIELDED_TO_PLAYER, RELEASED, CONFLICT_ISOLATED |
| Entity | RESERVED → SPAWN_INTENT_DURABLE → PRESENT → MOVING/SETTLED → CLEANING → REMOVED ; ADOPTED cède le cleanup ; UNLOADED garde son slot |
| Requirement | ACCEPTED, WAITING_CONTEXT, WAITING_SAFETY, SCHEDULED, EXECUTING, FULFILLED, CONFLICT, CANCELLED ; PREFER ajoute EXPIRED |
| Unique | RESERVED, SPENT_CONFIRMED, SPENT_POSSIBLE, RELEASED_BEFORE_EFFECT ; une preuve d'absence certaine est requise pour libérer après PREPARE |

Un événement terminal peut conserver une tâche de compensation localisée indexée par chunk. Cette tâche a sa propre deadline et ne conserve ni thread bloqué ni budget actif global injustifié. Les ressources ambiguës sont isolées jusqu'à rapprochement sûr ; cette isolation visible n'est pas un événement zombie invisible.

## 7. Contrat Quest Core / bus

`DirectorEnvelope<T>` : `{schema_version:1, message_id, world_id, producer_id, producer_key, scope, correlation_id, causation_id, producer_sequence, world_sequence, created_tick, payload_type, payload_version, payload}`. Les séquences et les ticks sont des chaînes décimales dans les exports JSON. `causation_id` et `world_sequence` peuvent être null à l'entrée ; le store alloue un `world_sequence` monotone distinct à chaque message d'outbox dans la transaction qui le crée. Tout message émis/rejoué depuis l'outbox porte cette même séquence, indépendamment du nombre de retries et du nombre de messages dans une transaction WAL.

| Message | Producteur → consommateurs | Payload minimal et sémantique |
| --- | --- | --- |
| `DirectorFact` | runtime/mémoire → quête, chaînes, observabilité | fact_id, fact_type, subject_refs, event_id?, evidence_status, payload, durable, supersedes_fact_id? ; fait immuable |
| `DirectorEvent` | runtime → admin/consommateurs | instance_id, transition, outcome?, scopes ; notification de lifecycle, ne remplace pas une preuve de résultat |
| `DirectorPreference` | quête/addon autorisé → Director | request_id, selector IDs/families/tags, scope, bounded_weight_bias, expires_tick, producer_key |
| `DirectorRequirement` | quête/addon autorisé → Director | request_id, selector, target_constraints, scope, required_fact_predicate, fulfillment_mode, producer_key ; pas d'expiration silencieuse |
| `DirectorObservation` | adaptateurs autorisés → mémoire | observation_id, type connu, coarse_scope, tick, aggregate_delta, confidence ; pas une commande d'effet |
| `DirectorWorldState` | Director → lecture admin/quêtes | revision, tension, pacing, health, public_region_state_refs ; projection versionnée |

`selector` est `ANY_OF(event_ids)` ou `ALL_TAGS(family_tags)` ; un REQUIRE exige au moins une variante satisfaisant le prédicat de résultat. `fulfillment_mode` = `NEW_EXECUTION` ou `ALLOW_EXISTING_FACT`. Le mode doit être explicite ; il ne se déduit pas de la présence d'un fait. `required_fact_predicate` est un arbre typé comparable au contrat de conditions, sans code arbitraire. La progression n'est validée que sur un fait CONFIRMED correspondant, jamais sur SELECTED/STARTED ni sur une réponse IA.

Persistance : réception et changement de requête sont journalisés ; faits + mise à jour d'état + outbox sont dans la même transaction Director. Livraison **at-least-once** ; chaque consommateur tient une inbox dédupliquée par `(consumer_id,message_id)`. Un consumer applique son effet local et son cursor dans sa propre transaction. Sans transactional inbox côté Quest Core, le consommateur MUST fournir un handler idempotent ; le Director ne promet pas un exactly-once distribué.

Ordering : séquence totale d'émission par monde dans le store Director ; ordre causal préservé pour un agrégat et ordre croissant par consumer cursor. Aucun ordre global entre mondes/producteurs externes. Les reprises relivrent avant d'avancer le cursor. Les callbacks n'exécutent pas synchroniquement une quête depuis le commit monde. Backpressure : lots bornés, pause du consommateur lent, outbox conservée ; aucune suppression silencieuse d'un fait durable. Un consumer défaillant ne bloque pas les autres ; si l'espace durable ne suffit plus, suspendre les nouvelles transactions dépendant de cette garantie et signaler le motif.

Idempotence des requêtes : même `(producer_id,producer_key)` et même hash → même RequestId/ACK ; même clé et payload différent → `IDEMPOTENCY_CONFLICT`. Une révision de requête utilise un nouveau message et `expected_revision`. L'annulation avant effet libère ; après effet elle arrête au prochain point sûr et ne rétracte pas un fait réel. Un REQUIRE impossible reste CONFLICT avec raisons et alternatives de contrats, sans être déclaré rempli.

## 8. Import WorldPrep indépendant

Format fermé dans `schemas/location-import.schema.json`, exemple `examples/locations/import.json` : `schema_version`, `world_id`, `source_namespace`, `export_revision`, `export_digest`, listes regions/locations/anchors. Chaque entrée a un ID stable, dimension, géométrie/coordonnées et tags. Les IDs de régions externes sont conservés comme références importées ; le Director mappe vers ses RegionId canoniques. Les imports n'ont ni classe Java WorldPrep, ni accès à son état de traitement.

Validation hors thread puis installation atomique de la nouvelle révision : monde attendu, dimensions existantes, border/hauteurs, IDs/references uniques, taille bornée, digest des entrées canonisées, tags connus ou namespace accepté. Une entrée supprimée devient inactive ; son historique n'est pas supprimé. Un import identique est no-op ; une même révision avec digest différent échoue. Une mise à jour modifiant une zone utilisée par une instance active est différée pour cette instance, qui garde son snapshot. Aucun chunk n'est généré pour vérifier un import.

Sans import, le Director observe et découvre progressivement camps/lieux ; les événements exigeant un anchor absent sont seulement inadmissibles. Les observations d'habitat et la faune initiale préparées hors ligne sont des capacités facultatives, jamais une obligation de faire tourner WorldPrep en production.

## 9. DTO des ports et résultats

| Famille | Champs communs/variantes nécessaires |
| --- | --- |
| SnapshotBatch / SampleRequest | request_id, scopes, sensor_set, max_work, captured_tick, scope_revisions, features, missing_data, server_health |
| ObservationBatch / Reduction | input_ids, aggregate_deltas, before_revisions ; output changes, proposed_facts, pattern_updates |
| DecisionInput / DecisionProposal | immutable context/memory/catalog/config, request/chain views, seed ; candidates, eligibility, scores, selected_plan?, audit |
| AdmissionRequest / AdmissionResult | proposal_id, expected_revisions, claims, target_bindings ; status, token?, reasons, retry_condition |
| EffectIntent / EffectPreparation | operation_token, primitive/version, typed_args, expected_footprint ; snapshots, claims, pre/post, budget_estimate, inverse_plan, capabilities |
| PreparedSlice / CommitPermit | operation_token, chunk/cell set, preconditions, bounded operations ; permit_id, reservation_generation, prepare_seq, expiry_tick |
| SliceResult / CleanupResult | outcome, applied_operation_ids, observed_hashes, remaining_work, released_claims, conflict_cells, fact_evidence |
| RecoveryProbe / ReconcileResult | durable intent + loaded resource proofs ; SAME_POST, SAME_PRE_PROVEN, PLAYER_OVERRIDE, AMBIGUOUS, UNAVAILABLE |
| DurableBatch / CommitAck | transaction_id, expected_store_generation, expected_aggregate_revisions, typed records ; durable_sequence, transaction_hash, ack_status |
| StoreQuery / StoreView | aggregate keys/sequence/page_cursor ; immutable projection, last_durable_sequence, has_more |
| Subscription / ConsumerAck | consumer_id, filters, next_seq, bounded batch ; message_ids, contiguous_ack_seq, expected_cursor |
| RequestAck / RequestView / CancelRequest | RequestId, revision, state, reasons, fact_refs ; annulation avec request_id, expected_revision, producer_key |
| AdminQuery/Command/Result | actor_session, request_id, expected_revision, operation, typed payload ; op_status, data?, error?, next_cursor? |
| AiInput / AiProposalResult | versioned admissible candidate DTO et deadline ; VALID_PROPOSAL ou FALLBACK(reason) |

`Result` ne lance pas d'exception pour un rejet métier. Les erreurs système portent `{code, severity, retriable, retry_condition?, scope_refs, correlation_id, safe_state}`. Codes canoniques : `STALE_CONTEXT`, `NO_TARGET`, `CAPABILITY_MISSING`, `CHUNK_UNAVAILABLE`, `SAFETY_COOLDOWN`, `UNIQUE_CONSUMED`, `CLAIM_CONFLICT`, `BUDGET_EXHAUSTED`, `PLAYER_OVERRIDE`, `PRESTATE_MISMATCH`, `WAL_UNAVAILABLE`, `STORE_CORRUPT`, `CATALOG_INVALID`, `RECOVERY_AMBIGUOUS`, `DELIVERY_UNCERTAIN`, `REQUIREMENT_UNSATISFIABLE`, `IDEMPOTENCY_CONFLICT`, `AUTH_DENIED`, `RATE_LIMITED`, `AI_TIMEOUT`, `AI_INVALID`, `INTERNAL_FAULT`. Aucun `INTERNAL_FAULT` ne passe en SUCCEEDED par défaut.

## 10. Audit machine-readable

Un `AuditDecision` contient contexte minimal suffisant au calcul, config/catalog versions et blobs hashés, liste des candidats examinés et motifs d'éligibilité, features, contributions/poids, score, pool, seed/tirages, avis IA retenu ou raison de fallback, événement choisi, rôles, scopes, validation dure avant/après PREPARE, réservations, effets, résultat, memory deltas, mutation IDs/hashes, consommation d'unicité, faits/outbox, timings et erreurs.

Les candidats écartés par les index sont résumés par raisons/volumes ; les candidats effectivement évalués sont conservés intégralement. Un replay exact nécessite le snapshot des features évaluées et les règles/version de calcul ; les seuls résumés textuels ne suffisent pas. Les décisions insignifiantes répétitives peuvent être agrégées ; les échecs de sécurité et toute mutation physique gardent un audit durable détaillé. Les blobs sont partagés par hash plutôt que recopier de grands plans à chaque ligne.

Le carnet visible est un store distinct : `JournalEntry(player_uuid, source_fact_id?, chosen_at_tick, player_text?, visible_summary)`. Une perception hors quête n'y entre que via `record_observation` du joueur ; les règles d'un journal de quête restent propriété explicite du Quest Core. L'audit admin et la mémoire technique significative ne donnent pas automatiquement un spoiler au joueur.
