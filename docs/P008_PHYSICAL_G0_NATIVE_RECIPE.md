# P008 — recette native du nouveau producteur physique et de sa projection G0

Préparée sur copies locales uniquement. Aucun démarrage, arrêt distant, chargement de chunks ou changement de monde effectué par cette proposition. La route `survey-export` B07 a été compilée par l'intégrateur mais n'a pas encore été exécutée ; `survey-director` est une nouvelle intégration à compiler sur le candidat courant.

## Scénario et périmètre

Un support QA déclaré, enregistré dans un chunk déjà FULL, est observé réellement en mémoire et dans Anvil après sauvegarde. Le vrai producteur scelle son D0 géométrique, puis le nouveau pont publie sa projection G0 et sa provenance. Un véritable joueur OP4 soumet le transfert à l'admission native J04. Les lecteurs du jeu retrouvent la région et le lieu, puis les retrouvent dans une nouvelle JVM. L'observation de blocs, l'export, l'admission et la lecture sont des preuves distinctes.

Chaque boîte D0 produit exactement une région et un lieu G0, avec membership explicite. Les maxima inclusifs D0 deviennent exclusifs G0 par `max+1`, sans enveloppe ajoutant des cellules. Aucune ancre ni aucun tag n'est créé. Les UUID dépendent de sourceWorld, du lieu D0, de la boîte canonique et du type ; le build et les révisions ne changent pas l'identité. Les doublons exacts de boîte sont refusés ; aucun alias physique n'est inféré. Le namespace `proutlost:observed_physical_geometry` reste distinct de `proutlost:worldprep_structures` et est pinné à son premier sourceWorld D0.

## Racine QA minimale et valeurs à lier avant exécution

Utiliser une **nouvelle** racine possédée, issue du template fullpack courant sans monde, jamais le monde J04 R2 conservé ni le monde GROUP consommé. Réutiliser le mécanisme fresh/bootstrap/seal déjà préparé par l'intégrateur ; ne pas créer un second gestionnaire de processus. Nécessaires : candidat complet client/serveur épinglé Java21/MC1.21.1/NeoForge21.1.248, empreinte des JAR, UUID réel du joueur QA, whitelist et OP4, port QA libre, propriétaire/processus avec heure de démarrage, seed réellement lu, répertoire monde absolu réellement créé par le jeu.

Disposition après premier bootstrap puis arrêt propre :

```text
QA_ROOT/
  mods/                         candidat épinglé
  server.properties             port/whitelist/level-name propres à ce run
  ops.json                      UUID réel QA, level4
  user_jvm_args.txt              flags ci-dessous, sans pin PRODUCTION
  WORLD/
    level.dat                   créé par Minecraft, identité/seed réels
    serverconfig/               WorldPrepConfig.SPEC effectif
    worldprep-candidate.json    marqueur neuf après vérification copie/propriété
    worldprep-surveys/requests/  intentions bornées produites par l'outil existant
    worldprep-surveys/exports/   snapshots/provenances immuables du vrai producteur
    director-location-imports/  archives G0 et messages, pas un registre d'admission
    director-lab-standalone/     J04 appartenant au runtime, chemin effectif à vérifier
```

Le marqueur neuf, au plus4096octets, contient `schemaVersion:1`, `candidateDirectory` égal au chemin absolu réel du monde, `offlineBaseline:true`, `physicalSurveyScope:"SYNTHETIC_QA"`. L'original d'un éventuel template/candidat et son marqueur sont conservés séparément ; le marqueur neuf ne prétend pas être une preuve physique. Le request SHA sera celui des octets exacts de ce marqueur.

WorldPrep effectif : `[worldPrep] enabled=false`, `physicalPasses=false`, `structuresOnly=false`, `loadExistingChunks=false` ; aucun moteur physique/qualification/structure/feature/faune/reconciliation actif. Confirmer le fichier serverconfig effectivement chargé par NeoForge avant test. Le LAB Director peut employer `-Dproutlost.director.nativeLab=true`. Activer aussi `-Dproutlost.worldprep.observedGeometryQa=true` pour la commande de vérification lecture seule. Laisser le catalogue automatique désactivé ; aucun événement n'est lancé par cette recette. Pour éviter les données narratives, réutiliser les flags neutralLab existants et son marqueur exact si le template les exige. Ne pas mélanger un pin PRODUCTION avec nativeLab.

La commande manuelle ne dépend pas des hooks GameTest. Pour la méthode GameTest optionnelle, namespace `proutlost`, `proutlost.quest.campaignGameTest=false/absent` et `proutlost.planner27.labOnly=false/absent`. Confirmer le nom enregistré dans le binaire épinglé ; le nom documentaire n'est pas une preuve d'énumération runtime. Méthode : `WorldPrepPhysicalGameTests.observedPhysicalExportUsesRealLoadedAndAnvilWitnesses`, batch `p008-physical-survey`, template `proutlost:qualification/arena`. Sa source serveur `withPermission(4)` prouve le vrai parseur/witness ; elle ne remplace pas la preuve d'authentification d'un client réel.

