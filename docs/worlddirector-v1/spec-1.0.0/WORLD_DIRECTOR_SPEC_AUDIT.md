# WORLD_DIRECTOR_SPEC_AUDIT

Revue de conception 1.0.0 · 23 octobre 2026. Cette revue porte sur le dossier livré ; elle n'est ni une revue d'un code Director existant ni une certification du fonctionnement de Minecraft.

## 1. Autorité et état réel de la mission

Les deux sources jointes ont été lues. Leur copie exacte et leurs SHA-256 sont dans `sources/`. La mission récente est normative. Le Concept contient plusieurs états successifs et une correction explicite recentrant le projet sur le monde autonome ; les restrictions antérieures qu'elle remplace ne sont pas traitées comme des décisions nouvelles.

Les dix documents obligatoires A–J existent. Les contrats structurants sont décidés : modules/ports, modèle d'acteur, stockage, threading, algorithmes de sélection/régions/mémoire, transactions/sagas, ownership, bus, protocoles, réseau, administration, IA et gates. Les fixtures proposent du contenu concret sans prétendre fixer le catalogue final. Les réglages non validés sont groupés dans `DEFAULT_FOR_TESTING.json`.

## 2. Traçabilité de la mission source

T = TECH_SPEC ; A = ARCHITECTURE ; D = DATA_MODEL ; E = EVENT_CONTRACT ; R = PERSISTENCE_AND_RECOVERY ; P = PERFORMANCE ; I = AI_LAYER ; H = ADMIN_V1 ; V = TEST_PLAN ; J = IMPLEMENTATION_PLAN. Les intitulés de fichiers complets sont dans README.

| Section source | Exigence | Couverture normative |
| --- | --- | --- |
| 1 | Spec suffisante pour implémentation | T, A, D, J |
| 2 | Autorité figée et TECHNICAL_CONFLICT | README, T §14, ticket |
| 3 | Autonomie et pipeline | T §2/5, A §3 |
| 4 | AMBIENT/HYBRID/NARRATIVE | D §7, E et schéma |
| 5 | PREFER/REQUIRE et safety | T §7, D §7, V T-QST |
| 6 | Rythme/micro/habitation | T §7/8, profil, E safe_micro |
| 7 | Concurrence multibudgets | T §10, P §2, V T-CONC |
| 8 | Régions logiques dynamiques | D §2/4, J01 |
| 9 | Mémoires et decay distincts | D §3, R §9 |
| 10 | Deux mémoires joueur sans profil psy | D §2/3/10 |
| 11 | Habitudes/CAMP_ROUTINE | D §4, V T-OBS |
| 12 | Pondération des meilleurs + exploration | T §7, V T-SEL |
| 13 | Score composé configurable | T §7, D §5, profil |
| 14 | Nouveauté sans épuisement obligatoire | T §7, D §3/5 |
| 15 | Tension globale et biais narratif limité | T §8, profil |
| 16 | Personnalité émergente | D §4 |
| 17 | Perceptions et carnet opt-in | T INV-07/12, D §10, H §3 |
| 18 | Apparitions/faux blocs/suivi bref | A §5, E §4, H §3 |
| 19 | Transformations substantielles journalisées | T §11, R §3/4 |
| 20 | Ownership/cession joueur | R §4, V T-MUT-02 |
| 21 | Priorité/compatibilité/merge | T §10, E §2, J05 |
| 22 | Faune réelle, migrations et caps/TPS | T §11, P §2/3, R §8 |
| 23 | Lifecycle entités/reboot/doublons | D §2/6, R §6/8 |
| 24 | Météo globale et locale | E §4/7 bis, V T-WEA |
| 25 | Catastrophes physiques dynamiques | T §11, E §5/7 bis, R §6 |
| 26 | Signes lisibles et vrai danger | T INV-15, E §5, V T-DIS |
| 27 | Conséquences persistantes causales | D §3/7, E §2/6 |
| 28 | Arcs courts/longs, branches/états | D §6, E §6 et deux chaînes |
| 29 | Quatre scopes d'unicité | D §2/6, R §7, schéma/fixtures |
| 30 | Nuit non automatiquement plus active | T §4/8, V T-PACE |
| 31 | Humour visible et raréfié en tension | T §8, E goat_guest, profil |
| 32 | Social/coopération | E shared_choice, H AnchorInteraction |
| 33 | Solo important sans prime au groupe | T §6, D §5, E parallel_steps |
| 34 | Objets/traces et déco joueur réversible | E §4/7 bis, R §4, V T-OBJ |
| 35 | IA facultative et limitée | I complet, V T-AI |
| 36 | Audit explicable et opacité joueur | D §10, I §5, H permissions |
| 37 | Dashboard v1 obligatoire | H §1/2/4/5, J07 |
| 38 | Debug/force/simulation/replay | H §2/5, V T-ADM |
| 39 | Logs/JSON/audit complet | D §10, R §1/9, H export |
| 40 | Observation incrémentale/budgets/DEGRADED | A §4, P complet |
| 41 | Quatre stratégies de recovery | R §5/6, D §6 |
| 42 | WorldPrep facultatif et lieux stables | D §8, schéma/import, J08 |
| 43 | API Quest Core producteurs/consommateurs | D §7, schéma/message, R §7 |
| 44 | Data-driven / Java safety | E §1–5, A §1/2 |
| 45 | Choix techniques détaillés | A/D/E/R/P/I/H/V/J |
| 46 | Autonomie des arbitrages techniques | README, tous contrats |
| 47 | Pas de modification gameplay silencieuse | T §1/2/14, statut des profils |
| 48 | Dix livrables A–J | README + vérificateur de présence |
| 49 | Socle puis six à huit workstreams | J §1–3, ownership précis |
| 50 | Familles et exemples des dix domaines | E §7/8, neuf événements + deux chaînes |
| 51 | DEFAULT_FOR_TESTING centralisé | profil unique, références vérifiées |
| 52 | Revue croisée sur neuf axes | tableau ci-dessous et rapport statique |
| 53 | Contrats prêts puis giga-ticket | ce dossier, J et ticket séparé préparé |
| 54 | Conception uniquement | aucun mod/serveur/WorldPrep touché ; V campagne future |
| 55 | Choix techniques pris, produit respecté | contrats normatifs et procédure de conflit |

