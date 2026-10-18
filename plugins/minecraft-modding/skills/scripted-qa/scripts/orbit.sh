#!/bin/bash
# Print live-driver commands that photograph one entity from the front, three-quarter, left and right.
# usage: orbit.sh <tag> <shotPrefix> <dist> <up> <focusUp|-> <focusFwd> | qa.sh <runDir>
# <tag> is the subject's /qa tag; focusUp "-" means 60% of the hitbox height.
# Run with bash: zsh does not word-split "set -- $a".
for a in "0 front" "40 34" "90 left" "-90 right"; do
  set -- $a "$@"
  echo "cam $3 $1 $5 $6 $7 $8"
  echo "sshot ${4}_$2 6"
  shift 2
done