## Exécution manuelle par le véritable opérateur

1. Se connecter avec le client candidat et le véritable UUID QA whitelisté/OP4. Conserver le handshake et la négociation J06. Vérifier les refus OP<4 sur les deux commandes physiques et l'import Director ; ne pas accorder temporairement une fausse session pour faire passer le contrôle. La console authentifiée est légitime pour le survey mais **l'import Director actuel exige un ServerPlayer OP4 réel**.
2. Choisir une boîte d'un seul chunk **déjà chargé FULL**, hors réservation spawn. Vérifier les coordonnées contre spawn/build/border, puis constater le chunk sans le charger. Si absent, choisir un support déjà chargé ou employer le mécanisme QA existant de chargement de chunks persistés FULL, avec sa propre preuve ; le collecteur ne charge ni ne génère.
3. Dans ce nouveau monde synthétique uniquement, préparer un support simple de27cellules, par exemple une fondation3×1×3 de pierre avec deux couches d'air au-dessus, sans BlockEntity. Les coordonnées exactes viennent du chunk vérifié ; aucun nombre d'exemple n'autorise un accès à du terrain absent. Le sommet d'air doit permettre au joueur réel de se tenir **dans** la région exportée pour tester la demande spatiale.
4. Exécuter `/save-all flush` et attendre son ACK. Le collecteur ne sauve jamais le terrain. Conserver la commande et les empreintes Anvil fermées lors du prochain arrêt. Le GameTest alternatif sauve lui-même son arena et attend4ticks avant de capturer ; cette sauvegarde appartient au harnais, pas au collecteur.
5. Sur le propriétaire QA, créer l'intention avec l'outil existant, en liant les valeurs réelles :

```sh
python3 tools/make-observed-physical-survey.py \
  --world "$QA_WORLD" --seed "$ACTUAL_SEED" \
  --source-world proutlost:qa_physical_observation \
  --source-build "proutlost:p008_physical_${RUN_ID}" \
  --revision qa-r1 --scope SYNTHETIC_QA \
  --survey-id "$NEW_SURVEY_UUID" --dimension minecraft:overworld \
  --bounds "$MIN_X" "$MIN_Y" "$MIN_Z" "$MAX_X" "$MAX_Y" "$MAX_Z"
```

Les six bornes sont **inclusives**. Cet outil écrit une intention seule. Son UUID neuf n'est ni un identifiant historique ni une preuve physique.

6. Depuis le vrai client OP4 :

```mcfunction
/proutlost worldprep physical survey-export survey-UUID.json
```

Remplacer UUID par la valeur de l'intention. Exiger le résultat `PHYSICAL_GEOMETRY_ONLY_D0_SEMANTICS_UNAUTHORED_NOT_IMPORTED`, puis vérifier `worldprep-surveys/exports/UUID/catalog.json`, `evidence/request.json`, `witnesses.json`, `receipt.json` et leurs enveloppes/hash. NativeLoadedAndDiskCellWitness exige live avant/après identique et égal à l'Anvil indépendant. Réessayer à l'identique doit rendre `RETAINED_SNAPSHOT_VERIFIED_NOT_CURRENT_OBSERVATION_OR_IMPORT` avec les mêmes octets.

7. Avec le nouveau binaire contenant ce pont :

```mcfunction
/proutlost worldprep physical survey-director UUID
```

Exiger `GEOMETRY_G0_TRANSPORT_READY_NOT_IMPORTED`. Conserver `g0-projection/mapping.json`, `plan.json`, `receipt.json`, l'archive G0, le pointeur producteur et le dossier `observed-rN-HASH_transfer`. La révision technique est allouée par le producteur ; l'opérateur ne fournit pas une révision auteur de remplacement. La reprise d'une ancienne projection reste historique et ne devient pas automatiquement la source courante. Le pointeur producteur réserve `FROZEN_EXPORT` avec survey/catalogue/révision avant plan et transport ; une autre survey est refusée jusqu'à fermeture du pending initial. Après transport et receipt forcés, il devient `COMPLETE_EXPORT`. Cette reprise producteur doit être testée sur le candidat épinglé ; elle ne remplace pas la reprise J04.

8. Soumettre le nom exact renvoyé, toujours depuis le véritable joueur OP4 :

```mcfunction
/proutlost director locations import observed-rN-HASH_transfer
/proutlost director locations status
```

Le chemin `/proutlost director lab locations import ...` est également disponible pour le LAB explicitement activé. Le code `runtimeLocations` exige `runtimeGameplay` actif et `ServerPlayer.hasPermissions(4)`. Ne pas utiliser une invocation directe de l'importeur depuis le harnais comme substitut à cette admission.

