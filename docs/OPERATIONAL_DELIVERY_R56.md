# Proutlost Saison 2 — R56 / Contra et campagne implantée

R56 conserve Contra et les réglages approuvés de R55. Il ajoute l'implantation effective des 17 lieux et 48 supports requis par la campagne : arrivée commune, supports consultables, lieux d'interaction, parcours, interventions de joueurs, protections et affichages de mémoire. Les 27 définitions, les faits narratifs, leurs dépendances et leurs portées restent inchangés. Les modèles réutilisés proviennent des mods déjà présents ; leur provenance technique figure dans les sources.

Les archives permettent de lancer la saison avec son implantation et ses raccordements. La vérification R56 reste volontairement courte ; elle ne vaut pas réception humaine ni validation intégrale de toutes les branches. **FULLSTACK_ACCEPTED n'est pas accordé.**

## Les fichiers nécessaires

- `PROUTLOST_S2_R56_CLIENT.zip` : les 53 mods client correspondants, configurations et installateur NeoForge.
- `PROUTLOST_S2_R56_SERVEUR.zip` **et** `PROUTLOST_S2_R56_MONDE_CONTRA.zip` : les deux parties nécessaires du serveur.
- `PROUTLOST_S2_R56_SOURCES.zip` : sources correspondant au module livré, SDK R55 conservé et fichiers techniques d'implantation/export.

## Windows / FIXE : installation neuve

1. Démarrer Docker Desktop en mode conteneurs Linux. Extraire `SERVEUR.zip` dans un dossier neuf, par exemple `C:\ProutlostS2-R56`. Placer `PROUTLOST_S2_R56_MONDE_CONTRA.zip` dans ce dossier ou son dossier parent ; ne pas l'extraire manuellement.
2. Double-cliquer `run.bat`. Le script vérifie et extrait le monde, puis crée `proutlost-s2-r56` et le volume Linux `proutlost-s2-r56-data`. Au premier lancement, Internet peut être nécessaire pour obtenir `eclipse-temurin:21-jre`. L'authentification des joueurs nécessite aussi l'accès aux services Minecraft.
3. Attendre `Done (...)!` et le chargement de la campagne (`CAMPAIGN_PHYSICAL_INSTALLED sites=17 supports=48`). Le profil de production est vérifié par son empreinte ; l'import géographique de campagne est repris automatiquement.
4. Dans la console, saisir `proutlost director health`. Lorsque la santé est `NORMAL`, saisir :

```text
proutlost director physical prepare contra_saison2 import_receipt_r56.json
proutlost director physical status
```

Attendre `grants=1`, puis saisir :

```text
proutlost director resume
proutlost director health
```

Le résultat attendu est `mode=PRODUCTION`, `J04=WRITABLE`, `phase=RUNNING`, `ports_manquants=0`. La préparation physique réautorise pour cette session le catalogue déjà importé ; elle ne reconstruit ni les bâtiments ni la progression. Conserver exactement le nom de reçu en minuscules.

5. Aux lancements suivants, réutiliser `run.bat`, puis les mêmes commandes de réautorisation/reprise. Le volume conserve la progression ; le monde initial n'est pas recopié dessus.

