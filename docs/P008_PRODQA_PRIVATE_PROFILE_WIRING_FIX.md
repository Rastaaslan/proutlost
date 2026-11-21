# P008-D-PRODQA-PRIVATE-PROFILE-WIRING

## Défaut natif et scénario observable

Le 20 novembre, deux vrais clients officiels R5 ont négocié J06 dans le banc ProductionRuntimeQA. La JVM native identifiée par le root a ensuite échoué dans `DirectorProductionRuntime.fail` avec `IllegalArgumentException: verified private LAB profile required`. Les preuves natives, le monde et la clôture restent à leurs emplacements root ; cette proposition ne les modifie pas et n'attribue aucun succès.

Cause exacte : la maintenance réelle appelle `DirectorNativeGameplay.ensureCampState()`. Le contenu déclaré synthétique porte correctement `ProviderMode.TEST_ONLY` mais utilise le vrai orchestrateur ProductionRuntime. L'appel ne transmettait que ce mode ; `DirectorNativeCampStateProjection` interprétait tout TEST_ONLY comme un laboratoire et exigeait `nativeLab=true`. Le mode de qualification distinct impose précisément `nativeLab=false`.

Scénario de fin défini avant correction : nouveau candidat cohérent construit depuis les sources intégrées, nouvelle JVM identifiée sur une copie fermée traçable avec les preuves d'échec conservées, deux clients réels courants et deux J06 ; la maintenance CAMP s'initialise sans ce refus, le vrai runtime atteint son assemblage des neuf ports et le rapport natif garde TEST_ONLY / DEFAULT_FOR_TESTING / aucune approbation opérateur. Vérifier ensuite sa lecture WORLD/J04, les captures et la fermeture/reprise dans leur périmètre. Une compilation ou les tests purs ci-dessous ne ferment pas ce scénario.

## Correction minimale et frontière d'autorité

- Une factory package-private `DirectorNativeCampStateProjection.declaredSynthetic(...)` reçoit le même token opaque `DirectorProductionRuntimeQa.Inputs` que J01, WORLD et EVENT. Elle exige un token non nul et appelle `inputs.require(server)` avant construction. La configuration vient directement du token.
- Le constructeur privé vérifie encore le monde contre la vraie session J04, revalide le token avec le vrai serveur et exige la configuration exacte. Les constructeurs publics passent toujours un token nul : aucun booléen public ou flag JVM seul ne peut ouvrir cette voie.
- `ensureCampState()` choisit cette factory uniquement lorsque son contenu est effectivement déclaré synthétique ; il transmet `content.qualificationInputs()`, déjà réservé au bootstrap. Les appels LAB et production normaux sont conservés.
- La politique pure package-private sépare les trois cas pour une régression exécutable. TEST_ONLY ordinaire exige toujours le flag LAB et le profil vérifié ; production normale exige toujours APPROVED ; le token déclaré vérifié permet uniquement TEST_ONLY et DEFAULT_FOR_TESTING. Aucun ConfigView APPROVED, manifeste auteur ou attestation d'approbation n'est créé.

La portée est le raccordement du profil de la projection CAMP. Les algorithmes d'abandon/reprise du camp, les durées, seuils, limites, CAS, ACK, métadonnées, physiques et autorisations restent identiques. Le paramètre interne `declaredInputsVerified` n'est fourni qu'après validation du token opaque dans le constructeur privé ; ce n'est pas un nouveau point d'entrée.

## Intégration par le root seulement

Le manifeste contient les préimages SHA exactes des deux fichiers modifiés. Vérifier ces préimages avant toute substitution. Les autres fichiers de source doivent être absents. Intégrer les deux modifications séquentiellement dans la base canonique la plus récente, puis les deux nouveaux fichiers Java et le fragment Gradle. Ajouter exactement le texte de `build.gradle.APPEND` au `build.gradle` courant après revue ; ne pas remplacer un build.gradle plus récent par une ancienne copie.

Le fragment enregistre `directorCampProfileAuthorityTest` et le raccorde au gate `directorContractTest`. Le helper de politique reste sans dépendance Minecraft dans `src/director`; le test est dans `src/directorTest` et ne va dans aucun JAR officiel.

Après intégration : construire les vrais JAR officiels et le candidat lié aux nouvelles sources ; exécuter le test de politique et les régressions affectées. Le wire et le contenu de la fixture QA ne changent pas. Réutiliser l'addon passif de capture si ses garanties et la parité au nouveau candidat conviennent. Les anciens helpers strictement épinglés R5 doivent être adaptés avec des pins vérifiés et une nouvelle provenance avant un nouvel essai, jamais contournés.

Les mondes/prouves natifs d'échec restent fermés et conservés. Choisir une nouvelle copie traçable et un nouveau plan pour le candidat courant ; ne pas rejouer ni remettre à zéro les anciens reçus pour rendre la recette verte. Une reprise du monde d'échec doit vérifier son vrai nouveau boot et les éventuels travaux de récupération, indépendamment de la correction de profil.

## Vérifications effectuées ici

- Compilation Java21 stricte de la politique pure et de ses vrais enums API : PASS ; **15 assertions de politique PASS**. Elles reproduisent le mauvais routage, vérifient la voie de token déclaré et les refus LAB/production croisés. Les cas APPROVED utilisent seulement l'enum dans une fonction pure et ne produisent aucun profil autorisé.
- Parsing Java21 des quatre unités Java : PASS, zéro erreur. Typecheck Minecraft/NeoForge et build natif : **NOT_RUN** dans cette copie.
- Revue indépendante du token, des constructeurs, du routage et des préimages : aucun blocage d'intégration détecté. Pas d'autre mauvais branchement LAB démontré dans les lanes voisines du bootstrap neuf ports ; cette revue ne qualifie pas les autres événements.

Le premier essai local de compilation pure a omis `Checks.java`, importé par les enums existants ; le défaut d'entrée a été corrigé pour le second essai. Il ne s'agissait pas d'un refus natif ni d'une modification des assertions.

Réception native, neuf ports, effets, performances, canon et humain : ouverts. Aucun verdict FULLSTACK_ACCEPTED.
