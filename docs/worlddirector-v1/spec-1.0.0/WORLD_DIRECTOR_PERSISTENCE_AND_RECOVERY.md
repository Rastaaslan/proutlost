# WORLD_DIRECTOR_PERSISTENCE_AND_RECOVERY

Contrat 1.0.0. Le Director possède des transactions logiques durables ; Minecraft ne fournit pas une transaction ACID commune à tous les chunks, entités, playerdata et effets réseau. Ce document définit explicitement cette frontière.

## 1. Stockage retenu

Un store par monde, sous `<world>/proutlost/director/`, sans base externe et sans dépendance SQLite native. Format : manifest double génération, checkpoints immuables, segments WAL encadrés et blobs content-addressed. Un verrou de fichier exclut deux writers sur le même store. Un clonage du monde doit cloner ce dossier et ses attachments ; un changement volontaire d'identité monde est un outil séparé, jamais un reset implicite.

| Élément | Contenu et règle |
| --- | --- |
| `manifest-A.json`, `manifest-B.json` | store/world IDs, génération, schema_version, checkpoint/hash, plage WAL, dernière séquence compacte, checksum |
| `checkpoints/<generation>.snapshot` | état logique durable, index nécessaires, références de blobs et cursors ; hash et footer complet |
| `wal/<first_seq>.wdwal` | transactions complètes ordonnées, rotation par profil de taille |
| `blobs/<sha256>.blob` | pré/post-états, définitions compilées, plans physiques, audits lourds |
| `audit/<segment>.jsonl` | projection lisible de l'audit ; reconstruisible depuis les records critiques/blobs |
| `recovery/` | rapports d'isolation ; pas une autre autorité concurrente |

WAL V1 : entiers binaires big-endian ; magic ASCII `WDW1`, format_version, frame_length, sequence, transaction UUID, payload_length, payload UTF-8 canonique, CRC32C de l'en-tête et payload. Une transaction est un frame `TX_BEGIN`, ses records puis `TX_COMMIT` avec hash ordonné des frames ; seul un commit entièrement validé est appliqué au replay. Les limites de frame/transaction et la compression des blobs sont des paramètres techniques de capacité. Les données NBT pré/post capturées sont des blobs opaques versionnés, avec taille/hash vérifiés ; aucune désérialisation Java native.

Encodage exact de frame : magic 4 octets ; version uint16 ; type uint8 (BEGIN=1, RECORD=2, COMMIT=3) ; flags uint8 (zéro en V1) ; frame_length uint32 incluant en-tête/payload/CRC ; sequence uint64 limitée au domaine positif d'un long Java ; transaction UUID 16 octets ; payload_length uint32 ; payload ; CRC32C uint32 sur tous les octets précédents du frame. Donc `frame_length=44+payload_length`. Les transactions ne s'intercalent pas. BEGIN décrit les révisions attendues et le nombre de RECORD ; COMMIT contient ce nombre et SHA-256 des octets complets BEGIN+RECORD dans l'ordre. Aucun COMMIT ne référence un blob non forcé. Les tailles/version/types de ce paragraphe définissent le format technique, pas une calibration gameplay.

Le writer unique écrit les nouveaux blobs, flush + force, puis les transactions qui les référencent et `FileChannel.force(true)` avant ACK. Il ne renvoie pas un ACK durable sur une simple file mémoire. Pour le manifeste/checkpoint : écriture d'un nouveau fichier temporaire, checksum/footer, force, rename atomique dans le même filesystem et synchronisation du répertoire lorsque supportée. Sur une plateforme ne garantissant pas cette synchronisation, les deux générations valides et le WAL conservé permettent une reprise conservatrice ; ne jamais affirmer une durabilité matérielle que l'OS ne garantit pas.

La taille des batches IO est bornée ; un PREPARE physique attend son ACK de manière asynchrone. Les observations anodines peuvent être regroupées ; les mutations, cessions, unicité, requêtes et faits durables passent par des transactions critiques. Les traces d'audit projetées peuvent être reconstruites, pas les intentions critiques.

## 2. Autorité et projections Minecraft

Le store est autorité sur la logique Director. Les blocs et entités Minecraft sont autorité sur le monde observé. Les attachments locaux relient ces deux autorités : `ChunkDirectorLedger` porte tokens/epochs, empreintes, cessions et un marqueur de tranche ; `EntityDirectorAttachment` porte identité de spawn, slot et disposition. Ils sont sérialisés avec leurs détenteurs ; ce rapprochement n'implique pas une atomicité de sauvegarde entre chunks ou entre chunks et entités.

