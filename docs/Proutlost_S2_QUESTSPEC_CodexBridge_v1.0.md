# PROUTLOST — SAISON 2
## Spécification développeur Quêtes, Enquête et Progression
### Optimisée ChatGPT ↔ Codex via CodexBridge

**Version :** 1.0  
**Date :** 26 septembre 2026  
**Statut :** spécification de référence pour développement — décisions fonctionnelles et narratives consolidées  
**Périmètre :** Quest Core, journal, interactions d’enquête, perceptions asymétriques, décisions collectives, contenu P0→F5, intégration avec le World Director  
**Hors périmètre :** WorldPrep/terraforming, implémentation complète du World Director, sélection finale de tous les mods, équilibrage de performance P2, assets finaux  
**Priorité des sources :** décisions explicites les plus récentes de Damien > présent document > CDC v2.1 > dossier scénario/quêtes v1.0 > QCM antérieurs compatibles.

---

# 0. But de cette spécification

Ce document a deux fonctions :

1. **Figer les décisions nécessaires au développement du système de quêtes et de la campagne Proutlost S2.**
2. **Servir de source de vérité stable pour les échanges ChatGPT ↔ Codex via CodexBridge**, afin qu’un ticket puisse référencer des identifiants précis sans réinterpréter la conversation.

Chaque exigence importante possède un identifiant stable.

Un ticket CodexBridge ne doit pas recopier toute la narration. Il doit référencer les identifiants de cette spécification qu’il implémente.

Exemple :

```text
SPEC_VERSION: QUESTSPEC-1.0
IMPLEMENTS: INV-001, INV-003, QCORE-001..QCORE-009, TEST-QCORE-001..008
```

Les décisions explicitement marquées **CALIBRATION** peuvent être ajustées sans changer l’architecture ni le canon.  
Les éléments marqués **ASSET** concernent le texte exact, le dessin, le son ou la mise en scène et peuvent évoluer sans modifier la mécanique décrite.

---

# 1. Invariants non négociables

## INV-001 — Le World Director reste le cœur du projet

Le système de quêtes n’est pas le moteur principal du serveur.

Le World Director peut observer le serveur, mémoriser les régions, choisir des événements, modifier légèrement un lieu ou produire une perception **sans qu’aucune quête soit active**.

Le Quest Core ne possède pas le World Director. Il peut seulement consommer certains faits, événements ou états lorsque ceux-ci deviennent pertinents pour la progression.

## INV-002 — Le monde existe indépendamment des quêtes

Un événement peut être purement ambiant, modifier légèrement un lieu, créer une mémoire régionale, produire une perception personnelle, déclencher une situation sociale ou produire une conséquence persistante sans apparaître dans le journal de quêtes.

Les joueurs ne doivent jamais apprendre la règle mentale :

> « Si le monde fait quelque chose d’étrange, c’est forcément une quête. »

Une manifestation ambiante peut ultérieurement ouvrir une piste narrative. Dans ce cas, la quête devient visible **après** que ses prérequis sont satisfaits.

## INV-003 — Réalité physique partagée

Tous les joueurs partagent les mêmes blocs, collisions, constructions, inventaires et vraies entités.

Les différences de perception utilisent notamment sons ciblés, symboles, particules, apparitions non physiques, textes, effets d’ambiance et petites interfaces.

Aucune quête ne doit nécessiter deux réalités physiques contradictoires.

## INV-004 — Alpa ne participe pas

Aucune progression, quête, validation, perception ou condition ne doit exiger la connexion d’Alpa, une action d’Alpa, une réponse en direct d’Alpa ou un personnage contrôlé par Alpa.

Sa représentation éventuelle en introduction ou épilogue est une mise en scène préparée, pas une participante.

## INV-005 — Le coma explique l’île

Le coma d’Alpa explique le monde surnaturel de la saison.

Ne pas introduire le Repli, une force surnaturelle extérieure indépendante, des consciences réellement transportées ailleurs ou une origine réellement préhumaine de l’île.

Le coma est un mécanisme de fiction, pas une description médicale.

## INV-006 — L’île veut maintenir son monde

L’île ne cherche pas principalement à tuer les personnages.

Son comportement profond tend à conserver les habitudes, préserver les lieux familiers, remettre certaines histoires en circulation, brouiller ou interrompre les départs et ramener l’attention vers l’intérieur du monde.

La progression finale consiste à permettre à ce monde d’accepter une conclusion. Le réveil d’Alpa est le dénouement.

## INV-007 — Valider une découverte, pas une théorie

Le système valide des faits observables : document consulté, perception relevée, comparaison effectuée, expérience réussie, réponse reproduite ou choix confirmé.

Il ne valide jamais « le joueur a compris », une théorie exprimée dans le chat ou une théorie exprimée dans le vocal.

Aucune analyse de chat/vocal n’est autorisée.

## INV-008 — Une quête accessible est visible avant résolution

États fonctionnels obligatoires :

1. `LOCKED`
2. `AVAILABLE`
3. `IN_PROGRESS`
4. `RESOLVED`

### LOCKED
Aucun titre révélateur, texte, branche future ou spoiler.

### AVAILABLE
Titre joueur évocateur + contexte court + action utile.

### IN_PROGRESS
Le texte peut s’enrichir uniquement avec les faits réellement obtenus.

### RESOLVED
Validation visible + bilan factuel conservé.

Le journal est évasif sur **la réponse**, pas sur **ce que le joueur peut essayer maintenant**.

## INV-009 — Pas d’échec définitif

Un indice essentiel, support perdu, joueur absent, déconnexion ou événement manqué ne doit pas condamner la campagne.

Les mécanismes de reprise peuvent reproduire autrement une manifestation, republier une connaissance établie, proposer une version de rattrapage ou déplacer un support dans une zone préparée.

Ils ne doivent jamais réécrire rétroactivement une preuve déjà enregistrée.

## INV-010 — Une minorité ne peut pas rusher seule la saison

