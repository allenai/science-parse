import json
from time import strftime
from subprocess import call, DEVNULL, check_output
from shutil import which
from os import listdir, mkdir, remove, environ
from os.path import isdir, join, isfile
from pdffigures_utils import Figure, FigureType, str_to_fig_type
import random
import string


class ScalaFigures(object):
    """
    The new scala based extractor. Requires environment variable "FIGURE_EXTRACTOR_HOME" to point
    towards the home directory of the figure extractor. For example:
    FIGURE_EXTRACTOR_HOME=/Users/chris/scholar/offline/figure-extractor
    """

    NAME = "scalafigures"
    SCRATCH_DIR = "/tmp/__scala_figures"

    def __init__(self):
        if not isdir(self.SCRATCH_DIR):
            mkdir(self.SCRATCH_DIR)
        time_str = strftime("%Y-%m-%d-%H-%M")
        random_string = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(10))
        self.scratch_dir = join(self.SCRATCH_DIR, time_str + "_" + random_string)
        if not isdir(self.scratch_dir):
            mkdir(self.scratch_dir)
        for line in listdir(self.scratch_dir):
            remove(join(self.scratch_dir, line))
        if "FIGURE_EXTRACTOR_HOME" not in environ:
            raise ValueError("Enviroment variable FIGURE_EXTRACTOR_HOME must point to figure extractor source," +
                             " ex. /scholar/offline/figure-extractor")
        target = join(environ["FIGURE_EXTRACTOR_HOME"], "target", "scala-2.11")
        if not isdir(target):
            raise ValueError("No target directory found (figure extractor not compiled?)" % target)

        jars = [x for x in listdir(target) if x.endswith("jar") and "assembly" in x]
        if len(jars) == 0:
            raise ValueError("No jar found in %s (figure extractor not compiled?)" % target)
        if len(jars) > 1:
            raise ValueError("Multiple assembly jar found")
        self.jar = join(target, jars[0])

    def get_config(self):
        pass

    def get_version(self):
        # Deduce version from the JAR name
        return self.jar.split("-")[-1][:-4]

    def start_batch(self, pdf_filenames):
        args = ["java", "-jar", self.jar, ",".join(pdf_filenames), "-c", "-d", self.scratch_dir + "/", "-e", "-q"]
        exit_code = call(" ".join(args), shell=True)
        if exit_code != 0:
            raise ValueError("Non-zero exit status %d, call:\n%s" % (exit_code,
                                                                     " ".join(args)))

    def get_extractions(self, pdf_filepath, dataset, doc_id):
        output_file = self.scratch_dir + "/" + doc_id + ".json"
        figs = []
        if isfile(output_file):
            with open(output_file) as f:
                loaded_figs = json.load(f)
            for fig in loaded_figs["figures"] + loaded_figs["regionless-captions"]:
                if "regionBoundary" in fig:
                    caption = fig["caption"]
                    bb = fig["regionBoundary"]
                    region_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                    bb = fig["captionBoundary"]
                    caption_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                else:
                    bb = fig["boundary"]
                    caption_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                    caption = fig["text"]
                    region_bb = None
                # For some reason (maybe a text height issue in PDFBox?) the caption bounding box
                # is consistently just a little too small relative to our annotated caption bounding box.
                # It seems fair to account for this by fractionally expanding the returned bounding box
                caption_bb[1] -= 3
                caption_bb[0] -= 3
                caption_bb[2] += 3
                caption_bb[3] += 3
                figs.append(Figure(
                    figure_type=str_to_fig_type(fig["figType"]),
                    name=fig["name"],
                    page=fig["page"] + 1,
                    dpi=72.0,
                    caption=caption,
                    caption_bb=caption_bb,
                    region_bb=region_bb))
        return figs


class PDFFigures(object):
    """
    The original C++ based pffigures program. Requires the CLI tool `pdffigures` to be in PATH
    """

    NAME = "pdffigures"
    SCRATCH_DIR = "/tmp/__pdffigures_extractor__"

    def __init__(self):
        # Randomize so we will not over-write each other if two are being used
        self.output_file = join(self.SCRATCH_DIR,
                                "output_" + ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(20)))
        if which("pdffigures") is None:
            raise ValueError("Could not find executable for `pdffigures`")
        if not isdir(self.SCRATCH_DIR):
            mkdir(self.SCRATCH_DIR)

    def get_config(self):
        pass

    def get_version(self):
        return check_output(["pdffigures", "--version"]).decode("UTF-8").strip()

    def start_batch(self, pdf_filenames):
        pass

    def get_extractions(self, pdf_filepath, dataset, doc_id):
        # Shell out to pdffigures, the read in JSON output and use it to build `Figure` objects
        if isfile(self.output_file + ".json"):
            remove(self.output_file + ".json")
        args = ["pdffigures", "-i", "-m", "-j", self.output_file, pdf_filepath]
        call(args, stderr=DEVNULL, stdout=DEVNULL)
        extractions = []
        with open(self.output_file + ".json") as f:
            figure_data = json.load(f)
        for data in figure_data:
            if data["Type"][0] == "F":
                fig_type = FigureType.figure
            elif data["Type"][0] == "T":
                fig_type = FigureType.table
            else:
                raise ValueError()
            fig = Figure(
                figure_type=fig_type,
                name=str(data["Number"]),
                region_bb=data["ImageBB"],
                caption_bb=data["CaptionBB"],
                caption=data["Caption"],
                page=int(data["Page"]),
                page_height=data["Height"],
                page_width=data["Width"],
                dpi=data["DPI"],
                )
            extractions.append(fig)
        return extractions


EXTRACTORS = {
  PDFFigures.NAME: PDFFigures,
  ScalaFigures.NAME: ScalaFigures
}

def get_extractor(name):
    return EXTRACTORS[name]()
