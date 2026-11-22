# Proutlost Saison 2 — R55 / Contra et Director de production

R55 conserve le module principal corrigé R54 A02 et corrige dans le SDK Director un blocage d’import sur son propre verrou. Il ajoute le monde Contra retenu par Damien, le profil initial approuvé, son manifeste et les exports physiques authentiques. Les valeurs restent dans les fichiers de configuration. Aucune portée ni contenu de quête n'est réécrit.

**Le serveur Contra et Director sont livrés pour utilisation. La campagne QUESTSPEC complète reste indisponible tant que ses supports physiques et leur implantation ne sont pas fournis ou réalisés. Cette limite fonctionnelle est distincte des qualifications complémentaires. FULLSTACK_ACCEPTED n'est pas accordé.**

## Fichiers à récupérer

- `PROUTLOST_S2_R55_CLIENT.zip` : les 53 mods client correspondants et l'installateur NeoForge.
- `PROUTLOST_S2_R55_SERVEUR.zip` et `PROUTLOST_S2_R55_MONDE_CONTRA.zip` : les deux parties du serveur, nécessaires ensemble. Le monde est séparé pour garder chaque téléchargement sous 512 Mo.
- `PROUTLOST_S2_R55_SOURCES.zip` : sources du module principal R54, du SDK corrigé et des fichiers d'intégration R55.

## Installation et lancement sur FIXE / Windows

1. Démarrer Docker Desktop, avec les conteneurs Linux. Extraire **SERVEUR.zip** dans un dossier neuf, par exemple `C:\ProutlostS2-R55`. Placer **MONDE_CONTRA.zip** dans ce dossier ou dans son dossier parent. Ne pas l'extraire manuellement.
2. Double-cliquer `run.bat`. Le script vérifie l'empreinte de l'archive du monde, l'extrait, puis crée le conteneur `proutlost-s2-r55` et le volume Linux `proutlost-s2-r55-data`. Docker récupère `eclipse-temurin:21-jre` s'il n'est pas déjà présent. Une connexion Internet est nécessaire à ce premier téléchargement et à l'authentification Minecraft.
3. Attendre `Done (...)!`. Dans la console, saisir `proutlost director health`. Une fois la santé `NORMAL`, saisir `proutlost director physical prepare contra_saison2 import_receipt_r55.json`, puis `proutlost director physical status`. Attendre `grants=1` : cette commande réautorise la source déjà importée pour cette session, sans reconstruire le catalogue. Saisir ensuite `proutlost director resume`, puis `proutlost director health`. Le résultat attendu est `mode=PRODUCTION`, `J04=WRITABLE`, `phase=RUNNING`, `ports_manquants=0`.
4. Les lancements suivants utilisent le même `run.bat`. Le script réutilise le conteneur et son volume ; il ne recopie pas le monde initial sur la progression des joueurs.