Les grandes convergences utilisent plusieurs des éléments suivants : connaissances réparties, contributions de joueurs distincts, population active, temps de circulation des découvertes, décisions collectives et états de lieux.

La présence de tous les inscrits n’est pas obligatoire.

## INV-011 — FTB Quests n’est pas la source canonique

FTB Quests ou une interface équivalente sert d’interface visible.

Proutlost reste l’autorité pour les connaissances, observations, choix, gates, états de lieux, progression collective, validations et convergence finale.

La perte ou reconstruction de l’UI ne doit pas détruire l’état canonique.

---

# 2. Architecture fonctionnelle

## ARCH-001 — Séparation des responsabilités

```text
WORLD DIRECTOR
    |
    | produit des faits / événements / états
    v
WORLD MEMORY / EVENT BUS
    |
    +--------------------------+
    |                          |
    v                          v
QUEST CORE                SYSTÈMES AMBIANTS
    |                          |
    v                          | peuvent fonctionner
KNOWLEDGE / CHOICES            | sans quête
    |
    +--------------+----------------+----------------+
    |              |                |                |
    v              v                v                v
JOURNAL       INTERACTIONS     PERCEPTIONS       VOTES
    |
    v
FTB QUESTS / UI
```

Le Quest Core écoute le monde.  
Le monde ne doit pas dépendre du Quest Core pour exister.

## ARCH-002 — Familles d’événements

Chaque événement peut déclarer une catégorie fonctionnelle :

- `AMBIENT`
- `NARRATIVE`
- `HYBRID`

### AMBIENT
Aucun effet obligatoire sur la progression.

### NARRATIVE
Produit explicitement un ou plusieurs faits consommables par une quête.

### HYBRID
Peut exister naturellement hors quête et devenir pertinent si une condition narrative est active.

Exemple : mémoire d’une routine autour du feu.

---

# 3. Modèle de connaissance

## KNOW-001 — Trois niveaux obligatoires

### `PERSONAL_OBSERVATION`
Ce qu’un joueur a effectivement vu, entendu ou relevé.

### `SHARED_EVIDENCE`
Observation qu’un témoin a explicitement partagée avec des destinataires.

### `ESTABLISHED_KNOWLEDGE`
Fait reconnu par la progression collective.

Ces niveaux ne sont pas interchangeables.

## KNOW-002 — Documents et inscriptions

Ouvrir ou examiner un support admissible ajoute automatiquement son contenu au relevé personnel, enregistre l’origine du relevé et ne constitue pas une validation de théorie.

## KNOW-003 — Perceptions personnelles

Une perception ciblée n’est conservée sous forme exacte qu’après une action volontaire de collecte.

Exemple :

```text
record_perception(perception_id)
```

Si une perception essentielle n’a pas été enregistrée, un mécanisme de reprise doit rester possible.

## KNOW-004 — Partage

Une observation personnelle devient partageable quand son témoin utilise explicitement :

```text
share(observation_id, recipients)
```

Les destinataires sont choisis par le témoin.

Une convergence peut ensuite publier une connaissance collectivement établie.

## KNOW-005 — Rattrapage

Un absent ou nouvel arrivant reçoit :

1. une page « Nouveautés » contenant uniquement les **connaissances collectives établies** depuis sa dernière présence ;
2. l’accès aux preuves déjà devenues collectives ;
3. un **court parcours de rattrapage facultatif** utilisant des traces déjà connues.

Il n’obtient pas automatiquement les observations privées jamais partagées.

## KNOW-006 — Découverte anticipée

Une preuve obtenue avant l’ouverture de sa quête est conservée discrètement, non utilisée pour révéler une branche verrouillée et automatiquement prise en compte lorsque la quête devient accessible.

---

# 4. Quest Core

## QCORE-001 — Entité Quest

Chaque quête doit posséder au minimum :

```text
quest_id
author_title
player_title
state
prerequisites
visibility_scope
observations
resolution_conditions
resolved_summary
unlocks
recovery_policy
```

## QCORE-002 — Titres

`author_title` peut contenir un spoiler de conception.

`player_title` ne doit jamais annoncer une conclusion non encore établie.

## QCORE-003 — Déblocage

Une quête peut devenir disponible après résolution d’une autre quête, obtention d’un fait, observation d’un événement, état d’un lieu ou convergence de plusieurs branches.

Débloquer une enquête n’est jamais équivalent à la résoudre.

## QCORE-004 — Condition d’activité utile

Une quête ne doit être `AVAILABLE` ou `IN_PROGRESS` que si le joueur dispose d’une action possible.

Interdit :

> « Cherche quelque chose quelque part » alors que le déclencheur nécessaire n’a pas encore eu lieu.

## QCORE-005 — Validation automatique

La validation normale est déclenchée par les faits enregistrés.

Pas de validation normale par modo.

Les commandes de secours sont réservées aux bugs/tests.

## QCORE-006 — Reconnexion

Après reconnexion ou redémarrage :

- reconstruire l’affichage depuis l’état canonique ;
- ne pas perdre les observations ;
- ne pas perdre les validations ;
- ne pas révéler les branches futures ;
- reprendre les expériences incomplètes lorsque possible.

## QCORE-007 — Progression collective

Une quête peut être personnelle, groupe ou serveur.

Une quête serveur peut être alimentée par des joueurs différents.

Un joueur n’a pas à refaire tout le contenu déjà établi collectivement.

## QCORE-008 — Anti-rush

Pour les convergences majeures, le moteur doit pouvoir exprimer :

```text
min_distinct_contributors
active_population_requirement
knowledge_requirements
elapsed_circulation_time
world_state_requirements
choice_requirements
```

Les valeurs exactes sont configurables.

## QCORE-009 — Population active

Population de référence :

> joueurs ayant réellement joué au cours des 7 derniers jours.

Une adaptation encadrée est possible si l’effectif baisse.

Les ratios exacts sont **CALIBRATION**.

---

# 5. Interactions réutilisables

## INT-001 — `examine`

Usage : journal, inscription, document, support lisible.

