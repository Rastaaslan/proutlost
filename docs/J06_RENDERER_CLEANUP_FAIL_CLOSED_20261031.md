# J06 — fail-closed du nettoyage renderer (31 octobre 2026)

## Problème corrigé
Le manager client devait faire confiance à `Effects.stop` : une exception
pouvait interrompre `clear()`, conserver des effets dans la table logique,
empêcher l'arrêt des autres effets et faire remonter une exception au tick
client. Même une nouvelle époque de connexion aurait pu réactiver un renderer
dont des effets physiques n'étaient pas garantis propres.

## Garantie de la nouvelle implémentation
- Toutes les identités actives sont retirées **avant** l'appel au renderer.
- Chaque arrêt physique est tenté indépendamment ; une exception ne fait pas
  abandonner la fermeture des autres effets.
- Tout échec d'arrêt rend le manager **définitivement indisponible** :
  `rendererFaulted` persiste à travers `disconnect()` et les nouvelles
  époques. Le manager doit être remplacé avec le renderer ; aucun retry de
  l'identité ni réouverture implicite.
- Le renderer Minecraft enlève la référence de son registre avant le cleanup,
  tente le `SoundManager.stop` puis le retrait de **toutes** les particules,
  et ne propage que la première erreur après les tentatives.
- Une exception de `Effects.available` retire l'ancien rendu, termine
  l'identité et retourne ACK ERROR plutôt que de faire planter le tick.
- Une exception d'émission d'ACK stoppe tous les cosmétiques et suspend
  la session ; un nouveau reset valide permet de reprendre si le renderer
  physique lui-même n'a pas échoué.
- Les stops d'instances, expiration TTL et resets conservent les clôtures
  des séquences/identités, sans prolongation artificielle.

## Contrôles
`NetworkTests.rendererStopFailureFailsClosed` vérifie :
1. deux effets actifs et un STOP physique qui échoue : deux tentatives de
   nettoyage, zéro effet logique, pas de reprise sur nouvelle époque ;
2. STOP explicite en erreur, sans double stop pendant le tick ni le reset ;
3. échec combiné start + stop : ACK ERROR émis et renderer quarantaine ;
4. exception de disponibilité sur effet existant : rollback/identité terminée ;
5. échec d'émission d'ACK : vidage des effets, suspension puis récupération
   uniquement via nouvelle époque valide.

Les assertions Java et les GameTests natifs sont **des qualifications
distinctes** ; les tests avec vrais clients et rendu F8 ne sont pas effectués.
Le code reste TEST_ONLY / DEFAULT_FOR_TESTING, sans asset APPROVED.

## Limite relevée dans le laboratoire
La première campagne du 31/10 depuis un worldDir sur /srv/storage a donné
26/32 GameTests, six délais WAL dépassés, puis attente de flush Minecraft au
shutdown. Trace threads sauvegardée ; SIGTERM appliqué **uniquement** à la JVM
du laboratoire. Ce FAIL doit rester visible.

En replaçant uniquement le worldDir/WAL de GameTest sur un nouveau répertoire
jetable NVMe, le même code (avant la protection de disponibilité/ACK finale)
a obtenu 32/32 puis 32/32 après redémarrage du monde, avec fermeture propre.
Ceci établit une corrélation forte de latence de stockage, pas une preuve
de qualification de performance production ni une excuse pour effacer le FAIL.

## Requalification finale du candidat J06

Après les ajouts de disponibilité et ACK, compilation et suites Java intégrales :
- réseau/perception : **3 654 assertions PASS** ;
- contrats 1.0.0/API 1.0.2 : **69 927 assertions PASS** ;
- catalogue 364, contenu 86, scoring 101, mémoire 321 : **PASS** ;
- build JAR autonome : **PASS**.

GameTests natifs **de ce code final** sur un monde de test neuf
`gametest-j06nv1031b` pointant uniquement vers un laboratoire jetable NVMe :
**32/32 PASS**, puis redémarrage exact du même monde et J04 WAL :
**32/32 PASS**. Les deux fois `DIRECTOR_NATIVE_BOOTSTRAP_SHUTDOWN_PASS`
et Gradle `BUILD SUCCESSFUL`. Logs datés du 31 octobre conservés.
Cette qualification reste un GameTest serveur, **pas** une connexion réelle
de deux clients ni une mesure G5 de charge/performance production.
