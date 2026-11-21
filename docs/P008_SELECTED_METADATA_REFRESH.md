# P008 — sélection et relecture des métadonnées natives

Une vue conserve son snapshot J04, son cut, ses corps et son profil authentiques. La publication et la réduction conservent la vérification des révisions courantes ; un token de sélection ne rend jamais un ancien corps actuel.

Le scan complet sélectionne un jeu borné de clés depuis le catalogue. Si la demande et le profil sont identiques, la relecture suivante peut ne relire que ces clés, en pages de 96, au nouveau snapshot épinglé. Le cache J04 reste limité à 512 corps. Les limites de transaction, de message, de page et de jeu résident sont inchangées.

La réutilisation exige simultanément :

- Même propriétaire de snapshot, même StoreSession, mêmes sources et générations.
- Même demande spatiale et mêmes références explicites ; même ConfigView et ProviderMode.
- Census exact des têtes LOCATION/ANCHOR et des mémoires significatives des joueurs retenus.
- Census sémantique REGION : dimension, statut, support, ancres, parents, alias, révision de membership et références de mémoire. Les compteurs d'en-tête, l'importance et la personnalité sont relus dans les corps sélectionnés.
- Census sémantique REGION_MEMORY : identités des camps. Une nouvelle mémoire vide hors scope n'ajoute aucune dépendance temporelle.
- Census des références qui sélectionnent le scope : identité/dernière région PLAYER_OPERATIONAL, régions des EVENT actifs, lignées des CHAIN encore pertinentes.
- Maintien de chaque absence primaire, de REGION_MEMORY sélectionnée ou candidate, et d'ancre explicitement demandée.

Toute incertitude ou variation de ces preuves impose un scan complet. Le résultat du refresh doit encore réussir `currentHeads` avant publication et avant écriture. Une mise à jour sélectionnée concurrente reste un refus, puis une nouvelle relecture épinglée. Aucun état manquant n'est fabriqué ; aucun accès en lecture ne donne de permission d'append.

Les empreintes de sélection sont dérivées par le writer J04 lors des changements de têtes visibles, reconstruites au replay et copiées avec les projections immuables. Les pages en préparation restent invisibles. Le stockage de cet index contient des empreintes par tête, jamais les corps froids.

Un CAMP durable, actif et proche peut précéder son premier RegionRecord et sa première RegionMemory. Le lecteur spatial conserve alors son ancre réelle et son scope réservé ; l'apparition de la première mémoire invalide la preuve d'absence. Cela ne crée aucun RegionRecord.

Les tests de pression associés emploient 25 identités de joueurs et 30 transactions réelles MemoryReducer/J04 sur des données QA synthétiques, avec 1 360 heads ou 4 096 lieux sur deux sources. Ils attestent les publications et les corps relus aux cuts annoncés. Ils ne constituent ni une mesure TPS avec 25 clients Minecraft, ni une réception Contra ou auteur. Les mesures natives restent distinctes.
