#!/usr/bin/python

# This script goes through the logs from an evaluation run, and identifies
# pairs of precision and recall errors. The intuition is that usually when we
# get a name wrong, it shows up as a recall error (because we missed the gold
# name), and also as a precision error (because we extracted a name not in the
# gold data). Usually these names are very similar, and usually these are not
# really errors. So this script identifies likely pairs of precision and recall
# errors, and outputs them into a table of pairs of names. These pairs are
# likely different forms of the same name, and should probably be treated equal
# by downstream tools.

def ngrams(s, n):
  for i in xrange(len(s) - n + 1):
    yield s[i:i+n]

def main():
  import sys
  evalLog = [line.strip().split("\t")[1:] for line in open(sys.argv[1]) if line.startswith("authorFullNameNormalized\t")]
  paperId2errors = {}
  for (pr, paperId, normalized, unnormalized) in evalLog:
    errors = paperId2errors.get(paperId, [])
    errors.append((pr, normalized, unnormalized))
    paperId2errors[paperId] = errors

  for (paperId, errors) in paperId2errors.iteritems():
    precisionErrors = [e[1:] for e in errors if e[0] == "precision"]
    recallErrors = [e[1:] for e in errors if e[0] == "recall"]
    for (pNormalized, pUnnormalized) in precisionErrors:
      pNgrams = set(ngrams(pNormalized, 2)) | set(ngrams(pNormalized, 3))
      for (rNormalized, rUnnormalized) in recallErrors:
        rNgrams = set(ngrams(rNormalized, 2)) | set(ngrams(rNormalized, 3))
        score = float(len(pNgrams & rNgrams)) / max(len(pNgrams), len(rNgrams))
        if score >= 0.5:
          print "%s\t%s\t%s" % (paperId, pUnnormalized, rUnnormalized)

if __name__=="__main__":
  main()
