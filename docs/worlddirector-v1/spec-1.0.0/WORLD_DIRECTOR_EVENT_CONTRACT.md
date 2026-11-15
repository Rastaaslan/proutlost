# WORLD_DIRECTOR_EVENT_CONTRACT

Contrat contenu 1.0.0. Le schéma fermé est `schemas/event-definition.schema.json`. Le contenu est déclaratif ; aucun JSON n'embarque une commande, du Java, du JavaScript ou une expression libre.

## 1. Chargement, validation et reload

Chemin data pack : `data/<namespace>/director/events/<path>.json`. L'ID déclaré doit correspondre au namespace/path du fichier ; l'exemple `examples/events/` est un dossier de fixtures, pas un datapack déjà installé. Les chaînes suivent `director/chains/`. Le loader compile les JSON vers un `CatalogSnapshot` immutable versionné/hashé.

Pipeline : parser avec rejet de clés doublons et taille bornée → schéma → résolution de paramètres → vérification des IDs/références/roles → DAG de steps → capabilités du registre → analyse de budget/danger/recovery → hash de contenu compilé. Une référence inconnue n'est pas transformée en no-op. Le catalogue actif reste en place si le nouveau catalogue ne compile pas ; le rapport désigne fichier, JSON pointer et reason code.

Les schémas utilisent JSON Schema 2020-12 pour la structure. Des règles sémantiques supplémentaires restent obligatoires : `min≤max`, entier requis après résolution, fin d'une séquence avant durée maximale, absence de cycle dans les steps, rôle existant, coût déclaré suffisant, phase WARN précédant toute action dangereuse, unicité/ownership compatibles, primitives/assets disponibles, dépendances de fact types valides. Le schéma seul ne prouve pas la sûreté d'un événement.

`@test:<clé>` référence uniquement une entrée de `DEFAULT_FOR_TESTING.json`. La résolution ne lit ni fichier arbitraire ni variable d'environnement, ne fait aucun calcul et impose le type/range du champ de destination. Toute utilisation de ce profil maintient `calibration=DEFAULT_FOR_TESTING`. Un futur profil approuvé versionné remplacera ces références par un registre de paramètres approuvés ou des valeurs numériques validées ; il ne suffira pas de renommer le statut. Chaque instance conserve le hash et les valeurs résolues de son admission.

## 2. Sémantique des champs

| Champ | Contrat |
| --- | --- |
| schema_version / content_revision / id | Format, révision de contenu et identité stable distincts ; changer la révision ne reset pas l'unicité |
| category / families | AMBIENT, HYBRID, NARRATIVE ; familles orthogonales au mode de ciblage |
| selection | poids positif et plancher de score ; hard filtering indépendant |
| target | région/solo/groupe, tags admissibles, rôles min/max, équité, overlap explicitement autorisé |
| eligibility | arbre typé all/any/not et feuilles de capteurs ; UNKNOWN ne satisfait pas une condition de sécurité |
| timing | durée maximale de l'instance, cooldowns world/region/player ; les cooldowns safety du moteur s'ajoutent toujours |
| uniqueness | scope et point de consommation ; réservé avant effet et conservé après consommation/incertitude |
| budget | enveloppe déclarée ; l'analyse de primitives peut l'augmenter ou refuser, jamais ignorer le coût réel |
| conflicts | claims symboliques compilés vers ressources concrètes, compatibilité, merge explicite, priorité |
| safety | risque, capabilities, profil physique/WARN, cession joueur et politique de carnet |
| steps | DAG d'étapes, args fermés par primitive, délai après prédécesseurs confirmés, politique d'échec |
| recovery | mode préféré parmi RECOVER/CLEANUP/COMPLETE_FROM_STATE/ABORT_SAFE et deadline |
| completion | fait terminal confirmé, trace optionnelle et probabilité de conservation additionnelle |

Le scheduler ne traite pas les claims symboliques JSON comme une liste exhaustive fiable : le registre de primitives développe les footprints, audiences et coûts effectifs. Une `role.max` limite les rôles de l'événement, pas les personnes physiquement affectables. Toute audience incidente d'un danger est incluse dans safety/WARN, même hors rôles. Une entrée tardive dans l'enveloppe exige un signe permanent et une possibilité d'anticipation sûre ; si la recette ne peut la garantir, suspendre les tranches dangereuses concernées et revalider.

