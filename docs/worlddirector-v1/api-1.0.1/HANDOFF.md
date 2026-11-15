# API 1.0.1 — avenant technique candidat

IMPLEMENTE_EN_SOURCE: J01 histogrammes indexés; J05 autorité de stabilisation forcée; J03 cooldowns, profils, politique persistée et planification pure; J07 mailbox et projections admin.
COMPILE: PASS Java 21 strict et probe réel Minecraft 1.21.1 / NeoForge 21.1.248.
TESTE: PASS 69339 assertions; schémas/config231, inventaire, régression evidence, exclusion testkit/WorldPrep du JAR.
BLOQUE: aucun correctif source connu; qualification/publication par Work à venir. Gradle de cet avenant, natif/client/GameTests: NOT_RUN.
public_interfaces_changed: liste exacte delta_files du manifeste; DirectorPrimitive.plan et DirectorMessageCodec inchangés.
capabilities_added: contrats uniquement, aucune capacité runtime de production annoncée.
migrations_added: aucune; nouveaux champs du codec interne exigent migration explicite si données anciennes existent. Recompiler les consommateurs.
integration_dependencies: J01 mémoire; J02 profils compagnon/hash; J03 évaluation; J04 persistance CAS/audit; J05 planner, stop et admin acteur; J07 facade; J08 politique; bootstrap Work inchangé.
next_smallest_blocker: qualification Work puis autorisation explicite de changement de baseline.

Source digest: `c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b`. API digest: `0dfa1f83b54741d8a212d6e66f7ba956857dfc031b56f92082454513dcddb228`. Parent G0: `e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2`.
Pas de commit local créé; opérateur Work propriétaire du commit/certificat. Base effective: `9d895ecf335f2cc7770f7f89b328c48e5e3173d3`.

Commande complète reproductible: `python3 scripts/test-director.py --platform`. Les commandes javac détaillées et hashes sont référencés dans TEST_EVIDENCE_1.0.1.json; Java inventory enregistré après compilation et revérifié intégralement.

JAR immuable: `build/director/API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar` SHA256 `a670ff5ea985882a6c90f4fc30aca5d898e7af82661e154df1255e21047f2259`.
Delta immuable: `build/director/API_1.0.1_DELTA_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.tar.gz`. Reçu externe AMENDMENT_HANDOFF_1.0.1.json après création (exclu du tar pour éviter un hash autoréférent).
Le delta contient uniquement les fichiers API/tests modifiés ou nouveaux et les documents de cet avenant. Appliquer sur G0 e769..., aucun ancien build.gradle/bootstrap/config/scripts inclus. Les archives G0 historiques et leurs attestations restent inchangées. Aucune qualification runtime/G1–G5 déduite.
