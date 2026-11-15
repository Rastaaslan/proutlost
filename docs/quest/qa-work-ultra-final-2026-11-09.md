# Proutlost S2 — livraison et qualification

**Candidat fonctionnel vérifié sur deux vrais clients Minecraft.** Un parcours complet jusqu’à F5, sa sauvegarde et sa restauration ont réussi. Un second parcours, repris depuis une copie entière du checkpoint C1, démontre aussi la résolution de F4 avec un contributeur déconnecté. Le laboratoire fictif QA-00 → QA-05 est jouable et son résultat a été rechargé avec le candidat corrigé. Ce document ne revendique pas 100 % ni une acceptation acoustique humaine.

## Versions et fichiers

Minecraft **1.21.1**, NeoForge **21.1.248**, Java **21**. Installer les **deux JARs** côté serveur et dans chaque client QA.

| Fichier | SHA-256 |
|---|---|
| `proutlost-0.1.0.jar` | `B3CBF38327EF336A324A7D45CFBD87D53A41364A5DD707B35BB01EA2C63ECF55` |
| `proutlost-director-1.0.0.jar` | `01673C9A6BBC7E1195D504A7FA7BAD8DD5A246119497AB403411604CF8334774` |

Sur FIXE, dépôt et JARs : `C:\WorldDirectorS2\work\main\build\libs\`. Racine des installations et preuves : `C:\WorldDirectorS2\work\main\build\quest-work-20261109\`. Clients graphiques courants : `client-A-nbtfix` et `client-B-nbtfix`.

L’instance Laptop `C:\Users\damie\curseforge\minecraft\Instances\Proutlost S2 QA ISOLATED` dispose également des JARs mis à jour avec empreintes concordantes. Les preuves graphiques de cette session proviennent des deux clients FIXE ; aucune audition supplémentaire sur Laptop n’est revendiquée.

Les serveurs résident dans le conteneur FIXE `worlddirector-s2-linux-lab-20261101`, sur Linux/ext4 :

- Campagne QA : `/workspace/quest-work-20261109/normal-quest-server`, port local **25578**.
- Laboratoire fictif : `/workspace/quest-work-20261109/native-neutral-server`, port local **25579**.

## Correctifs et validation

Le défaut de sauvegarde NBT au-delà de 65 535 octets a été corrigé : les champs volumineux sont enregistrés en UTF-8 binaire, avec lecture des anciennes sauvegardes. Un vrai test de compression/relecture, une sauvegarde Minecraft de **98 184 octets de campagne** et les redémarrages graphiques ont réussi. Le dernier checkpoint valide a été restauré puis les interactions nécessaires rejouées, sans injection de progression.

Build Gradle : **PASS, sortie 0**. GameTests standards : **PASS, 23/23**. Les tests JVM courants couvrent aussi les quatre combinaisons de choix. Les quatre parcours de campagne serveur historiques constituent une preuve distincte : ils n’ont pas été réexécutés après ce correctif de sérialisation. Un parcours GUI intégral et son complément hors ligne portent sur le candidat courant.

## Matrice consolidée

| Périmètre | Résultat | Preuve ou limite |
|---|---|---|
| P0 → F5, une variante existante | PASS | Interactions des deux GUI, conditions physiques et progression normale. |
| QCORE-006, sauvegarde/reprise | PASS | F5 restaurée : **190 faits, 20 observations**, votes D3/F3 `DECIDED` inchangés. |
| Reconnexion et rattrapage | PASS | A seul au premier retour, puis B : nouveautés et journaux reconstruits. |
| C1/C2/C3 | PASS ciblé | Perceptions complémentaires, collecte volontaire, partage, redistribution, essai incorrect sans progression puis réponse issue des observations actuelles. |
| J1/J2 et convergences | PASS | Contributions distinctes et délais réels ; une seule contribution ne suffit pas. |
| D3/F3 | PASS | Deux bulletins réels, décisions et restauration ; doublon refusé au contrôle D3. D3 exige ensuite les travaux physiques. |
| F4-002 / TEST-FINAL-004 | PASS runtime | Après redémarrage, A seul termine F4 : **184 → 188 faits** ; B reste déconnecté avant et après. |
| Conséquences F4 | PASS ciblé | Points physiques ouverts ; répétitions désactivées pour trois lieux, dont le camp. |
| Arrêt effectif des répétitions | PASS | Deux joueurs près du feu pendant 4,5 s : mémoire inchangée, **134 faits de replay avant/après**. |
| B1, opportunité HYBRID | PASS | Aucun fait B1 consommé avant P3 ; un nouveau fait monde HYBRID est consommé après ouverture. |
| QA-00 → QA-05, Director natif | PASS antérieur | Deux vrais événements J04, confidentialité, absence de duplication, vote et conclusion fictive. |
| Rechargement fictif, candidat courant | PASS | Deux GUI : 6 faits, 2 observations, deux partages ; deux rejeux J04 dédupliqués, vote décidé intact. Nouveau format NBT binaire sauvegardé puis relu après un second redémarrage. |
| Audio perçu par un humain | NOT_TESTED | Envoi/réception technique et affichage ne prouvent pas l’audition. |

## Preuves et limites

`offline-runtime-receipt.json` consigne le complément F4 : deux contributeurs et deux votes restaurés, un seul joueur connecté pendant la résolution. Le complément a repris une copie entière du checkpoint C1 ; le premier monde F5 et ses archives intégrales restent conservés.

La preuve Director → J04 → Quest Core provient du laboratoire fictif et d’un fait générique réel du catalogue. La preuve B1 vient séparément de `AmbientRuntime`, conformément à son contrat HYBRID. Aucun événement Director n’a été renommé pour simuler B1. J04 utilise Linux/ext4 ; sa sécurité de synchronisation n’a pas été désactivée sous Windows.

Restent hors qualification GUI : autres variantes et R1, quorum/égalités, et remplacement durable PER-003. Leurs preuves JVM/serveur doivent conserver cette portée. La galerie jointe contient six captures Minecraft neutres et distingue effets vanilla et natifs ; elle ne contient aucune réponse de campagne.

Le démarreur fourni a passé **CheckOnly**, puis le démarrage réel du serveur et du relais. Les instructions du laboratoire précisent les flags TEST_ONLY, le marqueur de monde, les ports, les deux JARs et les conditions natives à respecter. Conserver ensemble monde et WAL lors d’un arrêt propre.

L’inventaire initial de **1 288 fichiers** reste préservé : quatre modifications attendues, cinq ajouts, aucune suppression ; toutes les autres empreintes sont identiques. Le live et les sessions non-QA n’ont pas été modifiés. Les réserves GUI ci-dessus restent distinctes de la qualification acquise ; l’acceptation humaine de l’audio demeure à réaliser.
