#!/usr/bin/python
# format scholar-project/pipeline/src/main/resources/ground-truths/bibliographies.json as valid JSON before running this
# script inside that directory

from jsonsempai import magic
import bibliographies

papers = bibliographies.bibs

def refToStr(ref): # edit as necessary to include only authors/years/venues/etc.
    return ref.title.text + "|" + str(ref.year) + "|" + ref.venue.name + "|" + ":".join([" ".join([a.firstName] + a.middleNames + [a.lastName]) for a in ref.authors])

def paperToStr(paper):
    return "\t".join([paper.id] + [refToStr(ref) for ref in paper.refs])

with open('bibliographies.tsv', 'w') as f:
    for paper in papers:
        f.write(paperToStr(paper).encode('utf-8') + "\n")
