# J05 — Authenticité du trajet animal après restauration WAL

Qualification : **TEST_ONLY**, sans habilitation de gameplay. Commit de correction sur FIXE uniquement.

## Défaut identifié
La liaison native `FaunaNavigationBinding` pouvait lire un trajet hydraté dans
`preparedPlan` sans vérifier que ce blob correspondait aussi aux empreintes
PRE, aux revendications exclusives, à l'epoch de génération, aux références
de la préparation et à l'unique capacité de navigation.
Elle ne vérifiait pas non plus la version du codec de trajet. Cette incohérence
pouvait laisser progresser une navigation avec métadonnées J04 contradictoires.

## Barrière
`FaunaNavigationWalFence` exige une relation **exacte** entre un blob
`animal_route` v1, une seule ressource `ENTITY`, son claim exclusif,
l'epoch, le prehash, le jeu des références WAL et la capacité de navigation.
Un blob illisible, une référence manquante ou une révision de codec inconnue
est refusé avant `revalidate` et `apply`. La récupération reste AMBIGUOUS,
et l'observation d'arrivée renvoie vide si le plan n'est pas authentifié.
La navigation démarrée n'est toujours PAS une preuve d'arrivée durable.

## Preuves et limites
19 assertions de contrat pur sur cette barrière, compilées et exécutées
via `directorFaunaNavigationWalFenceTest`; tâches Java Windows complètes
et construction du JAR. Le GameTest n'a été que compilé. Aucun test NeoForge
Linux du nouveau commit, aucune session à deux vrais clients et aucun
animal en mouvement sur serveur réel ne sont qualifiés.

Le bootstrap natif reste en observation TEST_ONLY, sans installation de
providers réels dans le cycle de vie. Cette barrière n'autorise donc pas
l'admission automatique ni le déploiement. Ne jamais contourner J04 PREPARE
fsync, ownership/CAS, permissions admin4 ou l'absence d'APPROVED.