Résultat : `PERSONAL_OBSERVATION`.

## INT-002 — `record_perception`

Action volontaire permettant de conserver exactement une perception personnelle.

## INT-003 — `compare`

Deux étapes :

1. préparer le rapprochement dans le carnet ;
2. l’éprouver par une interaction sur le lieu concerné lorsque la quête le demande.

## INT-004 — `sequence_input`

Permet d’activer des supports, les orienter, composer une séquence ou reproduire un rythme/ordre.

Les contenus exacts sont data-driven.

## INT-005 — Mauvaise tentative

Résultat obligatoire :

`PARTIAL_RESPONSE`

Le système peut produire un élément de réaction, mais ne révèle pas directement quelle étape est incorrecte, la bonne combinaison ou la solution complète.

Aucune perte d’indice essentiel.

## INT-006 — `arrangement`

Support composé de 3 à 5 emplacements définis.

Les joueurs y placent des objets ordinaires admissibles.

Action :

```text
record_arrangement()
```

Proutlost enregistre identités des objets, emplacements, ordre canonique, lieu, auteur(s) et date logique.

## INT-007 — `camp_claim`

Validation hybride.

1. Proutlost détecte qu’une zone satisfait les critères minimaux d’un camp.
2. Un joueur demande explicitement `claim_camp`.
3. Le serveur revérifie les critères.
4. Le camp devient persistant uniquement si les critères sont toujours satisfaits.

Le premier camp reconnu valide P1.

Plusieurs camps restent possibles ensuite.

Les critères précis sont **CALIBRATION**, mais doivent utiliser des signaux robustes comme activité réelle, fréquentation, couchage, feu/lumière, stockage/nourriture.

Une simple construction décorative ne doit pas être reconnue automatiquement.

## INT-008 — `distress_signal`

Validation hybride.

Les joueurs fabriquent un signal réaliste : SOS au sol, feu/fumée, signal contrasté ou combinaison admissible.

Un joueur confirme :

```text
activate_distress_signal
```

Le serveur vérifie des critères de lisibilité : emplacement, dégagement, taille, visibilité/contraste et proximité raisonnable de côte ou point haut selon le type.

Aucune recette unique obligatoire.

---

# 6. Perceptions asymétriques

## PER-001 — Attribution

La perception dépend du contexte de la scène.

Aucun joueur ne possède un rôle perceptif permanent.

Le système doit assurer rotation, équité, diversité des rôles et indépendance vis-à-vis du streaming.

## PER-002 — Redistribution

Une expérience peut exiger que les joueurs changent physiquement de position.

Lors d’une répétition contrôlée, les perceptions sont redistribuées.

Le système doit permettre de démontrer :

> la position seule n’explique pas ce qui est perçu.

## PER-003 — Reprise

Si tous les détenteurs d’un fragment indispensable deviennent durablement indisponibles, une reprise contrôlée peut redonner ce fragment à un autre joueur.

Elle ne doit pas modifier les preuves antérieures.

---

# 7. Décisions collectives

## VOTE-001 — Éligibilité

Un joueur est éligible si :

1. il appartient à la population active ;
2. il a consulté le dossier partagé du choix.

La consultation doit rester accessible à tous les actifs.

## VOTE-002 — Interface

Vote contextualisé dans le carnet.

Avant confirmation : options, conséquences connues, informations déjà établies et avertissement que certaines conséquences restent inconnues.

Chaque joueur confirme explicitement son choix.

## VOTE-003 — Session collective

Le vote se déroule pendant une session collective après une fenêtre de discussion.

Les absents retrouvent ensuite le résultat.

La durée exacte de la fenêtre est **CALIBRATION**.

## VOTE-004 — Majorité

Une option est retenue normalement si :

> plus de 50 % des joueurs éligibles la soutiennent.

Sinon :

> nouvelle consultation sur population active actualisée.

## VOTE-005 — Participation minimale

Un tirage ne doit jamais remplacer une participation insuffisante.

Le seuil exact de participation est **CALIBRATION** et doit être configurable.

## VOTE-006 — Égalité

Si le seuil de participation est atteint mais que les options restent à égalité :

1. second tour limité ;
2. si l’égalité persiste, tirage impartial entre les options encore à égalité.

Le tirage doit être annoncé à l’avance, auditable et indépendant d’un modo.

---

# 8. Langage de l’île

## LANG-001 — Type

Pas d’alphabet complet.

Vocabulaire composé de **7 concepts combinables** :

1. `ICI`
2. `AILLEURS`
3. `VOIR`
4. `ENTENDRE`
5. `LIER`
6. `RÉPÉTER`
7. `PARTIR`

## LANG-002 — Règle de cohérence

Un signe conserve toujours le même concept.

Interdit de modifier son sens ultérieurement uniquement parce que les joueurs ont découvert la logique trop vite.

## LANG-003 — Combinaisons

Exemples admissibles :

- VOIR + AILLEURS
- ENTENDRE + ICI
- LIER + VOIR + ENTENDRE
- RÉPÉTER + ICI
- PARTIR + AILLEURS

Les dessins graphiques exacts sont **ASSET**.

---

# 9. Contenu canonique — P0 à J1

# P0 — L’ARRIVÉE

## P0-001 — Situation

Tous les joueurs commencent réunis au même endroit près de l’épave.

Pas de dispersion initiale.

Pas d’Alpa visible.

Le crash est une transition symbolique. Sa cause extérieure réelle n’est pas une énigme à résoudre.

## P0-002 — Objectif joueur

Quitter les abords dangereux de l’épave et rejoindre une zone sûre du rivage.

Pas de condition « attendre tous les inscrits ».

## P0-003 — Premier motif

L’épave contient un petit dessin/gribouillage banal possédant un détail inhabituel très reconnaissable.

Identifiant narratif : `MOTIF_01`.

**ASSET :** forme graphique exacte encore modifiable.  
Référence de travail : fleur/gribouillage avec un pétale carré.

Le motif n’est pas obligatoire pour terminer P0.

