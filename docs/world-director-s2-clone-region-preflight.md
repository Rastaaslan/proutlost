# Proutlost S2 — contrôle du clone Contra avant démarrage (TEST_ONLY)

Le contrôle en lecture seule `scripts/verify-proutlost-s2-production-start.ps1` exige désormais, en plus d'un `level.dat` gzip/NBT lisible, un répertoire `region` contenant au moins un fichier Anvil `r.<x>.<z>.mca` avec un en-tête d'offsets cohérent et au moins un chunk référencé. Chaque fichier région trouvé doit avoir une longueur alignée sur 4096 octets, un en-tête valide et des offsets et tailles de secteurs situés à l'intérieur du fichier. Les points de jonction directs sur `region` et sur les fichiers contrôlés sont refusés.

**Portée exacte :** cette vérification n'analyse ni les octets compressés des chunks, ni toutes les dimensions, ni les contenus de mods. Elle ne remplace **pas** les SHA256 de B, le scellement, la qualification de Contra ou la vérification complète du clone. Aucune preuve `APPROVED` n'est créée.

La suite `scripts/test-verify-proutlost-s2-production-start.ps1` utilise exclusivement des fixtures jetables sous `build/s2-preflight-fixtures`. Elle démontre les refus lorsqu'il n'existe que `level.dat`, quand un offset pointe hors du fichier, et l'acceptation **structurelle seulement** d'un en-tête minimal. La machine FIXE reste en mode `BLOCKED` lorsque RAM < 28 Gio, Contra actif, B non qualifiée ou OBS non qualifié. Aucun lancement ni modification d'instance n'est autorisé.

Le véritable serveur NeoForge 1.21.1 conserve **exclusivement** `-Xms20G -Xmx20G` au premier démarrage test et en production, sous réserve de tous les prérequis. Ne jamais appliquer à Protocole Zéro/WorldPrep. Derniers GameTests natifs qualifiés avant cette modification : `8a827f6` (34/34 + 34/34 sur même WAL), pas sur ce HEAD.
