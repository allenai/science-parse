#!/usr/bin/python

import csv
import argparse

parser = argparse.ArgumentParser(description='Convert bibliography data into an empty TSV file for annotators to fill '
											 'in with citation mention data')
# bibliographies.tsv is the file inside this very directory containing high quality human-annotated bibliography data
# Note that bibliographies.tsv itself is generated from the scholar project:
#  scholar-project/pipeline/src/main/resources/ground-truths/bibliographies.json
parser.add_argument('-i', metavar='INPUT.TSV', default='bibliographies.tsv', help='Filename for bibliography TSV data')
parser.add_argument('-o', metavar='OUTPUT.TSV', default='mentions-blank.tsv', help='Filename for empty TSV')
args = parser.parse_args()

with open(args.i) as bibs, open(args.o, 'w') as mentions:
	bibs = csv.reader(bibs, delimiter='\t')
	mentions = csv.writer(mentions, delimiter='\t')
	mentions.writerow(["Paper ID", "URL", "Bib Entry", "Context", "Context Reference"])
	for paper in bibs:
		if paper:
			id, entries = paper[0], paper[1:]
			for i, entry in enumerate(entries):
				title, year, venue, authors = entry.split('|')
				authors = authors.split(':')
				mentions.writerow([id,"https://www.semanticscholar.org/paper/{0}".format(id), "[{0}] {1}. {2}".format(i + 1, ', '.join(authors), year), "", ""])
