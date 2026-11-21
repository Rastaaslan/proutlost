# P008 — J06 AnchorInteraction C2S — scénario avant correction

Écart : P008-GAP-J06-C2S-ANCHOR-INTERACTION.
Base : cinq fichiers de P008_ANCHOR_C2S_BASELINE.json, copie isolée uniquement.

Le canal `anchor_interaction` existe déjà dans le registraire NeoForge 1.1.0 et le serveur consomme le DTO `AnchorInteraction`. Le bouton de choix courant exécute toutefois une commande vanilla, dont le serveur construit lui-même le DTO. Ce chemin commande constitue une preuve distincte.

## Acceptation native requise

1. Construire les deux binaires de la même cohorte et installer sur un monde QA distinct, avec J04 réel, deux vrais clients Minecraft négociés et un choix neutre déjà autorisé par le catalogue QA. Ne réutiliser aucun identifiant QA-00 à QA-05. La variante LAB manuelle garde OP4.
2. Capturer l’état J04 avant le choix et les sessions effectives. Le symbole ciblé doit être reçu réellement avant la présentation du bouton. Le bouton existant doit exécuter sa branche client locale et transmettre un vrai payload `AnchorInteractionC2S` sur le canal déjà enregistré.
3. Observer séparément la trace bornée d’entrée du véritable handler C2S, le header exact émis et reçu, la validation serveur, puis le fait SELECTED et le RESULT après ACK du vrai J04. Une ligne « envoyé » ou la seule trace du handler ne prouve ni acceptation ni durabilité. Vérifier l’effet en jeu et les consommateurs concernés.
4. Sauvegarder, fermer proprement, puis lancer une nouvelle JVM : retrouver le fait et l’état physique persistant, sans nouvelle émission ni nouveau choix. La reprise ne doit pas appliquer deux fois la décision.
5. Refuser sans mutation : ancien boot/connection, mauvais effet/séquence/ancre, action hors liste, hors portée ou empreinte modifiée, offre fermée/TTL expiré, joueur hors rôles. Maintenir les refus existants de permissions et de quota. Ne pas transformer un refus en choix par défaut.
6. Le chemin commande historique et le suffixe de compatibilité sont conservés avec leurs propres validations. Vérifier le fallback avec un ancien client 1.1.0 et la consommation Brigadier locale avec le client du candidat ; le parse seul ne les démontre pas. Ils ne permettent pas de clôturer le scénario C2S ci-dessus.

## Vérifications locales intermédiaires

- Compilation et tests purs du lien entre contexte de bouton, véritable header reçu, session courante et DTO/wire.
- Régressions de transport existantes pour expiration, resession et validateur vivant.
- Cache de réception indépendant borné à 128, saturation par données pures déclarées, expiration, doublons sans extension de TTL et révocations de session/effet. La recette native utilise uniquement de véritables réceptions autorisées par les limites serveur.
- Parse Java des adaptateurs ; compilation NeoForge et recette Minecraft restent requises sur FIXE par l’intégrateur.

Aucune donnée auteur nouvelle, aucune modification du protocole ou des plafonds de sécurité, aucun déploiement live.