`persistence_probability` ne décide pas si un fait réellement survenu est enregistré : ce fait l'est toujours selon son importance. Elle concerne la conservation additionnelle d'une trace éligible après l'effet ; une ressource cédée au joueur est préservée quel que soit le tirage. Une primitive déclarant un état obligatoirement persistant exige une probabilité résolue égale à l'unité, vérifiée sémantiquement. Les politiques explicites `SETTLE_WORLD` des animaux ou choix d'anchor durable ne sont pas annulées par une probabilité générique.

## 3. Contrat Java d'une primitive

Chaque `PrimitiveDescriptor` définit ID, version, argument codec fermé, capabilities, coûts minimaux, footprint resolver, état capturable, ownership domains, effets non inversibles, durée maximale, politique de pause, cleanup, recovery et inverse/merge éventuel. Le registre est chargé par Java ; un JSON ne crée pas une nouvelle capacité physique.

```java
interface DirectorPrimitive<A> {
  PrimitiveDescriptor descriptor();
  PreparedEffect plan(A args, PlanningSnapshot snapshot);
  ValidationResult validate(PreparedEffect plan, LiveValidationSnapshot current);
  EffectProgress applySlice(PreparedEffect plan, EffectCursor cursor, CommitPermit permit);
  ReconcileResult reconcile(PreparedEffect plan, RecoveryEvidence evidence);
  CleanupPlan cleanup(PreparedEffect plan, CleanupReason reason, OwnershipView ownership);
}
```

`plan` est pur ; `validate` ne déclenche aucun effet. `applySlice` est server-thread-only via adaptateur ; le registre sait si l'effet ne produit qu'un payload ciblé. L'inverse est une nouvelle primitive préparée/validée/journalisée ; aucune méthode ne dispose d'un accès illimité au serveur. Le curseur est sérialisable, versionné et idempotent par operation token.

## 4. Registre minimum de contenu

| Primitive de fixture | Effet réel / fin / reprise |
| --- | --- |
| `target_sound` | son ciblé avec volume/rayon/répétition bornés ; Stop/TTL ; pas de preuve psychologique |
| `target_particles` | particules ciblées ; nombre et durée bornés ; cleanup au reset |
| `apparition` | silhouette/objet rendu client sans collision ; suivi bref plafonné ; aucun proxy physique |
| `local_weather` | ambiance locale visuelle/sonore ; n'affecte pas pluie/fertilité globale ; TTL et reset |
| `camp_growth` | vraie pose/évolution de végétation selon recette connue ; pré/post et cession joueur ; rollback conditionnel |
| `fauna_migrate` | vraie cohorte et navigation de corridor ; slots persistants, vagues, adaptation TPS ; adoption respectée |
| `shared_choice` | perceptions complémentaires + interaction sur anchor commun ; valide rôles/portée et choix serveur ; timeout garde état initial |
| `goat_visit` | vraie chèvre possédée, petite situation de camp ; variante SAFE sans ram/knockback autonome ; pas de téléportation/poursuite cachée ; adoption/cleanup selon contrat |
| `warning` | signes perceptifs/physiques de danger lisibles, preuve de déploiement et délai de réaction ; pas de saut de WARN après reboot |
| `landslide` | vraie transformation bornée de terrain/débris et danger contrôlé ; stabilisation et reprise par tranches |
| `trace` | état d'anchor/objet examinable persistant explicitement connu ; propriété et attribution de fait |

Le moteur v1 doit aussi fournir les **contrats** de primitives : météo globale dimensionnelle avec lease et restitution conditionnelle ; eau dirigée à propagation bornée ; combustion dirigée ; débris contrôlés ; déplacement d'objet décoratif ; modification de règles de spawn contextuelles. Leur contenu final n'est pas imposé par les fixtures, mais leurs budgets, capture/cleanup et plan de tests ne peuvent être reportés à une réinvention de l'architecture.

Une restitution météo compare la lease et l'évolution connue ; elle n'écrase pas une météo modifiée ensuite par un autre système. La règle de spawn contextuelle est un modificateur local/versionné révocable, pas une réécriture permanente des règles globales sans provenance. L'influence de mouvement utilise un adaptateur compatible avec l'espèce ; sa capacité absente refuse seulement la variante dépendante.

