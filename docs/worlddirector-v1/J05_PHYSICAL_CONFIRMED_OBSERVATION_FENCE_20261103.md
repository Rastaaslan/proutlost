# J05 - Fait physique CONFIRMED sans observations (2026-11-03)

Defaut reproduit sur FIXE : ServerEffectPort.execute acceptait une FactEvidence
avec status CONFIRMED et observations={} malgré l'absence de preuve native de
post-etat attachee au fait. La comparaison etait vraie par vacuite et laissait
passer un fait sans observation apres depense du permis durable.

Correctif : chaque fait physique CONFIRMED doit contenir au moins une
observation, puis chaque empreinte doit correspondre aux observedHashes du
SliceResult de la meme operation. Un echec ne doit jamais relancer une mutation ;
seule la reconciliation du WAL peut clarifier le resultat apres un effet incertain.

RED : C:\WorldDirectorS2\logs\RUN21_EMPTY_CONFIRMED_FACT_RED_20261103.log
(echec attendu AssertionError: expected rejection).
GREEN : C:\WorldDirectorS2\logs\RUN21_EMPTY_CONFIRMED_FACT_GREEN_20261103.log

Ces tests sont en Java pur avec binding en memoire. Ils ne valident ni un
serveur natif Minecraft, ni un redemarrage ext4/WAL, ni deux vrais clients.
Un fait CONFIRMED muni d'observations authentifiees reste accepte.
J04 PREPARE fsync et la distinction TEST_ONLY/PRODUCTION restent intacts.
