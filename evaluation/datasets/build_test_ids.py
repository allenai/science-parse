import argparse
from os.path import isfile
import datasets
from random import shuffle
from math import ceil

"""
Builds test_ids.txt for a given dataset
"""

def main():
    parser = argparse.ArgumentParser(description='Evaluate a figure extractor')
    parser.add_argument("dataset",
                        choices=list(datasets.DATASETS.keys()))
    parser.add_argument("test_amount", type=float)
    args = parser.parse_args()

    dataset = datasets.get_dataset(args.dataset)
    doc_ids = dataset.get_doc_ids("all")
    shuffle(doc_ids)

    if isfile(dataset.test_ids_file):
        raise ValueError("test ids already exists!")

    if args.test_amount > 1:
        if int(args.test_amount) != args.test_amount:
            raise ValueError()
        test_num = int(args.test_amount)
    elif args.test_amount > 0:
        test_num = int(ceil(len(doc_ids) * args.test_amount))

    print("Using %d test and %d train" % (test_num, len(doc_ids) - test_num))
    test_ids = doc_ids[:test_num]
    with open(dataset.test_ids_file, "w") as f:
        for test_id in test_ids:
            f.write(test_id + "\n")
    print("done")

if __name__ == "__main__":
    main()