## 5. Recettes physiques et danger

`MutationProfile` : recipe_id/version, allowed primitive IDs, affected resource codecs, footprint bounds resolver, preconditions, propagation mask strategy, maximum declared work, neighbor dependency radius, safe exit constraints, ownership policy, construction impact contract?, inverse plan, noninvertible declarations, recovery strategy, required GameTest IDs. Les paramètres quantitatifs utilisent le profil central ; le contenu ne peut fournir sa propre implémentation d'inverse.

`WarningProfile` : sign asset/physical marker IDs, audience resolver, visibility/audibility constraints, lead time, persistent perimeter markers, new-arrival handling, cancellation signal, proof codec. Le succès du warning atteste la possibilité technique d'observer/anticiper, jamais l'attention humaine. Les warnings dépendant d'un asset non chargé rendent le danger inadmissible.

`DamageProfile` : types de dégâts connus, par-hit et cumul par audience, timing, zones exclues, critères de pause et ledger de hits idempotent. Une compensation physique n'annule pas les dégâts déjà reçus. La cible peut ignorer la nature exacte de la catastrophe, mais pas subir un grand impact sans signes.

`ConstructionImpactContract` : classes de construction affectables, exclusion des ressources/inventaires non capturables, ampleur, avertissement, état préalable, compensation, traitement des interventions joueur. Ce champ n'instaure pas une immunité générale des constructions ; il oblige une définition explicite lorsqu'un événement peut les modifier. La mission de conception n'approuve pas les dégâts/maxima du profil de test.

Le risque d'éboulement se calcule depuis rain/saturation/slope/recentActivity normalisés : somme des contributions avec `risk.landslide.*_weight`, clamp, puis seuil d'éligibilité. WATCH entretient ce risque à cadence lente ; WARN réserve l'enveloppe ; IMPACT applique tranches/débris ; STABILIZE contrôle la propagation ; RESOLVE décide traces durables et faits. Pluie seule n'est pas un ordre de catastrophe.

## 6. Chaînes courtes et dynamiques

Schéma : `schemas/chain-definition.schema.json`. Un `transition.event_id` désigne l'événement à demander après consommation du fait déclencheur ; l'état `to` signifie attente de son résultat, pas réussite anticipée. `event_id=null` termine/bifurque/installe un état régional, sans magie monde implicite. Les transitions terminales ne peuvent avoir un événement à démarrer. Le monde est modifié uniquement par une étape de primitive journalisée ou un état logique explicitement prévu.

Une chaîne courte respecte un petit arc de plusieurs événements et un graphe acyclique. Une chaîne dynamique peut avoir des boucles ; chaque tour consomme un fait neuf, respecte le gap/cooldowns, réévalue pertinence et conserve un horizon de planification borné. Elle n'a pas de longueur totale fixe. L'horizon limite la mémoire/planification en vol, pas son existence. Si plusieurs transitions correspondent au même fait, sélectionner par conditions, score et tirage audité ; enregistrer la branche avant d'émettre la requête. Un même `(chain_id,transition_id,fact_id)` ne s'exécute qu'une fois.

Les faits déclencheurs peuvent être des résultats Director ou des observations agrégées approuvées. La chaîne est liée à un scope et une lineage ; ses candidats doivent satisfaire ce scope et les contraintes propres de l'événement suivant. Si l'événement suivant exige une autre zone, une transition de scope explicite est nécessaire ; sinon la chaîne attend, bifurque ou s'interrompt. Les fixtures illustrent le format, pas une garantie que toute carte possède les tags nécessaires.

`examples/chains/camp_short_arc.json` illustre camp transformé → témoin solo → choix collectif → souvenir régional. `examples/chains/living_scar.json` illustre catastrophe → installation animale → manifestations renouvelables → état durable ou interruption. Les conditions peuvent devenir invalides ; ce résultat doit apparaître dans le dashboard, sans maintien indéfini d'un verrou physique.

## 7. Couverture concrète de MVP

