# Giga-ticket prêt pour la prochaine phase — World Director 1.0

**Statut : préparé uniquement. Aucun lancement par la mission de spécification.**

Implémente le dossier de spécification World Director 1.0.0 joint dans son intégralité, en conservant les décisions produit de la mission source. Le Director est autonome, fonctionne sans Quest Core et sans IA, accepte plusieurs événements simultanés dans les budgets, possède une mémoire de régions dynamiques et peut produire de vraies transformations/faune/catastrophes contrôlées.

Commence par lire README, TECH_SPEC, DATA_MODEL et IMPLEMENTATION_PLAN, puis tous les contrats spécialisés. Inspecte le dépôt réel et ses instructions. Crée un environnement de développement isolé. Verrouille les versions compatibles avec Minecraft 1.21.1, NeoForge 21.1 et Java 21 sans migration arbitraire. Gèle le socle G0 avant de distribuer J01–J08, avec propriété stricte des fichiers et fakes de ports.

Ne modifie, n'interromps et ne redémarre pas la génération écologique/WorldPrep/MapDev en cours pour développer ou tester. Les essais Minecraft utilisent une copie/monde de test dédié. Ne fais pas dépendre le moteur de WorldPrep, de StreamDashboard, d'un service web, de Twitch ou de YouTube. N'implémente pas le Quest Core à la place de son adaptateur.

Livre les dix domaines du dossier, les cinq onglets admin, les primitives physiques avec ownership/journal/reprise, le registre de contenu et les intégrations optionnelles. Respecte les hard validators sur toutes les voies, y compris REQUIRE, IA et force admin. Une modification joueur cède la trace Director par défaut ; aucune restauration aveugle. Les contrôles de danger précèdent des effets réels, et ne réduisent pas silencieusement les catastrophes au cosmétique.

Les valeurs `DEFAULT_FOR_TESTING` servent aux tests et doivent rester identifiées. Aucun asset test ni fake n'est une preuve de capacité livrée. Toute impossibilité fonctionnelle utilise le bloc TECHNICAL_CONFLICT de la spec, sans remplacer discrètement la règle concernée.

Suivre G1–G5 et le plan de tests : contrats/unitaires, dédié/clients, GameTests, crashs réels du processus de test, catastrophes/chaînes, IA down, performances/soak. Déclarer chaque test PASS/FAIL/NOT_RUN avec preuve. Ne pas revendiquer un déploiement tant que le JAR, les données, la sauvegarde/reprise et les capacités réelles ne sont pas qualifiés sur copie. La phase de conception est close ; le but de ce ticket est l'exécution des contrats, sans réinventer leurs fondations.