Paramètres facultatifs depuis PowerShell, pour une installation séparée ou un autre port :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Lancer-Serveur-Docker.ps1 -Nom proutlost-s2-r55 -Port 25579
```

Le stockage Director requiert les garanties de synchronisation de fichiers vérifiées sous Linux. Sous Windows, utiliser ce lancement Docker avec volume Linux. Le pack n'utilise pas un dossier Windows monté pour ses journaux durables.

## Installation Linux

Avec Java 21 et un système de fichiers Linux local, extraire SERVEUR.zip puis MONDE_CONTRA.zip dans le même dossier et lancer `sh run.sh`. Exécuter ensuite les mêmes commandes de console de préparation physique et de reprise décrites ci-dessus. Aucun installateur NeoForge serveur ni téléchargement de bibliothèque n'est requis : les bibliothèques de la version 21.1.248 sont incluses.

## Client et connexion

1. Installer Java 21 64 bits et créer une instance Minecraft **Java 1.21.1 / NeoForge 21.1.248** distincte. L'installateur NeoForge est fourni dans le client.
2. Copier les dossiers `mods`, `config`, `resourcepacks` ou `defaultconfigs` présents dans CLIENT.zip à la racine du dossier de jeu de cette instance. Conserver exactement ses 53 mods.
3. Lancer le client avec son compte Minecraft. Dans **Multijoueur → Connexion directe**, entrer `127.0.0.1:25579` sur FIXE, ou `IP-de-FIXE:25579` depuis un autre poste.
4. Le jeu utilise TCP **25579**. Simple Voice Chat utilise UDP **24454**. Les règles du pare-feu et du routeur ne sont pas créées par le pack. Pour des joueurs distants, utiliser l'accès réseau prévu et autoriser les ports correspondants.

## Arrêt et sauvegarde

Dans la console : `save-all flush`, puis `stop`. Attendre la fermeture. Pour rouvrir la console d'un conteneur déjà lancé : `docker attach proutlost-s2-r55`.

La progression est dans le volume Docker, distinct du dossier extrait. Après arrêt, exporter une sauvegarde complète :

```powershell
New-Item -ItemType Directory -Force .\Sauvegarde-R55
docker cp proutlost-s2-r55:/server/. .\Sauvegarde-R55
```

Conserver ensemble le monde, `world/proutlost/director`, ses identités et reçus, les configurations et les versions exactes des mods. Ne pas supprimer le volume pour une mise à jour. Ne pas remplacer la sauvegarde par la seule archive initiale du monde.

## Contenu disponible

- Monde Contra issu de l'union réelle lower + upper du candidat des 5–6 novembre : chunks, entités, POI, données des joueurs, dimensions et évolutions physiques conservés. Aucun monde vide de remplacement.
- Director en mode production : profil `proutlost:saison2_initial` approuvé, neuf événements, deux chaînes et dix primitives existantes. Import géographique des onze lieux ; export physique de neuf emprises observées nativement, avec deux besoins explicites rattachés aux définitions existantes. Les autres événements gardent leurs validations physiques natives existantes.
- WorldPrep R54 et modules existants, dépendances et configurations correspondantes. Les passes de préparation ne se lancent pas automatiquement en jeu. L'ambient de routine autour des camps est activé.
- Moteur de quêtes, carnet et 27 définitions canoniques conservés. Les commandes de test sont désactivées. Le moteur refuse de déclarer une action disponible quand son lieu préparé n'existe pas.

Les anciennes archives WorldPrep et le très gros journal LAB restent conservés sur dam-server. Ce sont des historiques de préparation, distincts des données Minecraft nécessaires au jeu. Sur la copie de saison, Director a créé nativement une nouvelle identité de contexte de production ; la filiation avec l'identité physique Contra d'origine est enregistrée dans `world/proutlost/season-origin`. Aucun journal LAB n'est vidé sous son ancienne identité.

## Limites concrètes

- L'implantation des supports de la campagne QUESTSPEC est absente de la préparation Contra récupérée : arrivée/épave, supports d'enquête, scènes et parcours canoniques. Aucune liaison arbitraire n'est livrée pour faire croire la campagne jouable de bout en bout. Le détail technique sans réécriture narrative figure dans `CAMPAGNE_IMPLANTATION_MANQUANTE.json`.
- Deux emprises restent hors de l'export D0 borné : une mine de 3 756 258 cellules et un donjon dépassant 128 fragments de géométrie protégée. Leurs blocs et leurs entrées géographiques sont conservés. Aucune capacité physique non observée ne leur est attribuée.
- Les assets narratifs définitifs signalés comme à produire dans QUESTSPEC restent à fournir ; leur absence n'est pas une demande de changement du canon.

Les réglages se trouvent dans `world/proutlost/director-profile/profiles/initial.json`, les définitions externes `data/proutlost_director/director/...`, et `world/serverconfig`. Un changement de profil doit rester versionné : recalculer les empreintes du manifeste et des besoins liés aux définitions, puis réinstaller un ensemble cohérent à l'arrêt. Les outils de compilation et de scellement utilisés sont inclus dans les sources, sous `scripts/delivery`.

## Vérification limitée à l'intégration

Le module principal et les résultats R54 non affectés sont réutilisés. Le SDK Director est recompilé pour les deux chemins de verrou d’import corrigés ; un seul contrôle ciblé couvre leur progression et leur libération. La vérification R55 porte sur le chargement Contra, le profil approuvé, l'import géographique, le reçu réel de l'export physique et le démarrage des fichiers livrés sans addon de préparation. Les preuves courtes livrées donnent les résultats réels.

Non réalisés : nouvelle suite complète, charge 20–25 joueurs, endurance 8 h, campagne d'arrêts brutaux JVM, réception humaine audio/rendu/G5 et parcours intégral de campagne. Aucun de ces essais n'est présenté comme passé.
