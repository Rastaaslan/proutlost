# CB-213 — vérification Windows FIXE (8 novembre 2026)

## Portage et environnement
- Poste : FIXE, répertoire `C:\WorldDirectorS2\work\main`.
- Java : Temurin OpenJDK 21.0.11 ; Minecraft 1.21.1 ; NeoForge 21.1.248 ; Gradle wrapper 8.14.4.
- Sources Quest Core, campagne P0→F5, journal, perceptions, interactions, votes, persistance et ressources présentes et compilées dans le module proutlost.
- Modifications antérieures non commitées conservées ; aucun commit, reset, merge ou push. Aucun serveur de jeu live ni monde live touché.

## Résultats exécutés sur FIXE
1. `gradlew.bat --offline test build --no-daemon --console=plain` : **PASS**, code de sortie 0, JAR généré ; voir `build/quest-windows-build-verification.log` et `build/test-results/test/*.xml`.
2. `gradlew.bat --offline runGameTestServer --no-daemon --console=plain` : **PASS**, code de sortie 0, **22/22 GameTests** après une correction locale de fixture, journal `run/logs/latest.log` (22:19:50).
3. Quatre campagnes complètes, chacune testée par Gradle sur deux processus serveur distincts (checkpoint C1 puis reprise avec `-PquestRestart=true`), vraie couche de commandes et deux acteurs simulés : **8 exécutions Gradle réussies (0)**, un GameTest réussi par démarrage. Variantes : `repair_filter_unite`, `detour_filter_distribute`, `repair_amplify_stable_distribute`, `detour_amplify_disturbed_unite`. Journaux : `build/quest-gradle-<variante>/logs/latest.log`. Les quatre reprises rapportent `All 1 required tests passed` et la suite groupée termine code 0.
4. Correction ciblée du test WorldPrep `WorldPrepPackInspectionGameTests.actualRuntimePackAndRegistriesProduceCanonicalIdentity` : ajout de `proutlost_director` à la classification `RUNTIME_ONLY` du pack de développement FIXE. Avant correction, 1/22 GameTests échouait exclusivement sur `Unclassified installed mod: proutlost_director`. Aucun comportement de production du mod n'a été modifié par cette correction de fixture.

## Artéfact Gradle candidat
- `build/libs/proutlost-0.1.0.jar`
- Taille : 523619 octets
- SHA-256 : `7244E86BA7B4F79F8FF53FE42BC6939852FC9ADA30290999CA29273EF4E77D36`
- Vérification du contenu : `QuestRuntime.class` et `data/proutlost/quest/campaign.json`, `investigation.json`, `perception.json` sont présents dans le JAR.

## Limites restantes — **ne pas marquer 100 %**
- Tests multiclients **non exécutés** sur deux vrais clients Minecraft : réception/confidentialité des perceptions ciblées et sons, complémentarité, partage, journal visible/reprise, présentation des votes, variantes finales.
- Les deux acteurs du GameTest sont des connexions serveur embarquées (EmbeddedChannel), pas deux clients graphiques ; aucun résultat humain n'est certifié.
- Ne pas installer ce JAR sur le serveur Minecraft live avant qualification dans une copie isolée de la map et accord pour l'installation.

## Étape suivante
Valider sur copie de monde avec deux vrais clients (FIXE et Laptop, éventuellement River) et consigner les observations PASS/FAIL ; seulement ensuite clôturer les critères de livraison. Toutes les vérifications automatisées ci-dessus ont été effectuées sans toucher à la map live.
