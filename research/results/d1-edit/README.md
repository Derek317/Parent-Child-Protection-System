# d1-omni-600M head edit (frozen patch 9c240628a9f37862...)

Variant A, ridge fraction 0.1, temperature 1.6663, cutoff 0.5. Changed entries: {'head.scorer.1.weight': 2048, 'head.scorer.1.bias': 2, 'head.scorer.3.weight': 1024}. Risk = level 2-3 (or label risk), benign = level 0 (or label benign); potential = level 1; unresolved = no level / review. Flagged = P(yes) >= 0.5.

| set | model | risk flagged | benign flagged | potential (L1) flagged | unresolved flagged | AUROC all | en | Cantonese | mixed | pass mark |
|---|---|---|---|---|---|---|---|---|---|---|
| tune | unedited | 51/90 | 10/45 | 13/45 | 9/45 | 0.744 | 0.813 | 0.600 | 0.878 | FAIL |
| tune | edited | 87/90 | 11/45 | 9/45 | 16/45 | 0.974 | 0.949 | 0.984 | 0.991 | FAIL |
| check | unedited | 38/90 | 3/45 | 6/45 | 5/45 | 0.760 | 0.831 | 0.702 | 0.800 | FAIL |
| check | edited | 85/90 | 3/45 | 7/45 | 17/45 | 0.975 | 0.991 | 0.993 | 0.960 | PASS |
| style3-test | unedited | 30/120 | 14/55 | 6/49 | 10/46 | 0.560 | 0.559 | 0.457 | 0.707 | FAIL |
| style3-test | edited | 77/120 | 18/55 | 5/49 | 8/46 | 0.758 | 0.733 | 0.765 | 0.869 | FAIL |
| blind90 | unedited | 32/36 | 29/36 | - | 15/18 | 0.535 | 0.507 | 0.562 | 0.528 | FAIL |
| blind90 | edited | 35/36 | 36/36 | - | 17/18 | 0.524 | 0.556 | 0.479 | 0.535 | FAIL |
| severity60 | unedited | 16/24 | 7/12 | 6/12 | 7/12 | 0.576 | 0.500 | 0.500 | 0.531 | FAIL |
| severity60 | edited | 21/24 | 7/12 | 7/12 | 6/12 | 0.747 | 0.719 | 0.656 | 0.844 | FAIL |
