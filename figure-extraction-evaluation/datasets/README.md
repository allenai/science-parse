Datasets
======

The only "export" of this module is datasets.py which contains hooks to load figure datasets, the rest
of the files are a mess of scripts that were useful when building these datasets. If your only concern is
using existing datasets that you can make use of "datasets.py" and ignore everything else in this directory
and the rest of this README.

## Format
At the most basic level a dataset is a collection of PDFs and their annotations specifying where the Figure and
Tables inside that exist. Datasets also provide a cache for fully rasterized images of the pages of
each PDF which we use to crop output or to display images to the users. datasets also need to specify which
of their PDFs are 'test' vs 'train' files. Each PDF in a dataset is expected is associated with an ID that is
used to cross reference all this information. And finally, for some datasets there are PDFs where only a subset of their pages
were annotated, so datasets also need to record which pages were/should be annotated for each PDF.

While any file format or storage method is fine as long as it conforms to the Dataset API, the standard way
this is implemented is:

* Each dataset is its own directory
* Each directory has a 'pdfs' directory that stores all the PDF files as <document-id>.pdf
* Each directory has a test_ids.txt file that stores the test ids one per a line
* Each directory has an annotations.json file that stores the annotations. These are stored as a dictionary with
document-id as keys, which map to dictionary of Figure with figure names as keys (see 'Figure' in pdffigures_utils.py)
alongid
* Each directory has a page_images_color and page_images_gray directory storing the color and
gray scale rasterized images in as <document-id>-page-<page#>.{jpg,pgm}
* Each directory optionally has a pages_annotated.json which indicates which pages of each PDF are/will be annotated for figures
* Each directory optionally has a non_standard_pdfs.txt file listing the document ids of PDFs that are non standard, optionally
followed by a space, followed by an arbitrary explanation of why the PDF is unusual. Typically includes OCRed PDFs or
PDFs that have sort of text encoding problem.
* Each dataset has an object in dataset.py which defines the dpi to use to render color and gray scale images,
the current dataset version, and if only a subsample of pages are going to be annotated,
MAX_PAGES_TO_ANNOTATE, PAGE_SAMPLE_PERCENT, PAGE_SAMPLE_SEED specify the maximum number pages to annotate per each
document, the percent of pages to annotated for each document, and the seed to use when sampling pages from each document


## Scripts
For the purpose of building datasets the following scripts are provided:

* test_datasets.py contains some sanity checks on the existing datasets
* visualize_annotations.py can be used to build visualizations of the annotations in a dataset
* build_dataset_images.py can be used to build the page_images directories for each dataset once the PDFs are downloaded
* build_test_ids.py can be used to build a test_ids.txt file for a dataset
* build_page_sample.py can be used to build a pages_annotated.json file for a dataset
* gather_unannotated_documents.py can be used to gather images of pages of documents that still need to be annotated and
* organize them into directories
* build_annotations.py takes as input a file containing raw annotations as exported from th ai2-diagram annotation tool
* and does the pre-processing needed to turn it into a fully formed annotations.json file