Un `SavedData` de l'Overworld peut mémoriser identité/génération/checkpoint attendu. Au désaccord, comparer les preuves, pas écraser automatiquement l'un des stores. Les attachments ne doivent pas charger le store complet à chaque chunk. La projection opérationnelle est reconstruisible depuis le WAL, mais les preuves de cession et le monde courant restent prioritaires pour décider d'une compensation.

## 3. Transaction de mutation et tranches

1. **PLAN** sur snapshot : primitive connue, enveloppe, read set, pré/post-états, coût et plan d'inverse. Capturer BE, eau, scheduled behavior et voisins dépendants selon la primitive ; si cela dépasse le modèle connu, refuser cette cible.
2. **RESERVE** sur server thread : tous claims acquis atomiquement, revalidation du contexte et des protections. Créer un operation token et un owner epoch.
3. **PREPARE** durable : plan, snapshots, causalité, unicité réservée et noninvertible declarations ; ACK avant tout changement physique.
4. **REVALIDATE** à réception d'ACK : chunks encore chargés, hash/epoch encore identiques, budget et audience encore sûrs. Un changement invalide le permis ; libérer ou replanifier par nouvelle transaction.
5. **APPLY** sur server thread : tranche locale bornée ; mettre à jour ledger d'attachment et états physiques dans le même tour d'acteur. L'ordre et les callbacks voisins sont définis par la primitive. Aucun deuxième système Director ne peut intercaler une mutation sur les mêmes claims.
6. **VERIFY** : comparer état réel et attendu ; classifier les réactions voisines et observer les effets non inversibles. Marquer la tranche APPLIED/VERIFIED dans le WAL.
7. **COMMIT logique** durable : résultat, memory deltas, consommation d'unicité, facts/outbox dans un même batch. Libérer ou transférer les claims.

Une mutation volumineuse est une saga de tranches indexées, pas une boucle monolithique ni une promesse d'atomicité visuelle globale. Les joueurs peuvent voir les étapes prévues d'un éboulement ; les effets dont la sécurité exige une application instantanée au-delà du budget sont inadmissibles et doivent être conçus avec une primitive/recette adaptée. Le découpage ne peut transformer silencieusement une catastrophe en décoration.

Les callbacks de drops, explosions, fluides et blocks tiers sont des effets secondaires réels. Les primitives utilisent des adaptateurs connus qui contrôlent/suppriment les drops et la propagation automatiques lorsqu'une compensation les dupliquerait ; les loot rewards passent par des opérations identifiées distinctes. Les dégâts/morts, le ressenti d'un son et les objets déjà utilisés ne sont pas inversibles par restauration de blocs. La déclaration `noninvertible_effects` et les garanties de WARN sont obligatoires.

## 4. Ownership, cession et rollback

Chaque ressource possède une clé : `(dimension,blockPos)` ; `entityUUID` ; `weather:dimension` ; `decorativeObjectId` ; ou canal perceptif. L'ownership est distinct du verrou de réservation. L'epoch change à chaque transfert/cession. Une fusion de traces n'est possible que si une fonction de merge et son inverse partiel sont explicitement enregistrés, avec tokens des contributeurs ; sinon conflit/defer.

Lorsqu'un joueur agit volontairement sur une trace, le hook serveur incrémente l'override epoch, pose un tombstone `YIELDED_TO_PLAYER`, annule les écritures Director encore en file pour cette ressource et persiste cette cession. Le ledger local doit être marqué dirty dans le même tour. Le Director n'essaie pas de rétablir son décor au tick suivant. Les exceptions doivent être des contrats de contenu identifiés et visibles dans la définition, jamais une règle cachée du moteur.

Les modifications par piston, explosion, fluides ou mod tiers invalident aussi le pré/post-état lorsque leur causalité n'est pas Director. Elles peuvent être UNKNOWN plutôt que volontaires ; dans ce cas on renonce à écraser et on isole le conflit. Les hooks incomplets sont compensés par vérification incrémentale des seules positions possédées, pas par un scan global. Une égalité de hash après plusieurs changements ne suffit pas : l'epoch/tombstone protège du cas ABA.

Rollback d'une cellule uniquement si : token/epoch attendus toujours possédés, absence de cession, hash courant exactement égal au post-état capturé, et inverse encore sûr selon les voisins/données BE. Sinon `YIELDED` ou `CONFLICT_ISOLATED`, conserver l'état du joueur et journaliser la raison. Le rollback est une nouvelle mutation journalisée avec son propre token ; le relancer ne doit pas doubler un drop ou vider un inventaire. Une récompense déjà emportée n'est pas confisquée automatiquement par rollback.

