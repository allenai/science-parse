#!/usr/bin/python

# This script takes a file with error pairs (found with findPRErrorPairs.py and
# then fixed by hand), and a file with gold data, and fixes the gold data
# according to the file of error pairs.

import sys

# read replacements
paper2replacements = {}
for line in open(sys.argv[1]):
  (paperId, ourExtraction, goldData) = [x.strip() for x in line.strip().split("\t")]
  replacements = paper2replacements.get(paperId, {})
  replacements[goldData] = ourExtraction
  paper2replacements[paperId] = replacements

# read gold data, write new file to stdout
for line in open(sys.argv[2]):
  line = [x.strip() for x in line.strip().split("\t")]
  
  paperId = line[0]
  sys.stdout.write(paperId)
  replacements = paper2replacements.get(paperId, {})

  names = line[1:]
  for name in names:
    replacement = replacements.get(name, name)
    sys.stdout.write("\t" + replacement)
  
  sys.stdout.write("\n")
