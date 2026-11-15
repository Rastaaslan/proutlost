# WORLD_DIRECTOR_TEST_PLAN

Plan de qualification future 1.0.0. **Aucun test Minecraft réel n'est autorisé ni exécuté pendant la mission de conception.** La validation de cette livraison se limite aux documents, schémas, fixtures et liens.

## 1. Environnements et oracles

U = tests Java purs avec fakes ; C = tests de contrats et golden ; I = intégration sur dossier temporaire ; G = GameTests NeoForge sur copie dédiée ; M = deux ou plusieurs clients ; F = fault injection process/disque ; S = soak/performance. Les seuils quantitatifs viennent du profil `DEFAULT_FOR_TESTING.json` et doivent être qualifiés sur le matériel cible.

Chaque test critique doit vérifier un état final observable, le store durable, les claims libérés/transférés et l'absence d'effet non autorisé. Vérifier seulement « pas d'exception » n'est pas un oracle suffisant. Les contrôles de corruption/crash doivent relancer un vrai processus de test ; une exception Java rattrapée ne simule pas une coupure.

## 2. Matrice fonctionnelle et contrats

| ID | Niveau | Cas | Oracle exigé |
| --- | --- | --- | --- |
| T-CAT-01 | U/C | schéma, champ inconnu, primitive inconnue, rôle absent, référence invalide, DAG cyclique | catalogue rejeté avec JSON pointer ; ancienne génération inchangée |
| T-CAT-02 | C/I | reload pendant instance | instance garde hash/révision ; nouvelle admission prend nouveau contenu |
| T-OBS-01 | U/I | même camp fréquenté, feu éphémère, observation répétée | confidence augmente seulement sur épisodes indépendants ; pas de trajectoire persistée |
| T-MEM-01 | U | decay par composante, ticks sautés, clock rollback, serveur arrêté | formules respectées, faits durables intacts, pas de decay double |
| T-REG-01 | U/I | création/fusion/scission et chevauchement | identités/alias cohérents, compteur non doublé, unicité héritée |
| T-SEL-01 | U/C | mêmes input/seed/catalogue/config | candidats/poids/tirages identiques au replay |
| T-SEL-02 | U | tirages sur échantillon du profil | distribution pondérée compatible avec probabilités ; le meilleur ne gagne pas toujours |
| T-SEL-03 | U | pool inférieur admissible, répétition, habitation | exploration occasionnelle, pénalité progressive, pas d'interdiction jusqu'à épuisement |
| T-FAIR-01 | U/S | joueurs identiques, groupe/solo, statuts stream absents | exposition équilibrée selon tolérance de test ; aucun bonus streamer ni groupe par somme |
| T-PACE-01 | U/S | REST/ACTIVE/RECOVERY, pas de candidats, nuit/jour, humour sous tension | périodes variées ; micro sûr ; pas de multiplicateur de débit nocturne |
| T-CONC-01 | U/I | événements compatibles dans régions distinctes et même région | plusieurs actifs admis dans budgets ; pas de singleton |
| T-CONC-02 | U/I | claims concurrents, priorités, merge autorisé/interdit | arbitrage non premier arrivé ; merge seulement via fonction enregistrée |
| T-QST-01 | U/I | Director sans Quest Core, sans préférences | autonomie complète |
| T-QST-02 | I | PREFER et REQUIRE pendant cooldown/danger/TPS dégradé | PREFER ne force pas ; REQUIRE attend avec raison durable |
| T-QST-03 | I/F | doublon inbox, annulation, consumer lent, crash avant ACK | effet local idempotent et faits relivrés sans double progression |
| T-QST-04 | I | NEW_EXECUTION vs ALLOW_EXISTING_FACT, unique consommé | aucun succès inventé ; conflit explicite si obligation impossible |
| T-UNIQ-01 | I/F | même unique en concurrence/reboot/fusion | un seul scope réservé/consommé ; statut incertain explicite |
| T-AI-01 | U/I | IA off/down/timeout/retard | choix déterministe disponible et aucun effet du résultat tardif |
| T-AI-02 | U/I | commande injectée, arc inconnu, NaN, score excessif, mauvais hash | rejet entier puis fallback ; aucun bypass safety |
| T-AI-03 | C | replay avec avis stocké | aucun appel modèle ; même résultat calculatoire |

## 3. Monde physique, catastrophes et ownership

| ID | Niveau | Scénario | Oracle |
| --- | --- | --- | --- |
| T-MUT-01 | G/I | transformation multichunk par tranches | pré/post exacts, budget par tick, événement/résultat cohérents |
| T-MUT-02 | G/F | joueur modifie trace avant rollback et cas ABA | cession persistée, aucune restauration écrasant le joueur |
| T-MUT-03 | G | piston/explosion/fluide/mod tiers modifie position possédée | invalidation ou conflit conservateur ; pas d'ownership maintenu par erreur |
| T-MUT-04 | G/F | BE/inventaire, drops, récompense déjà prise, retries | pas de duplication/perte ; primitive incompatible refuse avant action |
| T-OBJ-01 | G/M | objet décoratif joueur déplacé puis manipulé | capture sûre et inverse conditionnel ; objet manipulé conservé par joueur |
| T-ENT-01 | G/S | migration nombreuse, corridors chargés/non chargés | vrais animaux, cohortes/slots bornés, pas de force-load massif |
| T-ENT-02 | G/F | crash spawn, chunk entité unloaded, mort/adoption | aucune duplication ; adopté jamais supprimé par cleanup |
| T-SPAWN-01 | G | règle spawn modifiée puis stop/reload | modificateur contextuel révoqué, autres règles respectées |
| T-WEA-01 | G/M | météo dimensionnelle + ambiances locales coexistantes | physique partagé ; perceptions locales ; restitution conditionnelle |
| T-DIS-01 | G/M | éboulement/inondation/incendie/tempête/effondrement simulé | enveloppe contrôlée, dangers réels déclarés, signes et sortie lisibles |
| T-DIS-02 | G/F | reboot à WATCH/WARN/IMPACT/STABILIZE | pas d'impact nouveau sans warning valide ; propagation stabilisée |
| T-DIS-03 | G/M | joueur entre tard, quitte/revient, dimension change | audience réévaluée ; aucun majeur danger techniquement inanticipable |
| T-DIS-04 | G/S | serveur DEGRADED en plein danger | stabilisation prioritaire, aucune propagation abandonnée |
| T-DIS-05 | G | construction affectable explicitement vs donnée non capturable | contrat respecté ; pas d'immunité générale inventée ni de destruction hors modèle |
| T-CHAIN-01 | U/G | arc court sans quête, faits en doublon | étapes et scopes corrects, consommation une seule fois |
| T-CHAIN-02 | U/S | chaîne longue, boucle, branche, perte contexte, état durable | horizon borné sans longueur totale fixe ; pas de hot loop ou verrou zombie |