Les objets joueurs ne sont déplaçables que s'ils sont décoratifs, sûrs et réversibles selon une primitive enregistrée : capture exacte d'identité/composants/position, aucune ouverture d'inventaire ou copie de pile par heuristique. Si l'objet est manipulé pendant l'effet, l'action se termine par cession et ne le récupère pas ailleurs. Les objets persistants créés pour le monde sont des ressources Director distinctes avec politique de transfert.

## 5. Consistance après crash : table normative

Au reboot, aucun effet n'est autorisé avant validation du store. Les ressources non chargées reçoivent un probe différé, sans force-load général. Le runner ne considère pas que `COMMITTED` dans le WAL prouve que Minecraft a déjà sauvegardé chaque bloc.

| Preuve durable et état observé | Décision |
| --- | --- |
| PREPARE seulement, pré-état identique et preuve locale fiable qu'aucune tranche n'est passée | RECOVER possible avec même operation token après nouvelle admission |
| PREPARE/APPLIED, post-état et token/epoch locaux identiques, pas de cession | COMPLETE_FROM_STATE ou cleanup selon recette ; aucun deuxième spawn/drop |
| COMMIT logique, monde revenu au pré-état, preuve de génération/ledger suffisante et aucun override | réappliquer l'effet durable déclaré récupérable, avec même token ; résultat audité RECONCILED |
| Post-état identique mais provenance absente, ou pré-état identique sans preuve d'absence d'action | AMBIGUOUS ; aucune écriture destructive automatique |
| Epoch/tombstone de cession présent ou hash divergent | préserver état courant, céder/isoler la cellule ; résultat PARTIAL/ABORT_SAFE selon dépendances |
| Une partie des chunks appliquée, autre partie absente/inconnue | rapprocher tranche par tranche ; stabiliser le danger, puis terminer ou compenser seulement les tranches prouvées |
| Entité présente avec spawn token et slot attendus | réattacher ; ne pas respawn |
| Entité absente mais chunk/stockage entités non inspecté ou spawn antérieur incertain | slot conservé UNKNOWN ; pas de respawn par simple timeout |
| Données vanilla corrompues ou registre mod manquant | isoler les ressources affectées, déclarer erreur ; ne pas convertir arbitrairement en air |

Le pré-état seul n'est pas une preuve suffisante d'absence d'effet : le joueur a pu agir puis revenir au même état. En cas d'ambiguïté durable, le système clôt l'instance en sécurité avec un dossier de conflit et des claims localisés de protection ; il ne boucle pas indéfiniment une mutation risquée. Ces dossiers sont visibles au dashboard. Une résolution admin doit fournir un nouveau plan vérifiable, jamais forcer l'ancien rollback sans preuves.

## 6. Politique par type d'activité interrompue

| Activité | RECOVER | CLEANUP / COMPLETE_FROM_STATE / ABORT_SAFE |
| --- | --- | --- |
| Son/particules/apparition | reprendre seulement effets encore utiles selon deadline et nouvelle session cible | TTL client et reset boot epoch ; ne pas rejouer un son unique incertain |
| Transformation physique | reprendre tranches prouvées non faites et sûres | terminer depuis post-état ; compenser les seules tranches toujours possédées |
| Catastrophe | restaurer stage, enveloppe et avertissements | stabiliser d'abord fluides/feu/débris ; aucun retour direct à IMPACT sans WARN encore valide pour l'audience actuelle |
| Migration animale | réattacher cohorte/slots, compter aussi UNLOADED | cleanup des seules entités encore possédées ; adopter/settle explicitement, jamais supprimer un animal cédé |
| Chaîne | reprendre nœuds depuis faits consommés, version pinée | suspendre si contexte absent, bifurquer explicitement ou céder à état régional ; pas de réémission des mêmes étapes |
| Requête REQUIRE | reprendre état et faits de fulfillment | rester WAITING/CONFLICT si aucun fait confirmé ; aucune progression inventée |
| Migration du format de sauvegarde | reprendre depuis une génération entièrement validée | revenir à l'ancienne génération intacte si la nouvelle n'est pas committée |

Les modes sont `RECOVER`, `CLEANUP`, `COMPLETE_FROM_STATE`, `ABORT_SAFE`, comme demandé par la mission. Une recette annonce son mode préféré ; les preuves et la sécurité peuvent imposer un mode plus conservateur. La deadline de recovery et le nombre d'essais sont des réglages, mais aucun dépassement ne supprime une conséquence durable ou une cession.

## 7. Unicité, réseau et livraisons incertaines

Avant un effet unique potentiellement irréversible, persister une autorisation d'exposition et son effect token. `SPENT_CONFIRMED` exige la preuve technique prévue ; `SPENT_POSSIBLE` signifie qu'un crash a empêché de distinguer envoi et réception. Il empêche une nouvelle instance unique automatique, sans prétendre que le joueur a vécu la scène. Le retry d'un payload dans la même session emploie le même effect_id ; un client déduplique.