# P1 — PRENDRE PIED

## P1-001

Objectif : établir un camp fonctionnel.

Validation : `camp_claim` hybride.

Résultat : création d’un camp persistant dans la mémoire du monde.

Le premier camp reconnu valide P1.

# P2 — CHERCHER DES SECOURS

## P2-001

Construire un signal de détresse réaliste.

Validation : `distress_signal`.

## P2-002 — Réponse

Après validation, une réponse apparaît **depuis l’intérieur de l’île** : feu, fumée ou lueur brève.

Pas de réponse située hors de l’île.

La manifestation fournit une direction approximative, pas un marqueur GPS.

Effet narratif recherché :

> Les joueurs tentent de joindre l’extérieur ; l’île transforme cette tentative en raison de s’enfoncer davantage en elle.

# P3 — UN DÉTAIL DE TROP

## P3-001

Les joueurs atteignent un ancien petit campement.

Éléments possibles : ancien foyer, couchage, restes d’abri, objets/contenants et traces humaines.

## P3-002

Le camp contient `MOTIF_01`.

Les joueurs comparent le relevé de l’épave et le relevé du camp ancien.

Validation : correspondance exacte du détail inhabituel.

Conclusion établie :

> le même motif existe dans deux contextes qui ne devraient pas être liés.

P3 ouvre A, B et C en parallèle.

# A — LES NOMS

# A1 — UNE SIGNATURE

## A1-001

Dans l’ancien camp de P3, les joueurs trouvent un **journal déchiré**.

Auteur : **Camille Derval**.

## A1-002

Plusieurs fragments sont ordinaires, racontent la vie quotidienne, contiennent des éléments cohérents et portent la signature complète Camille Derval.

A1 doit d’abord rendre cette personne crédible.

Pas d’anomalie majeure obligatoire avant identification.

Validation : les fragments nécessaires sont relevés et l’auteur est identifié.

# A2 — LE MÊME NOM, AILLEURS

## A2-001

Un autre témoignage présente une autre **Camille Derval**.

Les deux Camille appartiennent à des **époques apparentes très différentes**.

Leurs biographies comportent au moins deux incompatibilités factuelles observables.

Le système valide les contradictions, pas leur interprétation.

# A3 — DES HISTOIRES MÉLANGÉES

## A3-001

Les récits recombinent expressions, habitudes, lieux et objets.

Des éléments associés à Camille ou à une histoire réapparaissent chez d’autres figures.

## A3-002

Validation : au moins deux recombinaisons confirmées entre plusieurs récits.

Conclusion établie :

> Les mêmes éléments apparaissent dans plusieurs histoires sans rester attachés aux mêmes personnes ni aux mêmes lieux.

# B — LES LIEUX

# B1 — QUELQU’UN EST REVENU

## B1-001

Le World Director utilise une **capacité générale du monde**, non spécifique aux quêtes :

> mémoire de routines simples autour d’un feu de camp.

Signaux admissibles : fréquentation, regroupement, positions relatives habituelles et répétition temporelle raisonnable.

Ne pas enregistrer des trajectoires complètes de joueurs.

## B1-002

Lorsque le contexte le permet, le lieu reproduit légèrement la routine : pas, placement d’une silhouette non physique, lumière, ambiance sonore ou combinaison légère.

B1 consomme cet événement comme preuve narrative.

La capacité de mémoire doit rester utilisable hors quête conformément à INV-001/002.

# B2 — LAISSER UNE MARQUE

## B2-001

Après B1, les joueurs réalisent une expérience volontaire via `arrangement`.

Ils créent une disposition identifiable avec des objets ordinaires.

Le relevé exact est enregistré.

# B3 — C’ÉTAIT NOTRE GESTE

## B3-001

La disposition de B2 réapparaît dans **un autre ancien campement ou abri**.

Elle est présentée comme une trace ancienne.

La correspondance exacte doit permettre d’écarter une simple coïncidence.

Conclusion établie :

> une action récente des joueurs a été intégrée au passé apparent de l’île.

Ne pas conclure « voyage temporel » automatiquement.

# C — LES PERCEPTIONS

# C1 — TU AS ENTENDU ÇA ?

## C1-001

Même lieu, même scène.

Une partie des joueurs reçoit une séquence de 3 sons.

Une autre partie reçoit 3 symboles.

Personne ne reçoit obligatoirement l’ensemble.

## C1-002

Chaque perception doit être relevée volontairement.

Validation :

- au moins un relevé sonore ;
- au moins un relevé visuel ;
- comparaison des deux familles de relevés.

Conclusion :

> plusieurs témoignages différents concernent pourtant la même scène.

# C2 — ÉCHANGER LES PLACES

## C2-001

Les joueurs doivent réellement changer de position.

La scène est relancée.

Les perceptions sont redistribuées équitablement.

Validation :

- deux essais ;
- positions modifiées ;
- distribution perceptive différente ;
- comparaison.

Conclusion :

> la position seule ne détermine pas la perception.

# C3 — RÉPONDRE ENSEMBLE

## C3-001

Les symboles indiquent **quels supports** utiliser.

Les sons indiquent **dans quel ordre** les activer.

Aucun fragment seul ne suffit.

## C3-002

Validation :

1. fragments partagés ;
2. séquence correcte reproduite ;
3. réaction commune du lieu.

Une mauvaise tentative utilise INT-005.

Conclusion :

> des perceptions complémentaires permettent une action correcte qu’aucun individu ne pouvait déduire seul.

# J1 — LES PIÈCES SE RÉPONDENT

## J1-001

J1 utilise uniquement le carnet.

Pas de nouveau lieu ou puzzle physique.

Prérequis : A3 + B3 + C3.

## J1-002

Page de synthèse :

- récits incompatibles ;
- action récente devenue trace ancienne ;
- perceptions différentes produisant une réponse commune.

Les joueurs rapprochent les trois constats.

Conclusion :

> Ces phénomènes ne semblent pas indépendants. Des noms, gestes, traces et perceptions se répondent selon une logique commune.

