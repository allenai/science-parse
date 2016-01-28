import datasets
import sys
from PIL import Image
from os.path import isfile
from pdffigures_utils import crop_to_foreground, get_pdf_text, Figure, FigureType, fig_type_to_str, get_num_pages_in_pdf
import json
import argparse
from collections import Counter

"""
Script that converts that raw annotations in a dataset to proper annotations,
save the annotations. Annotations are in the format
returned by the AI2 Diagram annotation tool.
"""

def sanity_check(doc_id, doc_annotations):
    fig_nums = []
    table_nums = []
    all_names = []
    for annotation in doc_annotations:
        fig = Figure.from_dict(annotation)
        all_names.append("%s %s" % (fig_type_to_str(fig.figure_type), fig.name))
        try:
            num = int(fig.name)
        except ValueError:
            print("Non-number figure name %s on doc %s" % (fig.name, doc_id))
            continue
        if fig.figure_type == FigureType.table:
            table_nums.append(num)
        else:
            fig_nums.append(num)
    if len(fig_nums) > 0:
        max_fig = max(fig_nums)
        num_figures = len(set(fig_nums))
#        if max_fig != num_figures:
#            print(("WARNING: On doc %s max fig number %d but %d " +
#                   "figures") % (doc_id, max_fig, num_figures))

    if len(table_nums) > 0:
        max_table = max(table_nums)
        num_tables = len(set(table_nums))
#        if max_table != num_tables:
#            print(("WARNING: On doc %s max table number %d but %d " +
#                   "tables") % (doc_id, max_table, num_tables))
    duplicates = [item for item, count in Counter(all_names).items() if count > 1]
    if len(duplicates) != 0:
        print("WARNING: Duplicate names for doc %s: %s" % (doc_id, str(sorted(duplicates))))

def convert_annotations(raw_annotations, annotation_dpi,
                        gray_image_map, pdf_file_map, pages_annotated_map):
    if "timestamp" in raw_annotations:
        del raw_annotations["timestamp"]
    all_annotations = {}
    for image_file,annotations in raw_annotations.items():
        doc_id, page = image_file[:image_file.rfind(".")].split("-page-")
        page = int(page)
        if doc_id not in all_annotations:
            all_annotations[doc_id] = {}

        doc_annotations = all_annotations[doc_id]

        # Load the grayscale image, convert it to black and white to be used
        # when clipping the annotations to the foreground
        gray_image_filepath = gray_image_map[doc_id][page]
        gray_img = Image.open(gray_image_filepath)
        annotation_w, annotation_h = gray_img.size
        img = gray_img.convert('L').point(lambda x: 0 if x<200 else 255, '1')
        gray_img.close()

        for annotation in annotations:
            if "text" not in annotation:
                raise ValueError("Doc %s-%d annotaiton %s has not text field" % (doc_id, page, annotation))
            text = annotation["text"]
            if text.endswith("C"):
                name = text[1:-1]
            else:
                name = text[1:]
            if text[0] == "T":
                fig_type = "Table"
            elif text[0] == "F":
                fig_type = "Figure"
            else:
                raise ValueError("Unexpected text %s on doc %s\n%s" % (text, doc_id, annotation))

            figure_id = (fig_type, name, page)

            is_caption = text.endswith("C")
            bounds = annotation["bounds"]["coords"]
            x1, y1 = bounds[0]["x"], bounds[0]["y"]
            x2, y2 = bounds[1]["x"], bounds[1]["y"]

            bbox = crop_to_foreground([x1, y1, x2, y2], img)
            if bbox is None:
                raise ValueError("%s page %d %s %s, box %s was empty?" % (doc_id, page, fig_type, name, [x1, y1, x2, y2]))

            if figure_id not in doc_annotations:
                doc_annotations[figure_id] = {}
            figure = doc_annotations[figure_id]
            if is_caption:
                if "caption_bb" in figure:
                    raise ValueError("%s %s has two caption boxes?\n(%s)" % (
                            doc_id, name, annotation))
                figure["caption_bb"] = bbox
                figure["caption"] = get_pdf_text(pdf_file_map[doc_id], page, bbox, annotation_dpi, 2)
            else:
                if "region_bb" in figure:
                    if "region_bb2" in figure:
                        raise ValueError()
                    print("%s %s has two image boxes (%d %d)?" % (doc_id, name, figure["page"], page))
                    figure["region_bb2"] = bbox
                figure["region_bb"] = bbox
            figure["page"] = page
            figure["doc_id"] = doc_id
            figure["name"] = name
            figure["figure_type"] = fig_type
            figure["page_height"] = annotation_h
            figure["page_width"] = annotation_w
            figure["dpi"] = annotation_dpi

    # Make sure we got a caption and region box for each annotation
    for doc, doc_annotations in all_annotations.items():
        for _, figure in doc_annotations.items():
            if "caption_bb" not in figure:
                raise ValueError("Missing caption for %s %d" % (doc, figure["page"]))
            if "region_bb" not in figure:
                raise ValueError("Missing region for %s %d" % (doc, figure["page"]))
        sanity_check(doc, doc_annotations.values())
        all_annotations[doc] = list(doc_annotations.values())
    return all_annotations

def main():
    parser = argparse.ArgumentParser(description='Build annotations')
    parser.add_argument("dataset",
                        choices=list(datasets.DATASETS.keys()))
    parser.add_argument("raw_annotations")
    parser.add_argument("output_filename")
    parser.add_argument("-t", "--test-on", help="Test building annotations for " +
                        "a particular document and print the results")
    args = parser.parse_args()

    if not isfile(args.raw_annotations):
        raise ValueError()
    with open(args.raw_annotations) as f:
        raw_annotations = json.load(f)

    dataset = datasets.get_dataset(args.dataset)
    dpi = dataset.IMAGE_DPI
    doc_ids_in_annotations = set()
    for doc in raw_annotations.keys():
        if doc.endswith(".jpg"):
            doc_id = doc[:doc.rfind("-page")]
            doc_ids_in_annotations.add(doc_id)

    gray_image_file_map = dataset.get_gray_image_file_map()
    pdf_file_map = dataset.get_pdf_file_map()
    pages_annotated_map = dataset.get_annotated_pages_map()
    if args.test_on is None:
        if isfile(args.output_filename):
            raise ValueError("%s already exists" % args.output_filename)

        all_annotations = convert_annotations(raw_annotations, dpi, gray_image_file_map, pdf_file_map, pages_annotated_map)

        # Add empty annotation for any missing docs
        print("No annotations for " +
              str(set(dataset.get_doc_ids("all")) - doc_ids_in_annotations) +
              " (because no figures are present?), adding empty annotations")
        docs = dataset.get_doc_ids("all")
        for doc in docs:
            if pages_annotated_map is not None:
                pages = pages_annotated_map[doc]
            else:
                pages = list(range(1, get_num_pages_in_pdf(pdf_file_map[doc]) + 1))
            annotations = all_annotations.get(doc, [])
            all_annotations[doc] = dict(figures=annotations, pages_annotated=pages)

        with open(args.output_filename, "w") as f:
            json.dump(all_annotations, f, indent=2, sort_keys=True)

    else:
        raw_annotations = {k:v for (k,v) in raw_annotations.items() if k.startswith(args.test_on)}
        all_annotations = convert_annotations(raw_annotations, dpi, gray_image_file_map, pdf_file_map)
        print(all_annotations[args.test_on])

if __name__ == "__main__":
    main()