Après reboot/reconnexion, un effet éphémère incertain n'est pas recréé en boucle. L'instance devient ABORTED_SAFE/DELIVERY_UNCERTAIN ou termine à partir d'une conséquence physique prouvée. Le Quest Core n'obtient un fulfillment que sur son prédicat de fait confirmé. Il peut alors soumettre une variante explicitement autorisée ; le Director ne réinitialise pas la portée unique pour satisfaire une quête coûte que coûte. Ce contrat préfère une incertitude explicite à une garantie exactly-once réseau impossible.

## 8. Entités, adoption et budgets

Un spawn réserve d'abord un slot durable `(instance_id,cohort_id,slot_id)` et un UUID prévu. Le token est installé avant l'ajout de l'entité au monde. Le hook de chargement rapproche token/UUID ; les tokens multiples signalent une duplication à isoler, pas une invitation à supprimer un animal adopté. Une entité déchargée garde son budget de persistance ; une disparition attestée par mort/suppression terminale peut libérer le slot. Le pathfinding est interrompu en cas de dégradation, mais les animaux restent des entités réelles.

Le cleanup retire seulement les entités éligibles selon propriété/disposition et état. Les animaux adoptés sont cédés, les cohortes durablement installées passent en propriété WORLD_SETTLED avec politique d'archivage ; elles ne sont pas régénérées à chaque reboot. La fermeture d'un événement transfère explicitement les ressources durables au monde, puis libère l'intensité active sans oublier leur coût vivant.

## 9. Checkpoints, compaction, retention et panne disque

Un checkpoint capture une révision logique cohérente via vue immutable ; sa sérialisation/compression est hors server thread. Le manifeste n'est avancé qu'après vérification des blobs et du checksum. La compaction supprime les vieux segments seulement si toutes leurs données requises sont dans un checkpoint durable, les transactions physiques encore récupérables sont conservées et les consommateurs outbox ont un chemin de replay. Garder plusieurs générations de reprise selon profil.

Les pré-états restent tant qu'un rollback/recovery est possible ; les cessions/unicités/faits durables sont compactables en index exacts, jamais effaçables par rotation d'audit. Les archives froides gardent la mémoire significative ; seule la mémoire anodine peut subir TTL/decay. Les audits ordinaires peuvent être échantillonnés ; ceux des mutations, dangers, conflits et erreurs ne sont pas perdus tant qu'ils soutiennent une garantie active.

Disque plein, fsync échoué ou writer arrêté : pas de nouvelle mutation/entité/effet irréversible sans journal. Passer READ_ONLY_SAFE, fermer les admissions critiques et nettoyer ce qui peut l'être avec les preuves existantes ; les TTL client restent indépendants. Ne jamais supprimer des sauvegardes Minecraft ou des archives utilisateur pour « faire de la place ». En cas de corruption en fin de WAL, ignorer uniquement les frames/transactions incomplètes de queue ; une corruption au milieu d'une chaîne valide bloque le replay à la dernière preuve et impose isolation, pas saut silencieux.

Chaque PREPARE de danger contient aussi un plan d'arrêt/stabilisation préautorisé, borné aux ressources/tokens et inverses sûrs déjà capturés. La perte du writer ne peut donc empêcher la coupure d'une propagation Director. L'exécution de ce plan utilise ses operation tokens existants, vérifie ownership/hash et écrit des marqueurs locaux ; son rapprochement au retour du writer ou au reboot reconstruit les résultats. Elle n'autorise aucune nouvelle transformation hors plan sous prétexte de cleanup. Une primitive de danger sans arrêt sûr exécutable après perte du writer n'obtient pas sa capability.

## 10. Versioning, migrations et sauvegardes

Versions distinctes : format WAL, schéma de sauvegarde, protocole réseau, schéma de contenu, version de primitive. Les migrations de sauvegarde sont des fonctions pures `vN → vN+1` sur snapshot hors monde, testées avec fixtures golden. Sauvegarder génération source + WAL + blobs ; écrire nouvelle génération ailleurs ; vérifier invariants/digests ; basculer manifeste atomiquement. Une version future inconnue passe en lecture sûre, jamais en reset.

L'unité de backup est le monde complet et son store Director, avec un arrêt/gel de sauvegarde cohérent du serveur. Copier uniquement le WAL ou uniquement les region files n'est pas un rollback sûr. Le plan futur doit tester restauration sur copie avant déploiement. Aucun script de backup, migration ou recovery n'est exécuté contre la carte active par cette mission de conception.