J1 ouvre D et E.

---

# 10. Contenu canonique — D/E/J2

# D1 — ENCORE LA MÊME SCÈNE

## D1-001

Scène quotidienne légère, mix visuel + sonore.

Première occurrence : étrange mais ambiguë.

Deuxième : répétition reconnaissable.

Occurrence suivante : incorpore un détail récent réellement vécu par les joueurs.

## D1-002

Validation :

- deux occurrences comparables ;
- élément stable identifié ;
- détail récent intégré identifié.

Conclusion :

> le monde ne conserve pas seulement des traces ; il remet certaines situations en circulation.

# D2 — LE DÉPART INTERROMPU

## D2-001

Mix documents + traces physiques.

Plusieurs personnes du passé ont préparé un départ.

Exemples de traces : corde, matériel, petit embarcadère, sacs, balises et bivouac orienté vers une sortie.

## D2-002

Les récits s’interrompent juste avant la conclusion ou reviennent vers un état familier.

Validation :

- au moins deux récits de départ ;
- au moins une trace physique correspondante ;
- pattern d’interruption établi.

Pas de boucle temporelle explicitement validée.

# D3 — PASSER AUTREMENT

## D3-001 — Deux options obligatoirement explorées

Avant ouverture du vote, les joueurs doivent explorer :

1. réparer le passage existant ;
2. ouvrir un détour.

Le carnet enregistre les conséquences connues des deux options.

## D3-002 — Réparer

Actions : identifier 2–3 points préparés, petit apport matériel et intervention collective.

Conséquences : ancien passage conservé, traces historiques plus lisibles et manifestations futures davantage liées au passé.

## D3-003 — Ouvrir un détour

Actions : explorer zone voisine, identifier itinéraire et aménager 2–3 points.

Conséquences : secteur davantage transformé, nouvelle exploration et nouvelles manifestations possibles.

## D3-004

Une fois les deux options explorées :

- vote selon VOTE-* ;
- seule l’option gagnante devient objectif collectif actif.

Aucune option ne bloque J2 ou la fin.

Apprentissage :

> faire évoluer un lieu n’efface pas nécessairement son histoire.

# E1 — UNE VOIX SANS LIEU

## E1-001

Le signal commence comme une séquence sonore abstraite.

Il apparaît dans plusieurs régions.

Aucune source physique locale n’est identifiable.

## E1-002

Au fil des manifestations, certaines composantes deviennent presque vocales.

Ne pas fournir immédiatement une phrase intelligible.

Validation :

- plusieurs régions ;
- structure sonore commune ;
- absence de source locale établie.

# E2 — LE MOT QUI MANQUE

## E2-001

Deux versions conservées d’un même fragment sont comparées.

L’ancien relevé ne doit jamais être réécrit.

Un élément a disparu ou été masqué.

## E2-002

Le concept manquant concerne `PARTIR` ou sa formulation contextuelle `SORTIR`.

Ne pas utiliser encore coma, réveil ou hôpital.

Conclusion :

> quelque chose semble masquer les informations qui conduisent vers le départ.

# E3 — FAIRE PASSER L’APPEL

## E3-001 — FILTRER

- plusieurs activations ;
- fragments courts ;
- meilleure lisibilité ;
- stabilité élevée.

## E3-002 — AMPLIFIER

- réponse plus longue ;
- davantage de parasites ;
- risque de R1.

## E3-003

Les deux options donnent accès à la même vérité narrative.

Aucune n’est « la bonne ».

# R1 — APRÈS LA SECOUSSE

## R1-001

Seulement si l’amplification provoque une perturbation.

Effets possibles : baisse brutale de visibilité, vent, pluie, pression sonore et dispositif perturbé.

## R1-002

Les joueurs rejoignent un point de repli déjà identifié.

Après stabilisation : retour sur le site, remise en état et reprise de transmission.

Pas de destruction arbitraire de constructions de joueurs.

Le site conserve une cicatrice légère.

# J2 — CE QUI NOUS GARDE

## J2-001

Synthèse uniquement dans le carnet.

Prérequis : D3 achevé + transmission E achevée.

## J2-002

Met en relation :

- départs systématiquement interrompus ;
- capacité à faire évoluer un passage ;
- signal masquant PARTIR/SORTIR.

Conclusion joueur :

> L’île ne cherche pas principalement à nous détruire. Elle cherche à ce que notre histoire continue ici.

Nouvel objectif : identifier le destinataire réel du signal et permettre une conclusion sans effacer ce qui a existé.

---

# 11. Finale F1→F5

# F1 — LE MÊME DESTINATAIRE

## F1-001

Pas de nouvelle chasse aux objets.

F1 réutilise Camille Derval, expressions récurrentes, motifs, habitudes, signal et récits recombinés.

## F1-002

Ces éléments convergent vers un nom absent de l’histoire de l’île :

**Alpa**

Le nom `Alpa` ne doit pas apparaître explicitement dans l’interface narrative avant cette convergence.

Conclusion :

> de nombreux fragments semblent revenir à une même personne absente.

F1 ouvre le dernier temps d’enquête.

# F2 — DE L’AUTRE CÔTÉ

## F2-001

Les joueurs utilisent la méthode de transmission maîtrisée.

Pour la première fois, le signal devient intelligible.

## F2-002 — Mise en scène extérieure

L’extérieur doit être banal.

Sons possibles : voix étouffées, porte, mouvement dans une pièce et objets déplacés.

Pas de séquence cliché obligatoire de moniteur médical.

## F2-003 — Phrase pivot

Ligne canonique de travail :

> « Alpa… si tu m’entends, on est là. »

Des fragments complémentaires établissent ensuite qu’Alpa est dans le coma.

La cause précise du coma reste hors du mystère à résoudre.

## F2-004 — Révélation

Les joueurs peuvent désormais établir :

> L’archipel et ses habitants appartiennent au monde intérieur associatif d’Alpa.