Les GameTests utilisent des templates dédiés, à coordonnées relatives, de la même version NeoForge que le build. `runGameTestServer` sera configuré pour les namespaces de test et un exit code exploitable en CI. Le test de classe de danger ne peut être remplacé par une validation JSON ; l'absence d'une capability maintient sa recette inadmissible jusqu'à qualification.

## 4. Matrice crash et disque

Injecter des arrêts entre chaque paire de points : blob écrit, blob fsync, TX_BEGIN, PREPARE commit/fsync, ACK consommé, première tranche, marqueur local, sauvegarde chunk, sauvegarde entité, verification, résultat WAL, outbox envoyée, ACK consumer, checkpoint fsync, manifest rename, suppression ancien segment. Répéter avec pré/post identiques mais provenance manquante et avec cession joueur durable.

| ID | Niveau | Panne | Oracle |
| --- | --- | --- | --- |
| T-CRASH-01 | I/F/G | WAL plus avancé que le monde et inverse | reprise depuis preuves locales ; pas de confiance aveugle au COMMIT |
| T-CRASH-02 | I/F | queue WAL tronquée vs corruption milieu | queue incomplète ignorée ; milieu corrompu isolé/lecture sûre |
| T-CRASH-03 | I/F | disque plein, fsync échoué, ACK très tardif | pas de nouvel effet sans permis durable ; ancien ACK ne réactive pas lease |
| T-CRASH-04 | I/F | manifeste nouveau invalide, checkpoint/blobs manquants | dernière génération vérifiable ; pas de reset store |
| T-MIG-01 | C/I/F | migration sauvegarde interrompue et version future | source intacte ; bascule complète seulement ; version future lecture sûre |
| T-BACKUP-01 | I/G | restauration monde + store cohérents sur copie | IDs/epochs/faits/entités cohérents ; aucune reprise sur carte active |

## 5. Client, multi-joueur et administration

Matrice M obligatoire avec deux clients indépendants, puis groupe mixte selon profil : son/particule/apparition visible seulement aux rôles ; physique/collisions identiques ; effets complémentaires ; cleanup à déconnexion, mort, changement dimension ; Stop perdu ; paquet ancien après reset ; version/capability manquante ; asset absent ; client lent. Un GameTest serveur seul ne prouve pas un rendu asymétrique.

T-NET-01 : fuzzer codecs, tailles, champs inconnus, UUID usurpé, cursor ancien, mauvais epoch, overflow. Oracle : rejet borné et aucun changement autoritaire. T-ADM-01 : tester tous onglets et commandes avec niveaux de droits distincts. T-ADM-02 : simulation/replay/force sous sécurité défavorable, vérifier zéro effet en simulation et refus clair du forçage. T-LOG-01 : audit suffisant au replay, absence de secrets/conversations, carnet hors quête créé uniquement sur choix explicite.

## 6. Performance et soak

Charge de référence fixe : même seed/carte, mêmes bots/actions, mêmes mods, mêmes ressources, baseline sans Director puis Director avec IA off/on. Scénarios : nombreux lieux dormants, groupe dense, joueurs dispersés, spam de blocs, cohortes, mutations multichunk et consumer lent. Échantillons/températures/erreurs statistiques, nombre de joueurs, durée de soak, cellules possédées et régions viennent du profil central.

S-PERF-01 exige respect du budget Director mesuré et files revenant à leur régime après pics ; aucun scan O(surface du monde). S-SOAK-01 exige pas de croissance non bornée des caches/clients/queues, pas d'instance active périmée et heap revenant à un plateau selon tolérance du profil. S-RECOVERY-01 inclut reboots durant le soak et reprise des longues chaînes. Des échecs de performances entraînent réduction de débit/calibration mesurée, jamais suppression d'une fonction validée sans conflit documenté.

## 7. Gates et preuves

G1 : contrats, schémas et fakes ; G2 : Java pur et stores ; G3 : dédié/client/perceptions/admin ; G4 : physique/crash/catastrophes/chaînes/performance ; G5 : calibration gameplay et livraison sur copie. Chaque gate produit commits, versions exactes, commandes exécutées, logs, rapports JSON et résultats. Un test non exécuté est `NOT_RUN`, jamais PASS par déduction.

Pour cette livraison : consulter `validation/STATIC_VALIDATION_REPORT.json` et `WORLD_DIRECTOR_SPEC_AUDIT.md`. Ces fichiers distinguent vérifications statiques réalisées et campagne future non exécutée.