## 3. Revue sur les axes demandés

| Axe | Verdict de conception | Preuve/limite |
| --- | --- | --- |
| Architecture | PASS_DESIGN_REVIEW | dépendances vers API et composition ; les ports évitent des imports cycliques ; futur contrôle d'import nécessaire |
| Gameplay | PASS_DESIGN_REVIEW | mission récente prioritaire ; transformations/faune/dangers réellement supportés ; aucune valeur test n'est une approbation |
| Quêtes | PASS_DESIGN_REVIEW | no-op possible, bus facultatif, REQUIRE durable sans bypass |
| IA | PASS_DESIGN_REVIEW | disabled adapter/fallback, sorties fermées et revalidation |
| Safety | PASS_DESIGN_REVIEW | unique admission, PREPARE avant effet, WARN, ownership/CAS, arrêt préautorisé |
| Persistance | PASS_DESIGN_REVIEW | sagas/rapprochement, pas d'atomicité Minecraft inventée, conflits localisés visibles |
| Performance | PASS_DESIGN_REVIEW | scopes actifs, caches bornés, débit/budgets, IO hors tick ; mesures réelles NOT_RUN |
| Parallel dev | PASS_DESIGN_REVIEW | G0 + huit lots avec les champs requis et ownership sans chevauchement |
| Testing | PASS_DESIGN_REVIEW | oracles unitaires/contrats/clients/physique/crash/soak ; campagne réelle NOT_RUN |

Ces verdicts sont une revue croisée des contrats par le rédacteur, pas une expertise indépendante ni une preuve formelle de correction. Les contrôles automatiques effectivement exécutés sont uniquement ceux du rapport statique.

## 4. Vérifications statiques de livraison

Le script livré `validation/validate_spec.py` est un outil de contrôle des artefacts, pas une implémentation du Director. Il vérifie JSON sans clés dupliquées, références locales de schémas, sous-ensemble de mots-clés JSON Schema utilisé, fixtures positives et négatives, résolution typée des paramètres, DAG/roles/WARN/budgets, graphes de chaînes, import de lieux/digest, liens Markdown, présence des dix documents et reproduction complète des exemples.

Le runtime disponible ne contient pas de validateur JSON Schema externe. Le contrôle utilise donc un évaluateur local explicite des mots-clés employés, avec rejet des mots-clés non pris en charge. Le rapport ne revendique pas une certification de conformité intégrale au standard 2020-12. La future gate J02 doit aussi utiliser le validateur/codec Java réel du projet.

Les nombres exacts de fichiers/exemples/cas contrôlés et les résultats sont enregistrés dans `validation/STATIC_VALIDATION_REPORT.json`. Les schémas et les examples sont vérifiés comme contrats de conception ; la présence d'un ID d'asset ou d'une capability ne prouve pas son existence dans le modpack actuel.

## 5. Limites explicites et passage à la suite

Pas de code Director écrit ; pas de build NeoForge ; pas de GameTest/client/soak ; pas d'appel IA ; pas de connexion à dam-server, Crafty, Sentinel ou WorldPrep ; pas de modification de génération. Les sources de plateforme sont les documentations primaires 1.21.1 citées dans ARCHITECTURE. Le dépôt existant et sa version patch exacte restent à vérifier au début de G0, sans choix d'architecture laissé ouvert.

Les limites de fréquence, dégâts, tailles de catastrophe, durées, caps d'entités et persistance doivent être calibrées avant mise en service. Cela ne bloque pas le développement des mécanismes ; cela interdit de présenter les fixtures comme un gameplay déjà validé.

Aucun `TECHNICAL_CONFLICT` bloquant n'a été identifié dans le périmètre demandé. La non-atomicité Minecraft est couverte comme limite d'exécution par rapprochement et isolation, sans promesse de restauration aveugle. Une capability impossible à qualifier sur le pack réel devra être signalée avec ce format ; elle ne sera pas remplacée discrètement par un autre effet.

La mission de conception est complète lorsque le rapport statique est PASS et que tous les livrables sont présents. Le giga-ticket préparé peut alors être transmis au prochain fil ; son exécution et sa livraison réelle restent une phase distincte.