Les incohérences précédentes doivent devenir relisibles à partir des preuves déjà obtenues.

# F3 — TOUT NE DOIT PAS DISPARAÎTRE

## F3-001

Vote entre :

### RÉUNIR LES TÉMOIGNAGES
Créer une mémoire commune plus centralisée.

### RÉPARTIR LES TÉMOIGNAGES
Conserver plusieurs mémoires locales et points de vue.

## F3-002

Aucune option ne bloque le réveil, n’est moralement supérieure ou ne modifie la vérité du coma.

Elle modifie l’organisation du souvenir, la présentation finale de l’archipel et l’épilogue.

# F4 — LAISSER PARTIR

## F4-001

Pas de nouvelle mécanique principale.

La conclusion réutilise trois apprentissages de saison :

1. relier correctement des fragments ;
2. laisser évoluer un lieu sans effacer son histoire ;
3. transmettre une réponse complète.

## F4-002

Les contributions peuvent être réalisées dans plusieurs lieux, par plusieurs groupes et sur plusieurs connexions.

Elles restent persistantes jusqu’à convergence.

Pas de présence simultanée de tous les joueurs.

## F4-003

À convergence :

- les répétitions cessent ;
- les passages restent ouverts ;
- le signal n’est plus brouillé ;
- les lieux cessent progressivement d’être ramenés à leur état familier.

Interprétation :

> l’île cesse de retenir son monde.

# F5 — LE MATIN D’APRÈS

## F5-001

Alpa se réveille.

Le réveil est certain dans toutes les variantes.

## F5-002

Dernier regard sur l’archipel.

Doit réutiliser l’histoire réellement enregistrée :

- passage réparé ou détour ;
- cicatrice éventuelle R1 ;
- témoignages réunis ou répartis ;
- camps ;
- lieux transformés ;
- traces pertinentes des joueurs.

## F5-003

Le devenir des personnages reste volontairement ouvert.

Interdit :

- destruction générale obligatoire ;
- sacrifice obligatoire ;
- confirmation qu’ils existent dans un autre monde réel ;
- réveil collectif.

---

# 12. Lieux et mapping

## MAP-001

Aucune « Clairière » centrale n’est imposée.

Les branches A/B/C utilisent un **petit réseau de lieux réutilisés**.

Un même lieu peut changer de signification après de nouvelles découvertes.

## MAP-002

Chaque lieu narratif devra ultérieurement déclarer :

```text
location_id
world_region
anchors
interaction_surfaces
allowed_transformations
protected_zones
possible_states
recovery_support
```

Les coordonnées exactes ne sont pas requises pour développer le Quest Core.

---

# 13. World Director ↔ Quest Core

## WDQ-001 — Contrat de fait

Le Director ou un système monde peut publier un fait standardisé :

```text
fact_id
fact_type
source_system
player_scope
group_scope
region_id
location_id
timestamp
payload
persistence
narrative_visibility
```

Le Quest Core décide si ce fait satisfait une condition narrative.

## WDQ-002 — Le Director ne lit pas l’arbre pour vivre

Le Director peut sélectionner un événement AMBIENT sans demander au Quest Core « quelle quête est active ? ».

Une quête peut toutefois inscrire une **opportunité** ou une **contrainte de contexte** pour un événement narratif.

## WDQ-003 — Exemple B1

Capacité monde générale :

```text
camp_routine_memory
```

Le Director peut l’utiliser hors quête.

Lorsque B1 est active, une manifestation issue de cette capacité peut également produire :

```text
fact_type = CAMP_ROUTINE_REPLAY_OBSERVED
```

Ce fait nourrit B1.

La capacité n’appartient pas à B1.

---

# 14. Data-driven

## DATA-001

Le contenu de quête doit être défini autant que raisonnablement possible hors Java :

- IDs ;
- textes ;
- prérequis simples ;
- conditions ;
- observations ;
- bilans ;
- liens ;
- supports ;
- séquences ;
- variantes.

## DATA-002

Java conserve :

- persistance ;
- validation ;
- sécurité ;
- transitions ;
- réseau ;
- partage ;
- votes ;
- logique complexe ;
- reprise ;
- permissions ;
- intégrité des états.

---

# 15. Tests d’acceptation

## TEST-QCORE-001
Une quête verrouillée n’est pas visible.

## TEST-QCORE-002
Une quête accessible est visible avant résolution avec une action utilisable.

## TEST-QCORE-003
Une observation partielle enrichit le texte sans résoudre la quête.

## TEST-QCORE-004
Une découverte anticipée est conservée mais ne révèle pas la branche future.

## TEST-QCORE-005
Une résolution observable valide automatiquement.

## TEST-QCORE-006
Reconnexion/redémarrage reconstruit l’UI sans perdre l’état.

## TEST-QCORE-007
Un joueur absent peut rejoindre la progression sans refaire toutes les quêtes.

## TEST-QCORE-008
Une minorité ne peut pas franchir seule une convergence majeure.

## TEST-KNOW-001
Une perception privée n’apparaît pas dans le journal d’un autre joueur avant partage.

## TEST-KNOW-002
Le témoin choisit ses destinataires.

## TEST-KNOW-003
Une connaissance collectivement établie devient disponible aux absents lors de leur retour.

## TEST-KNOW-004
Une observation privée jamais partagée ne devient pas automatiquement collective.

## TEST-VOTE-001
Seuls actif + dossier consulté sont éligibles.

## TEST-VOTE-002
Un joueur ne peut voter deux fois dans le même tour.

## TEST-VOTE-003
>50 % des éligibles valide normalement une option.

## TEST-VOTE-004
Participation insuffisante ne déclenche jamais de tirage.

## TEST-VOTE-005
Égalité + second tour + égalité persistante déclenche un tirage impartial.

## TEST-VOTE-006
Déconnexion/reconnexion conserve correctement le vote.

## TEST-WDQ-001
Un événement AMBIENT fonctionne sans aucune quête active.

## TEST-WDQ-002
Le même moteur de mémoire de camp peut alimenter B1 lorsqu’elle est active.