| Fixture | Catégories et familles couvertes | Ce que le test doit démontrer |
| --- | --- | --- |
| safe_micro | AMBIENT, ambiance minimale | présence légère si aucun événement régulier admissible |
| local_mist | AMBIENT, ambiance | météo locale sans modifier la météo commune |
| camp_regrowth | AMBIENT, mémoire, chaîne | routine/abandon puis vraie trace physique |
| parallel_steps | AMBIENT, perception, solo | ciblage individuel et unicité personnelle |
| shared_choice | HYBRID, social, perception | coopération, perceptions complémentaires et état commun |
| goat_guest | AMBIENT, humour, social | événement drôle réel et cleanup/adoption |
| animal_migration | AMBIENT, faune | nombreux animaux réels avec garde-fous |
| controlled_landslide | AMBIENT, danger, catastrophe | avertissement, vraie transformation, reprise sûre |
| required_signal | NARRATIVE, chaîne/perception | obligation de quête sans bypass sécurité |

Ces neuf exemples couvrent les dix domaines minimum de la mission avec les deux recettes de chaîne. Ils ne prétendent pas être le catalogue final d'environ quinze événements évoqué dans le Concept. Leur validation statique n'atteste ni assets finaux, ni qualité du gameplay, ni compatibilité avec chaque mod du pack.

## 7 bis. Primitives physiques à développer selon ces contrats

| Primitive complémentaire | Modèle, état persistant et algorithme imposés |
| --- | --- |
| `controlled_water` | Cellules de fluide Director non auto-propagées par ticks vanilla, enregistrées et rendues comme fluide partagé ; état `{cell, volume_units, level, owner_epoch}`. La physique de nage/immersion et les dégâts déclarés restent réels et communs. Répartir les transferts conservatifs de volume entre voisins contenus dans le masque, d'abord vers le bas puis vers les niveaux inférieurs, ordre seedé stable. Chaque transfert est une mutation pré/post bornée ; aucun volume n'est créé hors source déclarée. Les bords du masque sont imperméables au moteur. Une interaction joueur cède la cellule ; les cellules cédées ne sont plus des destinations automatiques. Un bucket pris par le joueur n'est pas réinjecté par cleanup. STABILIZE stoppe les nouvelles arrivées, puis retire/restaure uniquement les cellules encore possédées. Les paramètres de niveau/transfert sont `physical.fluid_*`. Un adaptateur vers un fluide tiers n'est admissible que s'il fournit les mêmes garanties. |
| `controlled_fire` | Cellules de combustion dirigée sans propagation vanilla autonome ; état `{cell, energy_remaining, burn_cursor, owner_epoch, hit_ledger}`. À cadence bornée, choisir seulement des voisins dans le masque et des blocs dont le codec pré/post est connu, avec RNG enregistré et probabilité du profil. L'énergie décroît et chaque combustion devient une mutation journalisée ; DamageProfile applique les vrais dégâts sous plafonds. STABILIZE coupe les nouveaux foyers, éteint les seules cellules possédées et conserve les traces décidées. Les feux vanilla hors moteur ne sont pas présentés comme contrôlés. |
| `controlled_debris` | Slots d'entités/segments physiques bornés, trajectoire/zone d'atterrissage vérifiées, aucun impact hors enveloppe. L'identité du hit est `(event,slot,target,impact_epoch)` pour empêcher un retry de doubler un dégât. Atterrissage journalisé comme cellule, ou retrait propre si cible modifiée. Les débris non réconciliables passent en cleanup sûr, pas en respawn libre. |
| `global_weather_lease` | Lease par dimension : pré-état météo, état demandé, curseur de durée, révision externe. Appliquer/renouveler via thread serveur ; restituer seulement si l'état est encore attribuable à cette lease. Un changement externe clôt la propriété. Le client local peut superposer une ambiance mais ne change pas cette physique. |
| `contextual_spawn_rule` | Règle additive dans un registre Director local aux scopes, validité/TTL/cursor de lease ; évaluée au point d'admission d'un spawn compatible. Les plafonds vanilla/serveur/région et d'autres mods ne sont jamais majorés par contournement. Retrait de lease révoque la règle, sans supprimer la faune naturelle. |
| `decorative_move` | Ressource décorative précisément identifiée, pré/post position et composants, aucune copie d'inventaire. Vérifier source possédée/accord de recette, destination sûre et inverse possible ; à une interaction externe, céder. Pas d'heuristique prenant un objet utile pour un simple décor. |

