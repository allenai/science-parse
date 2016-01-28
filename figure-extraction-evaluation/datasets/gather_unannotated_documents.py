from os import listdir, mkdir
from os.path import join, isdir
import datasets
import shutil
import argparse

"""
Script to gather all the page images for documents that do not yet have
annotations for a particular dataset into one directory.
"""

def main():
    parser = argparse.ArgumentParser(description='Get unannotated images')
    parser.add_argument("dataset",
                        choices=list(datasets.DATASETS.keys()))
    parser.add_argument("-g", "--groups", type=int)
    parser.add_argument("-i", "--ignore_docs_in")
    parser.add_argument("output_dir")
    args = parser.parse_args()

    if not isdir(args.output_dir):
        raise ValueError("Output dir does not exist")
    if len(listdir(args.output_dir)) != 0:
        raise ValueError("Output dir must be empty")

    ignore_docs = set()
    if args.ignore_docs_in is not None:
        if isdir(args.ignore_docs_in):
            for filename in listdir(args.ignore_docs_in):
                if isdir(join(args.ignore_docs_in, filename)):
                    for sub_filename in listdir(join(args.ignore_docs_in, filename)):
                        ignore_docs.add(sub_filename.split("-page-")[0])
                else:
                    ignore_docs.add(filename.split("-page-")[0])
        else:
            raise ValueError()
        print("Found %d documents in %s, ignoring" % (len(ignore_docs), args.ignore_docs_in))

    dataset = datasets.get_dataset(args.dataset)
    pages_to_annotated = dataset.get_annotated_pages_map()
    if dataset.has_annotations():
        annotations = dataset.get_annotations("all")
    else:
        annotations = {}

    annotated_docs = annotations.keys()
    all_docs = dataset.get_doc_ids("all")
    missing_docs = list(set(all_docs) - set(annotated_docs) - ignore_docs)
    image_file_map = dataset.get_color_image_file_map()
    print("%d missing documents" % len(missing_docs))

    if args.groups:
        size = len(missing_docs) /args.groups
        groups = [missing_docs[round(i*size):round(i*size + size)] for i in range(args.groups)]
        for i,group in enumerate(groups):
            group_output_dir = join(args.output_dir, "group%d" % (i + 1))
            mkdir(group_output_dir)
            for name in group:
                if pages_to_annotated is not None:
                    pages = pages_to_annotated[name]
                else:
                    pages = image_file_map.keys()
                for page in pages:
                    shutil.copy(image_file_map[name][page], group_output_dir)
    else:
        for name in missing_docs:
            if pages_to_annotated is not None:
                pages = pages_to_annotated[name]
            else:
                pages = image_file_map.keys()
            for page in pages:
                shutil.copy(image_file_map[name][page], args.output_dir)

if __name__ == "__main__":
    main()