## TEST-WDQ-003
Le Quest Core ne doit pas être requis pour exécuter une réaction environnementale sans rapport avec la storyline.

## TEST-CAMP-001
Un lieu satisfaisant les critères n’est pas automatiquement déclaré camp sans confirmation.

## TEST-CAMP-002
Une confirmation échoue si les critères ne sont plus satisfaits.

## TEST-CAMP-003
Le premier camp reconnu valide P1.

## TEST-EARLY-001
P0 → P1/P2 en parallèle → P3.

## TEST-EARLY-002
P3 ouvre A/B/C.

## TEST-EARLY-003
A3, B3 et C3 peuvent être accomplis par des groupes différents.

## TEST-EARLY-004
J1 ne s’ouvre qu’après A3+B3+C3.

## TEST-C-001
C1 distribue son vs symboles.

## TEST-C-002
Après changement réel de position, C2 redistribue les perceptions.

## TEST-C-003
C3 refuse une tentative incorrecte sans révéler la solution.

## TEST-C-004
C3 réussit uniquement avec la combinaison correcte.

## TEST-MID-001
D3 n’ouvre pas le vote avant exploration des deux options.

## TEST-MID-002
Réparer et détour donnent tous deux accès à J2.

## TEST-MID-003
E2 conserve les deux versions du message.

## TEST-MID-004
E3 filtrer/amplifier donne accès à la même vérité.

## TEST-MID-005
R1 n’est requise que lorsqu’une perturbation existe.

## TEST-FINAL-001
Alpa n’est jamais nécessaire comme joueuse connectée.

## TEST-FINAL-002
Le nom Alpa n’apparaît pas dans l’UI narrative avant F1.

## TEST-FINAL-003
F3 ne bloque jamais le réveil.

## TEST-FINAL-004
F4 accepte des contributions distribuées dans le temps et l’espace.

## TEST-FINAL-005
F5 reflète les choix réellement enregistrés.

---

# 16. Protocole CodexBridge

Cette section est normative pour les tickets créés depuis ChatGPT.

## CB-001 — Identité de ticket

Chaque job doit inclure :

```text
SPEC_VERSION
SPEC_IDS
BASE_COMMIT
SOURCE_WORKTREE
TARGET_WORKTREE
DEPENDENCIES
FILE_OWNERSHIP
NON_GOALS
ACCEPTANCE_CRITERIA
VALIDATION_COMMANDS
```

## CB-002 — Pas de recouvrement d’écriture

Deux jobs parallèles ne doivent pas posséder les mêmes fichiers sauf si le second est explicitement un reviewer read-only ou si un job d’intégration est créé après leur fin.

Ne pas faire modifier en parallèle le même Quest Core à plusieurs Codex.

## CB-003 — Sortie obligatoire

Chaque job doit retourner exactement quatre sections factuelles :

```text
IMPLEMENTE_EN_SOURCE
COMPILE
TESTE
BLOQUE
```

### IMPLEMENTE_EN_SOURCE
Liste des SPEC_IDS réellement implémentés + fichiers modifiés.

### COMPILE
Commande exacte + code de sortie.

### TESTE
Tests exacts + résultats réels.

### BLOQUE
Blocage précis ou `AUCUN`.

Interdit :

- PASS sans exécution ;
- « devrait fonctionner » présenté comme résultat ;
- documentation utilisée comme preuve d’implémentation.

## CB-004 — Handoff

Le résultat doit également fournir :

```text
HANDOFF:
- public_interfaces_changed
- migrations_required
- integration_dependencies
- next_smallest_blocker
```

## CB-005 — Reprise

Un ticket repris doit conserver même thread Codex lorsque possible, même worktree, modifications existantes et historique de tests.

Pas de reset/commit/merge/push sauf instruction explicite.

---

# 17. Découpage des tickets CodexBridge

## TICKET QCORE — Quest Core

**Dépendances :** aucune sur les autres tickets de quête.

Implémente :

- INV-007..011
- KNOW-001..006
- QCORE-001..009
- WDQ-001
- DATA-001/002

Ownership recommandé :

```text
quest/core/**
quest/state/**
quest/knowledge/**
quest/persistence/**
```

Ne pas implémenter le contenu P0→F5.

## TICKET QJOURNAL — Journal / FTB

**Dépend :** QCORE interfaces stabilisées.

Implémente :

- INV-008
- INV-011
- KNOW-001..006 côté UI
- J1-001/002 interface
- J2-001/002 interface

Ownership :

```text
quest/ui/**
compat/ftbquests/**
```

## TICKET QINTERACT — Interactions

**Dépend :** QCORE facts API.

Implémente :

- INT-001..008
- P1 validation primitive
- P2 validation primitive
- B2 arrangement primitive
- C3 sequence primitive

Ownership :

```text
quest/interaction/**
```

## TICKET QPERCEPT — Perceptions

Implémente :

- INV-003
- PER-001..003
- C1 primitives
- C2 primitives

Ownership :

```text
quest/perception/**
network/perception/**
client/perception/**
```

Ne pas modifier le World Director général.

## TICKET QVOTE — Choix collectifs

Implémente :

- VOTE-001..006
- population active QCORE-009 côté vote.

Ownership :

```text
quest/choice/**
```

## TICKET QCONTENT-EARLY

Implémente uniquement les données/contenus :

```text
P0
P1
P2
P3
A1 A2 A3
B1 B2 B3
C1 C2 C3
J1
```

Ne doit pas ajouter de nouveau moteur Java si une primitive prévue manque : retourner le blocker vers QINTERACT/QPERCEPT/QCORE.

## TICKET QCONTENT-MID

Implémente :

```text
D1 D2 D3
E1 E2 E3
R1
J2
LANG-*
```

## TICKET QCONTENT-FINAL

Implémente :

```text
F1 F2 F3 F4 F5
```

## TICKET QE2E

Ownership limitée aux tests.

Implémente la matrice TEST-*.

Doit notamment prouver :

