# Proutlost S2 — démonstrations de rendus réellement capturés en jeu (sans spoilers)

Date : 9 novembre 2026. Emplacement : FIXE, instance client QA NeoForge 21.1.248 / Minecraft 1.21.1, connectée uniquement au serveur QA isolé, port 25577 (Tailscale), monde jetable.
**Important : ce sont des captures d'écran Minecraft, pas des images générées et pas des captures de la saison live.**

## Galerie météo (tests directs via commandes Minecraft)
- Ciel dégagé : `fx-ingame-clear-day.png` ; commande QA `/weather clear 300`.
- Pluie : `fx-ingame-rain-day.png` ; commande QA `/weather rain 300`.
- Orage : `fx-ingame-storm-day.png` ; commande QA `/weather thunder 300`.
- Comparatif 3 vues : `fx-ingame-gallery-weather.png`.

## Galerie effets d'écran / ambiance
- Nuit : `fx-ingame-night.png` (commande de temps QA).
- Vision nocturne : `fx-ingame-night-vision.png` (effet d'état Minecraft sur l'acteur QA).
- Obscurité : `fx-ingame-darkness.png` (effet d'état Minecraft sur l'acteur QA).
- Particules violettes : `fx-ingame-particles-purple-front.png` (`minecraft:dragon_breath`, commandes /particle dans une zone neutre de QA).
- Aperçu particules blanches à densité excessive : `fx-ingame-particles-endrod.png`, utile pour calibrer un plafond de densité.
- Comparatif 4 vues : `fx-ingame-gallery-visual.png`.

Les planches sont composées de véritables captures de l'écran du client QA, redimensionnées et étiquetées par outil local ; elles ne synthétisent pas de nouveaux rendus.

## Interprétation stricte
**PASS :** le rendu visuel natif côté client Minecraft est observable pour ces états météo et effets de test.
**NON VALIDÉ :** déclenchement automatique par World Director ; rendu spécifique des assets/perceptions réseau propres à Director ; apparition/silhouette native ; brume locale native ; avertissements réseau ; dommages physiques contrôlés, inondations, glissements et feu ; qualité de l'audio ciblé. Ne pas attribuer PASS à ces fonctions sans captures réellement déclenchées par le pipeline Director.

Le code Director possède une voie de test administrateur `/proutlost director lab sound|mist|steps` et d'autres providers. Le processus QA consulté ne portait pas la propriété JVM `-Dproutlost.director.nativeLab=true`, donc il ne faut pas présenter les commandes de météo vanilla comme des effets Director validés.

## Protection
Toutes les opérations ont été faites sur les instances de QA. Aucun serveur ni monde live modifié. Aucun spoiler de quêtes, aucun nouveau contenu du CDC. Le laptop n'a pas été utilisé, puisqu'un autre jeu y était ouvert.

## Piste de qualification native
Ouvrir un laboratoire Director TEST_ONLY distinct et isolé du serveur QA des quêtes, avec les deux JARs adéquats et les assets côté client. Tester chaque famille de rendu depuis le pipeline Director réellement actif, avec captures/vidéos en jeu, paramètres, journaux et verdict PASS/FAIL/NOT_TESTED. Ne pas créer de faux PASS sur la base des simples commandes Minecraft.