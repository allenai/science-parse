from subprocess import check_output
from os.path import isdir, isfile, join
from datasets.datasets import DATASETS
from shutil import move
import argparse

"""
This script uses the DatastoreCLI (https://github.com/allenai/datastore) to download
the PDFs and the gray scale rasterized images for each dataset. The dataset are stored
in the private datastore so you will AWS credentials to download them.
"""

def main():
    parser = argparse.ArgumentParser(description='Download datasets using the datastore')
    parser.add_argument("datastore_jar", help="absolute path to DatastoreCLI.jar, ex '/Users/chris/bin/DatastoreCli.jar")
    args = parser.parse_args()
    datastore_jar = args.datastore_jar
    if not isfile(datastore_jar):
        raise ValueError("No file found at %s" % datastore_jar)

    for name, dataset in sorted(DATASETS.items()):
        dataset = dataset()
        print("*"*10 + " SETTING UP DATASET <%s> " % name + "*" * 10)
        datastore_version = dataset.datastore_version()
        base_args = ["java", "-jar", datastore_jar, "download", "-g", "org.allenai.figure-extractor-eval",
                     "-d", "private"]
        if not isdir(dataset.pdf_dir):
            print("Downloading PDFS...")
            args = base_args + ["-v", str(datastore_version), "-n", name + "-pdfs"]
            location = check_output(args).decode("utf-8").strip()
            if not isdir(location):
                raise ValueError("Got unexpected output from datastore cli: %s call: %s" % (location, " ".join(args)))
            move(location, dataset.pdf_dir)
            print("Done")
        else:
            print("PDF directory already exists")

        if not isdir(dataset.page_images_gray_dir):
            print("Downloading rasterized images...")
            args = base_args + ["-v", str(datastore_version), "-n", name + "-page-images-gray"]
            location = check_output(args).decode("utf-8").strip()
            if not isdir(location):
                raise ValueError("Got unexpected output from datastore cli: %s call: %s" % (location, " ".join(args)))
            move(location, dataset.page_images_gray_dir)
            print("Done")
        else:
            print("page_images_gray already exists")


if __name__ == "__main__":
    main()