- progression dans plusieurs ordres ;
- plusieurs groupes ;
- absences ;
- reprise ;
- redémarrage ;
- votes ;
- F1→F5 ;
- aucune dépendance à Alpa ;
- événements AMBIENT hors quête.

---

# 18. Ordre de développement CodexBridge

## Phase Q0 — obligatoire, non parallèle

```text
QCORE
```

Objectif : figer les interfaces et formats de faits.

## Phase Q1 — parallèle

```text
QJOURNAL
QINTERACT
QPERCEPT
QVOTE
```

Chaque job possède des fichiers distincts.

## Phase Q2 — parallèle après interfaces Q1

```text
QCONTENT-EARLY
QCONTENT-MID
QCONTENT-FINAL
QE2E
```

QE2E peut écrire les tests avant disponibilité de tous les composants, mais ne peut déclarer PASS qu’après intégration.

## Phase Q3 — intégration

Un seul job d’intégration :

```text
QINTEGRATE
```

Responsabilités :

- intégrer les branches validées ;
- résoudre les conflits d’interface ;
- exécuter toute la suite de tests ;
- produire le premier build testable.

---

# 19. Relation avec WorldPrep / CB-19

WorldPrep est un chantier indépendant.

Ne pas ajouter les tickets de quête dans le worktree CB-19.

Les interactions entre les deux doivent passer plus tard par des contrats explicites :

- `location_id`
- `anchor_id`
- `protected_zone`
- `world_state`
- `transformation_request`

Aucun ticket de quête ne doit interrompre ou détourner la livraison WorldPrep en cours.

---

# 20. Calibration restante — non bloquante pour l’architecture

Les points suivants restent à mesurer en test et ne justifient pas un nouveau QCM général :

- seuil minimal de participation d’un vote ;
- durée des fenêtres de vote ;
- délais de stagnation avant aide du Director ;
- critères numériques exacts de reconnaissance d’un camp ;
- durée avant qu’un témoin absent soit considéré comme durablement indisponible ;
- temps de circulation minimal avant J1/J2/finale ;
- intensité exacte des perturbations R1 ;
- nombre exact de contributions distinctes aux grandes convergences.

Ils doivent être configurables et testés.

---

# 21. Assets restant à produire

Ces éléments n’empêchent pas le développement du moteur :

- dessin final `MOTIF_01` ;
- fragments exacts du journal de Camille Derval ;
- deuxième biographie de Camille Derval ;
- autres figures du passé ;
- dessin graphique des 7 concepts ;
- sons C1 ;
- signal E1/E2 ;
- voix finale F2 ;
- textes définitifs de chaque état du journal ;
- apparence précise des sites ;
- staging final F5.

Les assets doivent respecter la présente structure et ne pas modifier les faits déjà validés.

---

# 22. Définition de “prêt pour développement”

Le système de quêtes est suffisamment spécifié pour commencer le développement lorsque :

- les invariants INV-* sont acceptés ;
- QCORE peut être implémenté sans inventer une règle narrative ;
- les primitives INT/PER/VOTE ont un contrat clair ;
- P0→F5 possède ses dépendances et conditions essentielles ;
- les éléments non définis sont exclusivement des assets ou de la calibration.

**Cette condition est satisfaite par QUESTSPEC-1.0.**

---

# 23. Règle de modification future

Toute modification importante doit préciser :

```text
CHANGE_ID
AFFECTED_SPEC_IDS
REASON
BACKWARD_COMPATIBILITY
MIGRATION
TESTS_TO_UPDATE
```

Une conversation ne doit pas silencieusement remplacer une règle de cette spécification.

Une décision explicite plus récente de Damien peut la remplacer ; elle doit ensuite être reportée dans une révision `QUESTSPEC-x.y`.

---

# 24. Résumé machine pour CodexBridge

```yaml
spec: QUESTSPEC-1.0
canonical_engine:
  world_director: independent
  quest_core: consumer_of_world_facts
  ftb_quests: ui_only

quest_states:
  - LOCKED
  - AVAILABLE
  - IN_PROGRESS
  - RESOLVED

knowledge:
  - PERSONAL_OBSERVATION
  - SHARED_EVIDENCE
  - ESTABLISHED_KNOWLEDGE

chat_voice_analysis: forbidden
alpa_required_as_player: false
permanent_failure: false

active_population:
  window_days: 7
  exact_thresholds: calibration

vote:
  eligibility:
    - active
    - shared_dossier_viewed
  normal_rule: absolute_majority_of_eligible
  tie:
    second_round: true
    impartial_draw_after_persistent_tie: true
    draw_requires_participation_threshold: true

language:
  concepts:
    - ICI
    - AILLEURS
    - VOIR
    - ENTENDRE
    - LIER
    - REPETER
    - PARTIR

first_development_path:
  - P0
  - P1
  - P2
  - P3

branch_graph:
  P0: [P1, P2]
  P1+P2: [P3]
  P3: [A1, B1, C1]
  A1: [A2]
  A2: [A3]
  B1: [B2]
  B2: [B3]
  C1: [C2]
  C2: [C3]
  A3+B3+C3: [J1]
  J1: [D1, E1]
  D1: [D2]
  D2: [D3]
  E1: [E2]
  E2: [E3]
  E3_amplify: [R1_optional]
  D3+E_complete: [J2]
  J2: [F1]
  F1: [F2]
  F2: [F3]
  F3: [F4]
  F4: [F5]

world_outside_quests:
  enabled: true
  ambient_events_require_quest: false
  quest_may_consume_world_fact: true

codexbridge:
  phase_0: [QCORE]
  phase_1_parallel: [QJOURNAL, QINTERACT, QPERCEPT, QVOTE]
  phase_2_parallel: [QCONTENT-EARLY, QCONTENT-MID, QCONTENT-FINAL, QE2E]
  integration: [QINTEGRATE]
  result_sections:
    - IMPLEMENTE_EN_SOURCE
    - COMPILE
    - TESTE
    - BLOQUE
```