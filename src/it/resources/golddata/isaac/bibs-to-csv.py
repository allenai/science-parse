#!/usr/bin/python

import csv

with open('bibliographies.tsv') as bibs, open('mentions-blank.tsv', 'w') as mentions:
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
