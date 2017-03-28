# Science Parse Command Line Interface

Science Parse has a command line interface called "RunSP". To build it into a super-jar, run `sbt cli/assembly`. This will download all dependencies and make a bundled jar that contains SP itself, plus all of its dependencies.

You can run it like this:
```
java -jar jarfile.jar --help
```
That will print all the command line options you can use with it. I won't describe all of them here, but there are a few important ones.

## Memory

Science Parse needs quite a lot of memory. We recommend you run it with at least 8GB of heap, like this:
```
java -Xmx8g -jar jarfile.jar 18bc3569da037a6cb81fb081e2856b77b321c139
```

## Specifying input

`RunSP` can parse multiple files at the same time. You can parse thousands of PDFs like this. It will try to parse as many of them in parallel as your computer allows.

`RunSP` takes input as positional parameters. Input can be any of the following:
 * S2 Paper ID (example: `java -Xmx8g -jar jarfile.jar 18bc3569da037a6cb81fb081e2856b77b321c139`). This will download the paper with the given ID from S2, and parse it.
 * PDF File (example: `java -Xmx8g -jar jarfile.jar paper.pdf`). This will parse the given PDF.
 * Directory (example: `java -Xmx8g -jar jarfile.jar my_directory/`). This will find all PDFs in that directory, and its subdirectories, and parse them.
 * Text file (example: `java -Xmx8g -jar jarfile.jar papers.txt`). Every line in the text file must be either an S2 Paper ID, a path to a PDF file, a path to a directory containing PDF files, or another text file that will be processed the same way.
