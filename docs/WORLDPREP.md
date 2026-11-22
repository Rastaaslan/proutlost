# WORLDPREP

This document describes the WorldPrep development contract. The authoritative requirements are in [WORLDPREP_SPEC.md](WORLDPREP_SPEC.md).

WorldPrep is an administrator-only, disabled-by-default compiler for disposable map-development copies. Preview is read-only; apply requires a matching persisted fingerprint and rollback snapshot. Unknown blocks, block entities, protected locations, and manual environments are never modified by default.

Current implementation status must be determined from automated build and test output; documentation is not evidence that a runtime acceptance gate passed.


## Empreinte de terrain des plans de blocs

Les nouveaux aperçus de blocs lient leur contexte et le terrain observé dans une trace obligatoire du plan. La capture et la revalidation utilisent le budget de cellules existant. L’application d’un nouvel aperçu revalide le terrain avant de créer le journal physique ; une modification rend l’aperçu STALE avant tout effet. Les limites de cellules et de santé ne sont pas relevées.

Le nouveau planner utilise une enveloppe de plan format4 avec sa trace obligatoire ; le codec des pages reste inchangé. Un ancien binaire refuse cette enveloppe. Le lecteur courant conserve les anciens formats pour lecture et pour les journaux/snapshots déjà propriétaires. Un ancien aperçu sans empreinte ne peut pas recevoir une nouvelle application : effectuer un nouvel aperçu explicite. Aucune empreinte de terrain n’est inventée rétroactivement.

Les phases de capture et de validation exposent une progression en cellules via le diagnostic en lecture seule. Une compilation ou un test hors runtime ne constitue pas une qualification native ; les résultats de recette sont consignés dans les registres du chantier.
