from datasets.build_dataset_images import get_images
from datasets.datasets import DATASETS

"""
This script checks for missing PDFs or missing rasterized images and re-downloads or regenerates
any that are missing. Unless a file was only partially downloaded it can be safely restarted if it crashes.
In practice this script is a bit unreliable since the URLs we have for PDFs might be incorrect.
"""

def setup():
    for name, dataset in sorted(DATASETS.items()):
        print("*" * 10 + " SETTING UP DATASET: %s" % name + " " + "*" * 10)
        dataset = dataset()
        print("DOWNLOADING PDFS:")
        dataset.fetch_pdfs()
        print("Done!")
        print("\nBUILDING GRAYSCALE IMAGES:")
        get_images(dataset.pdf_dir, dataset.page_images_gray_dir, dataset.image_dpi, True)
        print("Done!")
        print("\nBUILDING COLOR IMAGES:")
        get_images(dataset.pdf_dir, dataset.page_images_gray_dir, dataset.image_dpi, False)
        print("Done!")

if __name__ == "__main__":
    setup()