Les blocs/fluides Director contrôlés sont des types techniques à enregistrer par le futur mod, pas des assets fournis par cette livraison. Ils servent une transformation physique, pas une divergence client. Leurs codecs, comportements de collision, interaction avec eau/feu tiers, adoption et reprise sont couverts par les GameTests de la classe de danger. Une catastrophe « tempête » combine lease météo, vent perceptif et éventuellement débris/terrain approuvés ; un séisme simulé combine signes et mouvements de terrain connus, sans prétendre simuler une tectonique réelle.

## 8. Définitions complètes

Les définitions qui suivent sont reproduites intégralement depuis les fixtures, sans ellipse. Les fichiers JSON du dossier restent la source exacte pour les jobs d'implémentation.

<!-- COMPLETE_FIXTURES -->

### Événement : animal_migration

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:animal_migration",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "FAUNA"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "REGION",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "region_tag",
    "tag": "proutlost:fauna_corridor"
  },
  "timing": {
    "max_duration_ticks": "@test:event.migration.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.migration.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "ENTITY:COHORT",
      "NAVIGATION:CORRIDOR"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "entity.migration_v1",
      "habitat.corridor_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "migrate",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:fauna_migrate",
      "args": {
        "species": "minecraft:sheep",
        "cohort_size": "@test:event.migration.cohort_size",
        "corridor_tag": "proutlost:fauna_corridor",
        "arrival_policy": "SETTLE_WORLD"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "RECOVER",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:animal_migration_observed",
    "durable_trace": "proutlost:fauna_settled",
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : camp_regrowth

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:camp_regrowth",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "MEMORY",
    "CHAIN"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "REGION",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "all",
    "conditions": [
      {
        "type": "pattern_confidence",
        "pattern": "CAMP_ROUTINE",
        "minimum": "@test:pattern.camp_confidence_min"
      },
      {
        "type": "memory_flag",
        "flag": "proutlost:camp_abandoned",
        "expected": true
      }
    ]
  },
  "timing": {
    "max_duration_ticks": "@test:event.default.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.growth.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "BLOCKS:FOOTPRINT"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "mutation.plants_v1"
    ],
    "mutation_profile": "proutlost:test_camp_plants",
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "growth",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:camp_growth",
      "args": {
        "anchor_tag": "proutlost:abandoned_camp",
        "recipe": "proutlost:test_camp_plants",
        "max_blocks": "@test:event.growth.max_blocks"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "COMPLETE_FROM_STATE",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:camp_regrowth_observed",
    "durable_trace": "proutlost:regrown_camp",
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : controlled_landslide

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:controlled_landslide",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "DANGER",
    "DISASTER",
    "MEMORY"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "REGION",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.group_max",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "risk_minimum",
    "risk_id": "proutlost:landslide",
    "minimum": "@test:event.disaster.risk_min"
  },
  "timing": {
    "max_duration_ticks": "@test:event.disaster.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.disaster.global_units",
    "region_units": "@test:event.disaster.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.disaster.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "BLOCKS:FOOTPRINT",
      "DANGER:FOOTPRINT"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "DANGER",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "CONTROLLED_DANGER",
    "required_capabilities": [
      "mutation.terrain_v1",
      "danger.debris_v1",
      "warning.readable_v1"
    ],
    "mutation_profile": "proutlost:test_bounded_landslide",
    "warning_profile": "proutlost:test_landslide_warning",
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "warn",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:warning",
      "args": {
        "role": "witness",
        "profile": "proutlost:test_landslide_warning",
        "minimum_lead_ticks": "@test:event.disaster.warning_ticks"
      },
      "on_failure": "ABORT_SAFE"
    },
    {
      "id": "impact",
      "after": [
        "warn"
      ],
      "delay_ticks": "@test:event.disaster.warning_ticks",
      "primitive": "proutlost:landslide",
      "args": {
        "recipe": "proutlost:test_bounded_landslide",
        "max_blocks": "@test:event.disaster.max_blocks",
        "damage_profile": "proutlost:test_debris_damage"
      },
      "on_failure": "PARTIAL_AND_STABILIZE"
    }
  ],
  "recovery": {
    "mode": "RECOVER",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:controlled_landslide_observed",
    "durable_trace": "proutlost:landslide_scar",
    "persistence_probability": "@test:event.disaster.trace_persistence_probability"
  }
}
```

### Événement : goat_guest

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:goat_guest",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "HUMOUR",
    "SOCIAL"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "SOLO",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "players_present",
    "minimum": "@test:event.role.one"
  },
  "timing": {
    "max_duration_ticks": "@test:event.default.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.goat.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "ENTITY:GOAT_SLOT"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "entity.goat_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "guest",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:goat_visit",
      "args": {
        "role": "witness",
        "anchor_tag": "proutlost:camp"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "RECOVER",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:goat_guest_observed",
    "durable_trace": null,
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : local_mist

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:local_mist",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "AMBIENCE"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "SOLO",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "players_present",
    "minimum": "@test:event.role.one"
  },
  "timing": {
    "max_duration_ticks": "@test:event.mist.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "PERCEPTION:WITNESS"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "perception.local_weather_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "mist",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:local_weather",
      "args": {
        "role": "witness",
        "profile": "proutlost:test_mist"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "CLEANUP",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:local_mist_observed",
    "durable_trace": null,
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : parallel_steps

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:parallel_steps",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "PERCEPTION",
    "SOLO"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "SOLO",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "solo_ready",
    "minimum_isolated_ticks": "@test:event.solo.isolated_ticks"
  },
  "timing": {
    "max_duration_ticks": "@test:event.default.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "ONCE_PER_PLAYER",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "PERCEPTION:WITNESS"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "perception.sound_v1",
      "perception.silhouette_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "steps",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:target_sound",
      "args": {
        "role": "witness",
        "sound": "minecraft:block.gravel.step",
        "volume": "@test:event.micro.volume",
        "radius_blocks": "@test:event.micro.radius_blocks",
        "repeats": "@test:event.micro.repeats"
      },
      "on_failure": "ABORT_SAFE"
    },
    {
      "id": "apparition",
      "after": [
        "steps"
      ],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:apparition",
      "args": {
        "role": "witness",
        "model": "proutlost:test_silhouette",
        "follow_ticks": "@test:event.solo.follow_ticks",
        "max_distance_blocks": "@test:event.solo.max_distance_blocks"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "CLEANUP",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:parallel_steps_observed",
    "durable_trace": null,
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : required_signal

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:required_signal",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "NARRATIVE",
  "families": [
    "PERCEPTION",
    "CHAIN"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "SOLO",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "requirement_pending",
    "selector": "proutlost:required_signal"
  },
  "timing": {
    "max_duration_ticks": "@test:event.default.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "ONCE_PER_WORLD",
    "consume_on": "OUTCOME_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "ANCHOR:STATE"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "REQUIRE",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "anchor.state_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "QUEST_OWNED",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "trace",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:trace",
      "args": {
        "anchor_tag": "proutlost:signal_anchor",
        "state_tag": "proutlost:signal_revealed",
        "persistent": true
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "COMPLETE_FROM_STATE",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:required_signal_observed",
    "durable_trace": "proutlost:signal_revealed",
    "persistence_probability": "@test:event.required.persistence_probability"
  }
}
```

