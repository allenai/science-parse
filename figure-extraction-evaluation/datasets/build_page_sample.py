import argparse
from os import listdir
from os.path import join, isfile
from datasets import get_dataset, DATASETS
from pdffigures_utils import get_num_pages_in_pdf
import json
import math
import random

"""
Script to select a sample of pages from a set of PDFs.
"""

def main():
    parser = argparse.ArgumentParser(description='Select and save a sample of pages ' +
        ' from some PDFs. Note the random seed is fixed to 0 by default')
    parser.add_argument("dataset",
                        choices=list(DATASETS.keys()))
    parser.add_argument("output_file")

    args = parser.parse_args()
    dataset = get_dataset(args.dataset)
    sample_percent = dataset.PAGE_SAMPLE_PERCENT
    seed = dataset.PAGE_SAMPLE_SEED
    random.seed(seed)
    max_pages = dataset.MAX_PAGES_TO_ANNOTATE

    if sample_percent > 1.0 or sample_percent < 0.0:
        raise ValueError()

    pages_to_keep = dataset.get_annotated_pages_map()
    if pages_to_keep is None:
        pages_to_keep = {}

    pdf_file_map = dataset.get_pdf_file_map()
    for doc in dataset.get_all_doc_ids():
        if doc in pages_to_keep:
            continue
        pdf_file = pdf_file_map[doc]
        num_pages = get_num_pages_in_pdf(pdf_file)
        keep = num_pages * sample_percent
        keep = math.ceil(keep)
        if max_pages is not None:
            if keep > max_pages:
                keep = max_pages
        pages_in_sample = random.sample(range(1, num_pages + 1), keep)
        pages_to_keep[doc] = sorted(pages_in_sample)

    with open(args.output_file, "w") as f:
        json.dump(pages_to_keep, f, indent=2)


if __name__ == "__main__":
    main()