Pour un nom de conteneur ou port distinct :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Lancer-Serveur-Docker.ps1 -Nom proutlost-s2-r56 -Port 25579
```

Director requiert les garanties de synchronisation de fichiers Linux. Sous Windows, utiliser le volume Linux du lancement fourni.

## Linux

Avec Java 21 et un système de fichiers Linux local, extraire `SERVEUR.zip` puis `MONDE_CONTRA.zip` dans le même dossier. Lancer `sh run.sh`, puis exécuter les mêmes commandes Director. Les bibliothèques NeoForge 21.1.248 sont incluses. Aucun outil temporaire d'implantation n'est nécessaire au fonctionnement.

## Client et connexion

1. Installer Java 21 64 bits et créer une instance distincte **Minecraft Java 1.21.1 / NeoForge 21.1.248** avec l'installateur fourni.
2. Copier dans son dossier de jeu les dossiers `mods`, `config`, `resourcepacks` et `defaultconfigs` présents dans `CLIENT.zip`. Garder les 53 mods du pack R56.
3. Lancer avec son compte Minecraft. Dans **Multijoueur → Connexion directe**, saisir `127.0.0.1:25579` sur FIXE ou `IP-de-FIXE:25579` depuis un autre poste.
4. Pour l'accès distant, autoriser TCP **25579** et, pour Simple Voice Chat, UDP **24454** dans le réseau prévu. Le pack ne modifie pas le pare-feu ni le routeur.

Une première connexion de campagne rejoint l'arrivée préparée. Consulter le carnet avec `/proutlost quest journal`. Un clic droit sur les supports préparés déclenche leur lecture ou leur action lorsque les prérequis sont remplis. Le carnet fournit les commandes de comparaison, partage, discussion et vote ; conserver les guillemets autour des identifiants de relevés affichés. Les actions collectives gardent leurs règles et leurs délais configurés : une interaction refusée peut attendre un prérequis, une contribution ou une discussion.

## Sauvegarde et version antérieure

Dans la console : `save-all flush`, puis `stop`. Attendre la fermeture. Pour retrouver la console : `docker attach proutlost-s2-r56`.

Après arrêt, exporter une sauvegarde complète :

```powershell
New-Item -ItemType Directory -Force .\Sauvegarde-R56
docker cp proutlost-s2-r56:/server/. .\Sauvegarde-R56
```

Conserver ensemble monde, identités, journal Director, reçus, configurations et mods correspondants. Pour découvrir cette implantation, installer R56 dans un nouveau dossier/volume. Ne pas écraser une saison déjà jouée avec l'archive du monde initial. R55 reste conservé séparément ; aucune migration d'une progression R55 active n'est effectuée par l'installateur.

## Disponible et réglages

- Contra complet récupéré des couches lower et upper, avec ses évolutions cohérentes, dimensions et données Minecraft conservées. L'implantation ajoute des modifications bornées sur des chunks existants, sans régénération du monde.
- Dix-sept lieux préparés, quarante-huit supports liés, treize liaisons de terrain et une arrivée côtière praticable. Les zones sensibles résistent aux destructions ordinaires ; les points prévus pour les travaux deviennent modifiables selon le choix collectif. Le camp garde un espace constructible.
- Les supports d'enquête utilisent les textes existants ; l'expérience d'arrangement reproduit les objets réellement enregistrés par les joueurs. Perceptions, signaux, transmission, repli et mémoire utilisent leurs états et conditions existants. Les affichages de mémoire ne présentent que les synthèses déjà établies.
- Director de production et manifeste approuvés inchangés : neuf événements, deux chaînes, dix primitives, onze ports requis. SDK R55 conservé. Vingt-huit lieux géographiques/physiques sont intégrés ; les nouveaux lieux ont leur propre source d'implantation. Le catalogue D0 passe en révision 2 et conserve à l'identique les adaptateurs R55.
- WorldPrep et modules prévus conservés. Les volumes de protection de campagne sont enregistrés ; aucune passe WorldPrep n'est lancée à l'exécution de Director. L'ambient de routine reste activé. Les commandes de faits synthétiques restent désactivées.

Les réglages restent dans `world/serverconfig`, `world/proutlost/director-profile/profiles/initial.json` et les documents référencés par son manifeste. L'implantation est décrite dans `world/proutlost/campaign/manifest.json`. Modifier un profil/manifeste exige de recalculer ses empreintes à l'arrêt avec les outils inclus ; modifier un simple réglage de gameplay ne demande pas de reconstruire le monde. Ne pas déplacer manuellement les ancrages en cours de saison.

Les historiques lourds WorldPrep et LAB demeurent conservés sur dam-server. La filiation Contra d'origine et l'identité de contexte de production de R55 sont conservées.

## Limites précises

- La mine de 3 756 258 cellules et l'ancien donjon qui dépassait 128 fragments restent physiquement intacts. Leur D0 R56 couvre leurs fenêtres d'accès/ancrage observées, plutôt que leur volume intégral. Les ancrages nécessaires sont utilisables ; un futur effet qui exigerait tout leur volume doit produire un nouvel export adapté avant activation. Les parcours de campagne n'exigent pas cet export intégral.
- Les contenus existants marqués `ORIGINAL_TEXT_PLACEHOLDERS` restent présents avec leurs faits et fonctions validés. L'habillage littéraire/artistique définitif reste une limite de présentation ; aucun fait canonique nouveau n'a été inventé pour le remplacer.
- Les sentiers et structures fournissent une implantation fonctionnelle. Une réception humaine de leur lisibilité, de l'audio et du rendu reste à faire. Le pack nécessite plusieurs participants pour les contributions et votes collectifs prévus ; il n'est pas converti en campagne solo.

## Vérification limitée

Les résultats R54/R55 non affectés sont réutilisés. R56 compile les raccordements modifiés, contrôle nativement les supports et liaisons, importe réellement les exports et vérifie le chargement du monde, du profil et du jeu de mods livré. Un parcours court sur copie jetable couvre l'arrivée, les supports, les protections et la progression initiale avec les réglages de production. Les résultats exacts figurent dans `ETAT_LIVRAISON.json` et les preuves courtes ; aucun état de test n'est injecté dans le monde livré.

Non réalisés : nouvelle suite générale, charge 20–25 joueurs, endurance 8 h, campagne d'arrêts brutaux JVM, réception humaine audio/rendu/G5 et parcours intégral de toutes les branches de campagne.
