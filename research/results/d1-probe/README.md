# d1-omni-600M layer probe

Ridge linear probe per layer, fit on train (risk = level 2/3 vs benign = level 0), scored on tune.
Feature = true-option marker minus false-option marker activation (the positions the decision head scores).
Train n=900 (risk 600); tune n=135 (risk 90). Ridge strength by family-grouped 5-fold CV in train; cutoff = balanced-accuracy optimum on out-of-fold train scores.

| layer | AUROC all | en | Cantonese | mixed | risk flagged | benign flagged |
|---|---|---|---|---|---|---|
| embed | 0.500 | 0.500 | 0.500 | 0.500 | 0.000 | 0.000 |
| L1 | 0.620 | 0.791 | 0.503 | 0.562 | 0.800 | 0.800 |
| L2 | 0.656 | 0.764 | 0.546 | 0.656 | 0.833 | 0.689 |
| L3 | 0.988 | 1.000 | 0.989 | 1.000 | 0.944 | 0.044 |
| L4 | 0.998 | 1.000 | 1.000 | 1.000 | 1.000 | 0.089 |
| L5 | 0.995 | 0.993 | 0.996 | 1.000 | 0.922 | 0.022 |
| L6 | 0.995 | 1.000 | 0.987 | 1.000 | 0.978 | 0.067 |
| L7 | 0.996 | 1.000 | 0.996 | 1.000 | 0.989 | 0.067 |
| L8 | 0.990 | 1.000 | 0.978 | 1.000 | 1.000 | 0.067 |
| L9 | 0.998 | 0.998 | 0.996 | 1.000 | 0.978 | 0.067 |
| L10 | 0.999 | 0.998 | 0.998 | 1.000 | 0.967 | 0.022 |
| L11 | 0.992 | 0.998 | 0.996 | 0.996 | 0.944 | 0.044 |
| L12 | 0.983 | 0.993 | 0.978 | 0.991 | 0.933 | 0.044 |
| L13 | 0.988 | 0.991 | 0.993 | 1.000 | 0.967 | 0.067 |
| L14 | 0.990 | 1.000 | 0.982 | 0.998 | 0.978 | 0.133 |
| L15 | 0.975 | 0.953 | 0.989 | 1.000 | 0.956 | 0.067 |
| L16 | 0.978 | 0.942 | 0.993 | 0.993 | 0.911 | 0.111 |
| trunk_out | 0.974 | 0.940 | 0.991 | 0.989 | 0.933 | 0.178 |
| scorer1_in | 0.970 | 0.931 | 0.991 | 0.987 | 0.900 | 0.133 |
| scorer3_in | 0.932 | 0.927 | 0.922 | 0.993 | 0.911 | 0.222 |
| baseline model P(yes), cutoff 0.5 | 0.744 | 0.813 | 0.600 | 0.878 | 0.567 | 0.222 |
| baseline model P(yes), train cutoff 0.290 | 0.744 | 0.813 | 0.600 | 0.878 | 0.889 | 0.578 |

Self-check: probes on 20 shuffled-label draws, tune AUROC: L4 mean 0.485 sd 0.071 (range 0.35-0.64); L10 mean 0.496 sd 0.086 (range 0.35-0.68); trunk_out mean 0.496 sd 0.096 (range 0.25-0.73); scorer3_in mean 0.515 sd 0.105 (range 0.34-0.75).

Layer names: embed = token embeddings; L1..L16 = residual stream after trunk layer i; trunk_out = after the final norm (input to the head's 2 transformer layers); scorer1_in / scorer3_in = inputs to the head scorer's Linear layers (true minus false). Details: results.json (also a true-marker-only variant).
