#!/usr/bin/python

import csv
import re

with open('mentions-filled.tsv') as mentions, open('mentions.tsv', 'w') as gold_writer:
	mentions = csv.reader(mentions, delimiter='\t')
	mentions.next() # skip header row
	gold_writer = csv.writer(gold_writer, delimiter='\t')
	gold = {}
	for paper, _, _, context, mention in mentions:
		if paper not in gold:
			gold[paper] = []
		cleaned_context = re.sub(r'\s+', ' ', context.strip())
		if not cleaned_context:
			continue
		gold[paper].append("{0}|{1}".format(cleaned_context, mention))
	for paper, bib_entries in gold.iteritems():
		if len(bib_entries) > 0:
			gold_writer.writerow([paper] + bib_entries)