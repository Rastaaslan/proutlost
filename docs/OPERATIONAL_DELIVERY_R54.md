# Proutlost S2 — livraison technique R54

## Etat exact

Les binaires R54 A02 et les sources correspondantes sont disponibles. Le correctif terrain a passe l'essai natif court : refus d'un ancien apercu apres changement de support, pause/reprise, rechargement du plan format 4, application de 28 changements puis inverse exact sur le volume controle. Les preuves acquises sont reutilisees. Aucun essai de charge, campagne complete ou nouveau cycle d'arret brutal n'a ete lance.

**La saison complete reste bloquee par ses entrees auteur. Ces fichiers ne sont pas annonces comme le pack final operationnel.**

Les archives precedentes n'incluaient ni monde Contra ni profil approuve. Le serveur R54 ajoute les configurations de saison, un installateur et le lancement du vrai manifeste Director ; il refuse de creer silencieusement un monde different.

## Installer le client

1. Utiliser Java 21 64 bits et Minecraft Java Edition 1.21.1.
2. Creer une instance separee avec NeoForge **21.1.248**, ou utiliser l'installateur NeoForge fourni si le lanceur officiel Minecraft est deja installe.
3. Copier le dossier mods de l'archive client dans le dossier de jeu de cette instance. Garder les 53 fichiers exacts.
4. Lancer cette instance avec son compte Minecraft habituel. Une fois le serveur de saison installe, rejoindre **127.0.0.1:25579** depuis FIXE, ou l'adresse choisie pour le serveur. Aucun serveur public n'est deploye ici.

## Installer le serveur de saison

Prerequis : le dossier du monde Contra ferme et complet, avec ses exports physiques authentiques, et le dossier du profil Director effectivement approuve.

Extraire l'archive serveur dans un dossier neuf puis executer dans PowerShell :

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Installer-Saison.ps1 -MondeSource "D:\Contra-ferme" -ProfilDirector "D:\Profil-Director-approuve"
~~~

Les chemins ci-dessus sont des exemples a remplacer par les dossiers existants. L'installateur conserve le monde source. Il ne produit aucune approbation ni export physique fictif. Les fichiers techniques seront prepares depuis les decisions auteur retenues ; il n'est pas demande a Damien de rediger les JSON.

L'installation place le monde sous world, le profil sous world/proutlost/director-profile et les configurations sous world/serverconfig. Les quetes sont activees avec les valeurs existantes et les commandes de test restent desactivees. WorldPrep est une etape de preparation distincte ; ses passes physiques ne sont pas executees automatiquement par Director au lancement.

Pour ecouter sur le reseau local plutot que sur FIXE seulement :

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Installer-Saison.ps1 -MondeSource "D:\Contra-ferme" -ProfilDirector "D:\Profil-Director-approuve" -AdresseEcoute "0.0.0.0" -Port 25579
~~~

Le port de jeu est TCP 25579. Le chat vocal utilise son port UDP configure (24454 par defaut). Aucune regle routeur ou pare-feu n'est modifiee par ces scripts.

## Lancer et connecter

Apres installation reussie et acceptation de eula.txt, lancer run.bat sous Windows, ou bash run.sh sous Linux avec Java 21 et Python 3. Le lancement calcule et transmet l'empreinte du manifeste reel au chargeur de production Director. Il verifie son accord avec l'installation et refuse un profil de test ou des arguments QA.

Depuis le client correspondant : Multijoueur > Connexion directe > 127.0.0.1:25579 sur FIXE, ou IP-du-serveur:25579.

Pour arreter : saisir save-all flush puis stop dans la console et attendre la fermeture Java. Sauvegarder ensuite le monde entier, ses donnees Director, ses plans/journaux/snapshots, les configurations et les memes mods. Restaurer toujours une copie fermee complete ; conserver les identites et les identifiants deja consommes.

## Donnees encore indispensables

- **Carte de reference** : le seul candidat Contra retrouve est le candidat du 5–6 novembre sur dam-server. Ses donnees G0 authentiques comportent 10 regions, 11 lieux et 33 ancres ; les qualifications geometriques sont conservees. Il n'est pas assimile a une carte finale approuvee.
- **Catalogue physique** : director-physical-catalogs/<nom>/catalog.json et adapters.json, lies aux vrais besoins physiques et a leur producteur scelle. Ces exports n'ont pas ete recuperes. La geometrie G0 ne les remplace pas.
- **Profil auteur Director** : manifest.json, profils, inventaire de primitives, references et 9 evenements/2 chaines prevus, avec approbation reelle. Le profil fourni pour revue conserve DEFAULT_FOR_TESTING et production_approved=false.
- Les 27 definitions de quetes existantes restent SERVER. Le moteur PERSONAL/GROUP est conserve ; aucune nouvelle portee narrative n'est inventee.

## Ce qui est disponible et ses limites

Les 53 mods client et 52 mods serveur, leurs dependances, WorldPrep R54, Director, Quest Core et les modules prevus sont assembles avec les memes versions. Les scripts de saison sont prets ; l'absence des donnees ci-dessus bloque encore leur demarrage de saison. Aucun monde QA, addon d'observation ou profil synthetique active n'est livre comme contenu de saison.

La matrice garde ses 179 exigences et ses statuts historiques. Ses lignes IMPLEMENTED_UNVERIFIED ne sont pas traitees comme du developpement absent.

**Non realise ici** : reception humaine rendu/audio/G5, calibration finale en jeu, charge representative 20–25 joueurs, endurance 8 h, suite complete et verification supplementaire apres un nouvel arret froid de l'inverse R54. Ces qualifications restent distinguees de la disponibilite des composants.

FULLSTACK_ACCEPTED n'est pas accorde.