9. Attendre le pivot J04 exact puis lire la source actuelle par les APIs natives : namespace, exportRevision, exportDigest, sourceSHA et génération doivent correspondre aux archives/provenances. Pour une boîte : exactement REGION, REGION_MEMORY et LOCATION, aucune ANCHOR. Vérifier le contenu, pas seulement le log d'ACK. Déplacer le vrai joueur dans la boîte et constater `runtimeMemoryView`, `memoryProjectionView` et `runtimePrimaryRegion` : lieu/région sélectionnés, membership exact, cache≤512 et aucune portée WORLD prétendant contenir tout le catalogue. L'observateur paginé existant exige des ancres first/far et n'est donc pas utilisé pour ce fixture sans ancre. Le raccord fournit `/proutlost worldprep physical survey-director-verify UUID`, disponible uniquement avec `-Dproutlost.worldprep.observedGeometryQa=true` et nativeLab, depuis le vrai joueur OP4. Il emprunte le propriétaire J04 courant en lecture seule, parcourt les têtes par96, atteste la génération et les heads du contexte sélectionné via runtimeRegionAnchorCoverage, puis lit les vrais MemoryView/primaryRegion. Le résultat distingue le cut J04 et le cut mémoire sélectionnée. Aucune ancre n'est inventée.
10. Arrêter proprement, vérifier l'identité du processus terminé et les verrous libérés, sceller le monde **et** J04 entiers avec les mêmes binaires/configurations. Lancer une nouvelle JVM compatible. Relire les receipts D0/G0 et la source J04, reconnecter le même vrai client, réexécuter la même commande survey-director-verify et comparer les identités/génération avec un bootEpoch différent ; ne pas réimporter pour masquer un défaut de récupération.

Refus utiles séparés : chunk absent reste absent, loaded/disk divergent refuse, BlockEntity refuse, request/marker/catalogue/witness modifié refuse, boîte dupliquée refuse. Conserver les refus et les tentatives, sans affaiblir les assertions.

## Voie ultérieure légitime depuis les supports Contra existants

Réutiliser `remaining_inventory/P008-L3-current-source-readonly-evidence.json` et `P008-L3-NEW_EXPORT_PATH_PROPOSAL.md`, observation du18novembre06:43UTC ; aucune nouvelle fouille d'archives n'est nécessaire pour recommencer ces mêmes recherches. Ces constats ne sont pas une propriété actuelle.

- Le dérivé `/home/damien/contra-s2-candidate-cow-20261105/upper` avait G0v3 SHA `7cd7f84e97a87d2ac3ecde706f8d04d23c133f8a435b693f0cf8f4977d288c65`, qualificationCURRENT SHA `19eeb76139f44d1f95bc05903d293526082dd59c88649b9a44a2163110274b33`, onze lieux/dix régions/trente-trois ancres, vingt-deux rôles, et huit preuves alors retrouvées et vérifiées. Il était rattaché à un conteneur actif ; son upper ne constitue pas un snapshot fermé.
- Les originaux de rôle et le guide validé peuvent déterminer un support/une géométrie existants. Ils ne définissent pas EventNeed/adapters. Préserver les fichiers et leurs hashes ; établir un propriétaire/snapshot cohérent puis une copie QA restaurable des chunks et receipts requis. Ne pas arrêter le service d'origine ni écrire dedans dans cette recette.
- Pour `VERIFIED_NATIVE_STRUCTURE_MODULE`, conserver les vrais `complete.json`, `director-native-location-candidate`, `native-plan`, `native-all-chunks-placed`, `native-disk-verified`, sans rollback-intent. `--native-module` doit viser le placement exact et les bornes doivent y être contenues. Les tags optionnels sont un sous-ensemble vérifié, conservé dans D0 ; ce mapping G0 technique ne les transfère pas.
- En scope `PHYSICAL_GEOMETRY_ONLY`, LAB biome/forced placement est refusé. Les témoins loaded+Anvil sont à produire maintenant sur cette copie cohérente avec une nouvelle sourceBuild/provenance ; les anciens rôles ne remplacent pas les cellules observées. Un support recadré reçoit une identité distincte, jamais le nom de toute la structure.
- Copy-A physique existait avec manifeste SHA `b35645cb8375ce50978036cf1355a3cd5e4db7174ea4fec7f06cd140c59ff5e9`, mais l'origine et copy-B étaient absentes. Cela ne permet pas de revendiquer le scellement V5 historique. Produire un export neuf est une opération distincte et légitime lorsque propriété, chunks et décisions de support sont établis.

Le besoin externe auteur ne concerne que la réception sémantique D0/événements et les affectations d'adapters. Il ne bloque pas ce pont géométrique QA ni les tests natifs du producteur. Aucune réception canonique Contra, événement, audio, perception humaine ou FULLSTACK_ACCEPTED n'est revendiquée.
