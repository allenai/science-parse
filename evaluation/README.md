Figure Extraction Evaluation
======

This is a set of python3 scripts for evaluating and comparing systems
that locate the figures, tables, and captions within PDFs. It also includes 
functionality for building datasets of gold standard annotations.

## Overview
There are two types of entities to think about:

1. Datasets, which are collections of PDFs and their gold annotations for some domain.
We have 3 datasets originating from ACL, S2 DBLP matches, and sampled from a selection of top conferences, named
"acl", "s2", and "conference". Datasets are also listed in datasets/datasets.py
2. Extractors, which are programs that can extract figure/caption bounding regions from PDFs.
Extractors are listed in extractors/extractors.py

To evaluate a dataset/extractor pair there are the following three scripts:

"build_evaluation.py" takes as input the name of a dataset and extractor, and scores the given
extractor against the given dataset. The result can be saved to to disk in a pickled file.

"parse_evaluation.py" reads a pickled evaluation file and prints the evaluation results, it can also
provide visualization of the ground truth compared to the extractor's output.

"compare_evaluation.py" takes as input two pickled evaluations and prints the PDFs and Figures
for which the two evaluations differed.

Existing evaluations to compare against exist in the "evaluations" folder. 

## Setup
To build an evaluation one first needs to download the PDFs for each dataset. When grading extractors it 
is also helpful to ensure the extracted bounding boxes get cropped to the same
rasterization of the PDF as the gold annotations. For this purpose gray scale images of each page for
each PDF need to be built or downloaded. 

Currently the PDFs and their page renders are stored in the datastore in org.allenai.figure-extractor-eval.
The zipped files in that folder need to be download and placed in their respective folders in the 
/dataset directory. For examples, 's2-pdfs' needs to be stored as datasets/s2/pdfs. There is a 
script to do this automatically:

`python down_load_from_datastore.py /path/to/DataStoreCLI.jar`

Alternatively one could re-download the PDFs and rebuild the rasterized images from scratch. This requires
the poppler-utility "pdftoppm" to be installed. The script "download_from_urls.py" can do this, but
is not as reliable since it can get stuck on PDFs for which we don't have valid URLs for.

## Dependencies:
python3 and the python library 'Pillow'. On Mac, do

```
brew install python3
sudo pip3 install pillow
```

If the PDFs are being downloaded from URLs the 'requests' library is also needed, 
as well as the poppler utility 'pdftoppm' to rasterize the PDF pages

Building new datasets requires some additional dependencies, see datasets/README.md

## Workflow
A typical workflow using this setup might be (after downloading everything):

1. Makes some changes to an extractor
2. Run "build_evaluation.py" to re-evaluate the extractor. For example:

`python3 build_evaluation.py conference scalafigures -o new_evaluation.pkl -w all`

to evaluate the extractor named "scalafigures" against all the PDFs in the dataset named "conference"
 and save the results to "evaluation.pkl".

3. Run "compare_evaluation.py" to see what changed between this run and a previous run:

`python3 compare_evaluation.py new_evaluation.pkl old_evaluation.pkl`

to view how the results in "new_evaluation.pkl" differed from "old_evaluation.pkl"

3. Run "parse_evaluation.py" to review the scores or to visualize the errors:

`python3 parse_evaluation.py new_evaluation.pkl`

## Evaluation Methology
We evaluate figures and captions as follows:

Extractors are expected to return a figure region, a caption region, the page of the
figure, and an identifier or name of the figure (ex. For "Figure 1"), and optionally the caption text.

For each extraction, we consider the extraction correct if:

1. The page was the same as the ground truth page
2. The identifier was the same as the ground truth identifier
3. The returned region bounding has (area of overlap)/(area of union) score of >= 0.8 with the
ground truth (same scoring criterion as used in PASCAL, http://host.robots.ox.ac.uk/pascal/VOC/)
4. The caption bounding box is scored in the same way, although we additionally
consider the caption correct if its text matches the ground truth text.

Returned figures with page numbers and identifiers that are not contained in the gold standard
are considered FPs, figures in the gold standard with page numbers and identifiers that are not found in
the extracted figures are considered FNs.

