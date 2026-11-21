# WORLD_DIRECTOR_AI_LAYER

Contrat 1.0.0. La couche IA est facultative au chargement, au runtime et dans les tests. Le même Director fonctionne avec `DisabledAiAdvisor`.

## 1. Frontière et point d'intervention

L'IA reçoit uniquement une projection minimale de candidats déjà admissibles, de faits autorisés et de transitions de chaîne déjà compilées. Elle peut proposer des ajustements bornés de log-poids, choisir une variante parmi celles fournies, ou assembler une continuation avec les transitions/primitives autorisées dans l'horizon fourni. Elle ne reçoit aucun port monde, commande Minecraft, script, classe à charger, permission de mutation ou accès au WAL.

Le déterministe calcule immédiatement un résultat de fallback. En mode IA désactivé, indisponible, circuit ouvert ou sans budget, ce résultat est utilisé sans attente. Si l'IA est sollicitée, elle dispose de la deadline `ai.deadline_ms` sur transport isolé. À expiration, l'acteur adopte le fallback et invalide le token de réponse. Un résultat tardif ne modifie ni un événement choisi, ni une chaîne déjà engagée.

## 2. Entrée versionnée

`AiInput` : protocol_version, request_id, world pseudonym, decision_id, catalog_hash, context_hash, candidate_set_hash, expires_monotonic_deadline, candidate DTOs, allowed_chain_transitions, planning_horizon, output_limits. Le timeout absolu process n'est pas persisté comme échéance réutilisable au reboot.

Chaque candidat contient `candidate_id`, event_definition_id, category/families, features normalisées, score déterministe, coûts normalisés, pseudonymes de cibles et tags de contexte autorisés. Les coordonnées exactes, UUID réels, inventaires, chat, vocal, clés API, contenu de fichiers administratifs et statut streamer sont exclus. Les notes de joueurs/textes d'objets ne sont pas interprétés comme instructions. Les contenus textuels facultatifs sont marqués comme données non fiables et ne peuvent élargir l'espace de sortie.

Les candidates IDs désignent une attribution complète (événement, variante, région et rôles), pas seulement un nom d'événement. Le candidat qui change de cible doit être généré/validé par le moteur avant un éventuel prochain avis IA.

## 3. Sortie exacte

`schemas/ai-proposal.schema.json` ferme l'objet : `protocol_version`, `request_id`, `candidate_set_hash`, `adjustments[]`, `arc_proposals[]`, `rationale_codes[]`. Un adjustment = candidate_id + delta_log_weight ; un arc = chain_id + ordered transition_ids. Aucun champ `command`, `script`, `position`, `damage`, `primitive_args` ou nom libre d'événement n'est accepté.

Validations, dans cet ordre : taille/octets/timeout ; JSON sans trailing text ni clés doublons ; schéma fermé ; égalité request/hash/version ; absence de doublons ; candidats/transitions présents dans l'entrée ; nombre d'éléments et amplitude numériques ; graphe d'arc valide, causalité, horizon et budgets ; contexte encore frais ; validators d'admission physiques habituels. Une sortie invalide entière entraîne le fallback ; pas d'extraction opportuniste de morceaux d'un texte non conforme.

`delta_log_weight` est borné par `ai.max_abs_log_delta`, puis ajouté au log-score. La sélection reste le tirage pondéré de la spec. L'IA ne transforme pas `REQUIRE` en préférence, ne remplit pas un fait de quête et n'annule pas une sécurité. Un arc proposé reste un plan : chaque étape passe ultérieurement par une nouvelle éligibilité et une nouvelle admission.

## 4. Transport, disponibilité et budget

Port `AiTransport` derrière `AiAdvisorPort`. Contrat retenu pour un backend facultatif : POST JSON UTF-8 vers un endpoint configuré `/v1/director/propose`, réponse JSON conforme au schéma, en-tête bearer secret injecté hors données/audits si nécessaire. Un adaptateur de fournisseur peut traduire ce contrat, mais le moteur n'est lié à aucun SDK ou modèle. Sans endpoint explicitement configuré, le transport reste désactivé ; aucun téléchargement de modèle ni appel payant n'est démarré par défaut.

Le profil central définit concurrence, deadline, plafond de sortie et fréquence d'appel. Une seule proposition utile par fenêtre, déduplication par hash de contexte/candidats. Circuit breaker après échecs répétitifs, demi-ouverture avec probe borné, puis retour automatique. Le seuil d'échec et les temps sont des calibrations techniques. Les erreurs IA ne mettent pas le Director en pause.

Les secrets sont lus par configuration privée/env dédiée ; jamais dans un événement JSON ou un payload client. Les audits conservent les métadonnées de modèle/adapter et l'avis numérique accepté, pas des credentials ni des chaînes de raisonnement. L'explication est donnée par reason codes et score breakdown auditable.

## 5. Replay et imprévisibilité

Un avis IA peut être non reproductible entre deux appels ; l'avis accepté pour une décision est enregistré. Rejouer utilise cet avis exact, son hash et les tirages stockés ; il ne rappelle pas le modèle. Un replay peut aussi comparer `WITHOUT_AI` et `WITH_RECORDED_AI`, sans effet monde. L'absence d'IA conserve déjà de la variété grâce au tirage pondéré ; son apport optionnel ne constitue jamais une dépendance de scénario.

## 6. Oracles de qualification

Matrice future : DisabledAiAdvisor, timeout avant/après deadline, réponse tardive, panne réseau, JSON partiel, texte autour du JSON, commandes injectées, candidat inconnu, hash périmé, répétition, score infini, arc cyclique invalide, sortie trop grosse, circuit ouvert, danger devenu inadmissible après la réponse. Chaque cas doit laisser un choix déterministe disponible et un code de fallback ; aucun ne doit produire de mutation hors contrat. Le test de cette livraison vérifie seulement les schemas et fixtures de sortie, pas un appel de modèle réel.