### Événement : safe_micro

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:safe_micro",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "AMBIENT",
  "families": [
    "SAFE_MICRO_EVENT",
    "AMBIENCE"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.micro.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "SOLO",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.one",
        "max": "@test:event.role.one",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "players_present",
    "minimum": "@test:event.role.one"
  },
  "timing": {
    "max_duration_ticks": "@test:event.micro.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "REPEATABLE",
    "consume_on": "EFFECT_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.micro.units",
    "region_units": "@test:event.micro.units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "PERCEPTION:WITNESS"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "MICRO",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "perception.sound_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "sound",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:target_sound",
      "args": {
        "role": "witness",
        "sound": "minecraft:block.campfire.crackle",
        "volume": "@test:event.micro.volume",
        "radius_blocks": "@test:event.micro.radius_blocks",
        "repeats": "@test:event.micro.repeats"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "CLEANUP",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:safe_micro_observed",
    "durable_trace": null,
    "persistence_probability": "@test:event.default.trace_persistence_probability"
  }
}
```

### Événement : shared_choice

```json
{
  "schema_version": 1,
  "content_revision": 1,
  "id": "proutlost:shared_choice",
  "calibration": "DEFAULT_FOR_TESTING",
  "category": "HYBRID",
  "families": [
    "SOCIAL",
    "PERCEPTION",
    "CHAIN"
  ],
  "enabled": true,
  "selection": {
    "base_weight": "@test:event.default.weight",
    "score_floor": "@test:event.default.score_floor"
  },
  "target": {
    "scope": "GROUP",
    "region_tags": [],
    "player_policy": "FAIR",
    "roles": {
      "witness": {
        "min": "@test:event.role.group_min",
        "max": "@test:event.role.group_max",
        "overlap": false
      }
    }
  },
  "eligibility": {
    "type": "all",
    "conditions": [
      {
        "type": "players_present",
        "minimum": "@test:event.role.group_min"
      },
      {
        "type": "region_tag",
        "tag": "proutlost:clearing"
      }
    ]
  },
  "timing": {
    "max_duration_ticks": "@test:event.default.duration_ticks",
    "cooldowns": {
      "world_ticks": "@test:event.default.cooldown_world_ticks",
      "region_ticks": "@test:event.default.cooldown_region_ticks",
      "player_ticks": "@test:event.default.cooldown_player_ticks"
    }
  },
  "uniqueness": {
    "scope": "ONCE_PER_REGION",
    "consume_on": "OUTCOME_CONFIRMED"
  },
  "budget": {
    "global_units": "@test:event.default.global_units",
    "region_units": "@test:event.default.region_units",
    "server_ms_per_tick": "@test:event.default.server_ms_per_tick",
    "entity_slots": "@test:event.default.entity_slots",
    "mutation_units": "@test:event.default.mutation_units",
    "client_units": "@test:event.default.client_units"
  },
  "conflicts": {
    "claims": [
      "PERCEPTION:WITNESS"
    ],
    "compatibility_tags": [],
    "allow_merge_with": [],
    "priority": "AMBIENT",
    "preemption": "YIELD_BEFORE_COMMIT"
  },
  "safety": {
    "risk": "SAFE",
    "required_capabilities": [
      "interaction.shared_anchor_v1",
      "perception.symbol_v1"
    ],
    "mutation_profile": null,
    "warning_profile": null,
    "player_edit_policy": "YIELD",
    "journal_policy": "PLAYER_OPT_IN",
    "participant_loss": "ABORT_IF_MIN_LOST"
  },
  "steps": [
    {
      "id": "choice",
      "after": [],
      "delay_ticks": "@test:event.default.delay_ticks",
      "primitive": "proutlost:shared_choice",
      "args": {
        "roles": [
          "witness"
        ],
        "anchor_tag": "proutlost:clearing",
        "choice_ids": [
          "proutlost:listen",
          "proutlost:leave_trace"
        ],
        "timeout_policy": "KEEP_UNCHANGED"
      },
      "on_failure": "ABORT_SAFE"
    }
  ],
  "recovery": {
    "mode": "CLEANUP",
    "deadline_ticks": "@test:scheduler.recovery_deadline_ticks"
  },
  "completion": {
    "fact_type": "proutlost:shared_choice_observed",
    "durable_trace": "proutlost:clearing_choice",
    "persistence_probability": "@test:event.shared.persistence_probability"
  }
}
```

### Chaîne : camp_short_arc

```json
{
  "schema_version": 1,
  "id": "proutlost:camp_short_arc",
  "content_revision": 1,
  "calibration": "DEFAULT_FOR_TESTING",
  "mode": "SHORT_ARC",
  "scope": "REGION",
  "entry_state": "CAMP_CHANGED",
  "states": [
    "CAMP_CHANGED",
    "WITNESS_READY",
    "SHARED_CHOICE",
    "COMPLETE"
  ],
  "planning_horizon": "@test:chain.planning_horizon",
  "minimum_step_gap_ticks": "@test:chain.step_gap_ticks",
  "relevance_minimum": "@test:chain.relevance_min",
  "transitions": [
    {
      "id": "proutlost:camp_to_witness",
      "from": "CAMP_CHANGED",
      "to": "WITNESS_READY",
      "on_fact": "proutlost:camp_regrowth_observed",
      "event_id": "proutlost:parallel_steps",
      "condition": {
        "type": "players_present",
        "minimum": "@test:event.role.one"
      },
      "consume_fact_once": true,
      "settle_state_tag": null
    },
    {
      "id": "proutlost:witness_to_choice",
      "from": "WITNESS_READY",
      "to": "SHARED_CHOICE",
      "on_fact": "proutlost:parallel_steps_observed",
      "event_id": "proutlost:shared_choice",
      "condition": {
        "type": "region_tag",
        "tag": "proutlost:clearing"
      },
      "consume_fact_once": true,
      "settle_state_tag": null
    },
    {
      "id": "proutlost:choice_to_complete",
      "from": "SHARED_CHOICE",
      "to": "COMPLETE",
      "on_fact": "proutlost:shared_choice_observed",
      "event_id": null,
      "condition": {
        "type": "region_tag",
        "tag": "proutlost:clearing"
      },
      "consume_fact_once": true,
      "settle_state_tag": "proutlost:remembered_camp"
    }
  ],
  "terminal_states": [
    "COMPLETE"
  ],
  "recovery": "RECOVER"
}
```

### Chaîne : living_scar

```json
{
  "schema_version": 1,
  "id": "proutlost:living_scar",
  "content_revision": 1,
  "calibration": "DEFAULT_FOR_TESTING",
  "mode": "DYNAMIC",
  "scope": "REGION",
  "entry_state": "SCAR",
  "states": [
    "SCAR",
    "RECOVERING",
    "ACTIVE",
    "SETTLED",
    "INTERRUPTED"
  ],
  "planning_horizon": "@test:chain.planning_horizon",
  "minimum_step_gap_ticks": "@test:chain.step_gap_ticks",
  "relevance_minimum": "@test:chain.relevance_min",
  "transitions": [
    {
      "id": "proutlost:scar_to_recovery",
      "from": "SCAR",
      "to": "RECOVERING",
      "on_fact": "proutlost:controlled_landslide_observed",
      "event_id": "proutlost:animal_migration",
      "condition": {
        "type": "region_tag",
        "tag": "proutlost:fauna_corridor"
      },
      "consume_fact_once": true,
      "settle_state_tag": null
    },
    {
      "id": "proutlost:recovery_to_active",
      "from": "RECOVERING",
      "to": "ACTIVE",
      "on_fact": "proutlost:animal_migration_observed",
      "event_id": "proutlost:local_mist",
      "condition": {
        "type": "players_present",
        "minimum": "@test:event.role.one"
      },
      "consume_fact_once": true,
      "settle_state_tag": null
    },
    {
      "id": "proutlost:active_continue",
      "from": "ACTIVE",
      "to": "ACTIVE",
      "on_fact": "proutlost:local_mist_observed",
      "event_id": "proutlost:local_mist",
      "condition": {
        "type": "memory_flag",
        "flag": "proutlost:scar_still_relevant",
        "expected": true
      },
      "consume_fact_once": true,
      "settle_state_tag": null
    },
    {
      "id": "proutlost:active_settle",
      "from": "ACTIVE",
      "to": "SETTLED",
      "on_fact": "proutlost:local_mist_observed",
      "event_id": null,
      "condition": {
        "type": "memory_flag",
        "flag": "proutlost:scar_still_relevant",
        "expected": false
      },
      "consume_fact_once": true,
      "settle_state_tag": "proutlost:recovering_landscape"
    },
    {
      "id": "proutlost:interrupt_recovery",
      "from": "RECOVERING",
      "to": "INTERRUPTED",
      "on_fact": "proutlost:context_lost",
      "event_id": null,
      "condition": {
        "type": "memory_flag",
        "flag": "proutlost:scar_still_relevant",
        "expected": false
      },
      "consume_fact_once": true,
      "settle_state_tag": "proutlost:unfinished_recovery"
    }
  ],
  "terminal_states": [
    "SETTLED",
    "INTERRUPTED"
  ],
  "recovery": "RECOVER"
}
```
