# Laboratoire fictif Proutlost S2 — installation et utilisation

**Laboratoire jouable sans spoilers, candidat corrigé rechargé et résultat conservé.**

## Installation QA existante

Le laboratoire est indépendant de la campagne : monde propre, état `proutlost_quest_neutral_qa_v1`, commandes TEST_ONLY et port **25579**. Il utilise le vrai Quest Core, les perceptions et le vote existants.

| Élément | Emplacement |
|---|---|
| Dépôt FIXE | `C:\WorldDirectorS2\work\main` |
| JARs | `build\libs\proutlost-0.1.0.jar` et `build\libs\proutlost-director-1.0.0.jar` |
| Clients QA courants | `build\quest-work-20261109\client-A-nbtfix` et `client-B-nbtfix` |
| Conteneur serveur | `worlddirector-s2-linux-lab-20261101` |
| Répertoire serveur | `/workspace/quest-work-20261109/native-neutral-server` |
| Connexion des clients FIXE | `127.0.0.1:25579`, via le relais QA existant |

Chaque client charge les **deux** JARs sous Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. Vérifier les empreintes du rapport après copie et redémarrer uniquement les clients QA concernés.

Le serveur réutilise le launcher Linux/ext4 déjà préparé. Ses arguments nécessaires sont :

```text
-Dproutlost.director.nativeLab=true
-Dproutlost.quest.neutralLab=true
-Dproutlost.quest.neutralLabRoot=/workspace/quest-work-20261109/native-neutral-server/world
```

Le fichier `world/TEST_ONLY` contient exactement `PROUTLOST_NEUTRAL_QA_V1`. Le serveur utilise `server-port=25579`, une whitelist et deux participants opérateurs niveau 4. Dans `proutlost-quest-server.toml`, `enabled=false` et `testCommands=false` : les listeners de campagne ne sont pas enregistrés.

Le laboratoire refuse un chemin de monde différent du chemin déclaré, un marqueur absent ou un fichier de sauvegarde de campagne `data/proutlost_quest_v1.dat` présent.

## Reprendre le résultat déjà obtenu

Avant une mise à jour, arrêter proprement ce serveur QA, puis conserver ensemble son monde et son WAL J04. Ne pas dissocier le WAL d’un monde existant. Vérifier l’absence d’erreurs d’écriture, en plus de l’accusé de sauvegarde.

Pour reprendre le serveur et le relais déjà préparés, depuis FIXE, exécuter :

```powershell
& 'C:\WorldDirectorS2\work\main\build\quest-work-20261109\Lancer_labo_FIXE.ps1'
```

Le démarreur `Lancer_labo_FIXE.ps1` reprend le serveur et le relais existants ; `-CheckOnly` effectue uniquement les contrôles. Ces deux modes ont été exécutés avec succès. Le relais reste local et dure au maximum une heure ; le démarreur conserve ses anciens reçus avant une nouvelle session.

Connecter les deux clients dédiés à `127.0.0.1:25579`, puis :

```text
/proutlost qa status
/proutlost qa journal
/proutlost director lab catalogue configure
/proutlost director lab catalogue status
```

Sur la sauvegarde finale conservée, vérifier QA-03/04/05 personnelles et le résultat collectif déjà décidé. Cette reprise a été réalisée sur le candidat corrigé : six faits, deux observations, deux partages et deux bulletins conservés, sans réingestion des faits Director.

## Parcours fictif neuf

Utiliser un bac à sable distinct préparé avec les mêmes gardes. Garder la sauvegarde qualifiée. Un simple `reset TEST_ONLY` ne crée pas un nouveau monde ni un nouveau WAL.

Les deux joueurs doivent être présents, non spectateurs et à moins de 12 blocs. Depuis QAAlpha :

```text
/proutlost qa start QABravo
/proutlost qa scene
```

Chaque joueur reçoit seulement sa perception et son identifiant. Depuis chaque GUI :

```text
/proutlost qa record "<fragmentPersonnel>"
/proutlost qa share "<fragmentPersonnel>" <autreJoueur>
/proutlost qa journal
```

Après les deux partages, un participant compare les deux identifiants accessibles :

```text
/proutlost qa compare "<premierFragment>" "<secondFragment>"
```

`/proutlost qa recall` rappelle seulement la dernière perception du joueur. Pour un second essai, chacun se déplace d’au moins deux blocs avant `scene` ; les familles s’inversent. Employer les identifiants de l’essai courant.

## QA-03 : événement Director réel

`/proutlost qa effects` est une galerie **vanilla** : pluie, particules et notes. Elle ne résout pas QA-03 et ne prouve pas un rendu Director.

Le catalogue natif TEST_ONLY se pilote avec :

```text
/proutlost director lab catalogue configure
/proutlost director lab catalogue auto off
/proutlost director lab catalogue on
/proutlost director lab catalogue status
/proutlost director lab catalogue event proutlost_director:candidate/safe_micro
```

Employer l’identifiant exact du catalogue courant. L’admission garde ses conditions réelles : présence requise **12 000 ticks**, délai monde **1 200 ticks**, délai joueur **12 000 ticks**, requête expirant après **1 200 ticks**. À 20 TPS : dix minutes, une minute, dix minutes et une minute. Une requête expirée ne constitue pas une exécution.

Il faut deux événements SOLO effectifs, un fait privé par joueur. Seuls des faits `proutlost:safe_micro_observed` confirmés et durables résolvent QA-03. Vérifier la cible réellement choisie, l’identité du fait et `QA_DIRECTOR_FACT` ; une demande acceptée ne suffit pas.

## QA-04 et QA-05

Chaque joueur ayant sa propre QA-03 consulte :

```text
/proutlost qa dossier
```

Après au moins cinq secondes de discussion, un participant ouvre. Chaque GUI confirme une seule fois pendant les quinze secondes du scrutin :

```text
/proutlost qa open
/proutlost qa confirm ALPHA
```

La clôture se fait après le délai réel :

```text
/proutlost qa close
/proutlost qa finish
/proutlost qa journal
/save-all flush
```

Pour vérifier la persistance, arrêter proprement cette seule QA, conserver monde et WAL ensemble, redémarrer et reconnecter les deux joueurs. Relire leurs journaux et le résultat collectif. L’audio entendu reste à valider humainement ; les envois réseau ne suffisent pas.
