# Science Parse as a library

The most flexible way to use SP, but also the most complicated, is to use it as a library.

## Parsing documents

The main entry point into Science Parse is the [`Parser`](src/main/java/org/allenai/scienceparse/Parser.java) class. In Java, you can use it like this:
```Java
import org.allenai.scienceparse.Parser;
import org.allenai.scienceparse.ExtractedMetadata;

final Parser parser = Parser.getInstance();

// Parse without timeout
final ExtractedMetadata em = parser.doParse(inputStream);

// Parse with timeout
final ExtractedMetadata em = parser.doParseWithTimeout(inputStream, 30000);  // 30 second timeout
   // This might throw ParsingTimeout, which is a type of RuntimeException. 
```

This will attempt to parse the PDF in `inputStream` into an object of type [`ExtractedMetadata`](src/main/java/org/allenai/scienceparse/ExtractedMetadata.java), which looks like this:.

```Java
public class ExtractedMetadata {
  public Source source;
  public String title;
  public List<String> authors;
  public List<Section> sections;
  public List<BibRecord> references;
  public List<CitationRecord> referenceMentions;
  public int year;
  public String abstractText;
  public String creator; // program that created the PDF, i.e. LaTeX or PowerPoint or something else
  
  // ...
}
```

For more detail, we recommend diving into the code, or asking a question (create an issue).

## Training models

SP uses four model files:
 1. The general CRF model for title and authors
 2. The CRF model for bibliographies
 3. The gazetteer
 4. Word vectors
 
We provide defaults for these. During normal startup, SP will download all four and cache them locally.

You can also train your own. This README is too small to contain a full introduction to how to do this, but to get you started, check out these entry points:
 1. The general CRF model is created by the executable class [`org.allenai.scienceparse.Training`](src/main/scala/org/allenai/scienceparse/Training.scala). Run it with `sbt "core/runMain org.allenai.scienceparse.Training --help"` to see all the options it supports. If you specify nothing but an output location, it will train itself with the same parameters that were used to create the default models.
 2. The biliography CRF model is created by the executable class [`org.allenai.scienceparse.BibTraining`](src/main/scala/org/allenai/scienceparse/BibTraining.scala). Run it with `sbt "core/runMain org.allenai.scienceparse.BibTraining --help"` to see all the options it supports. As with the general model, if you specify nothing but an output location, it will train itself with the same parameters that were used to create the default models.
 3. You can specify a custom gazetteer as well, but the gazetteer is currently not used at runtime, only during training. To experiment with it, download the default gazetteer for a peek at the format. It's an uncomplicated JSON format.
 4. We never experimented with different vectors. There is currently no way to change the ones we provide.
 
 The SP Server, the SP CLI, and the evaluation code (see below) can be instructed to load other models than the default ones from the command line. Run any of them with `--help` to see details.
 
 Abstract extraction is purely rule-based, and not part of any of these models. Section extraction comes from the `pdffigures2` project, which SP depends on. Both of these are unaffected by any changes to the models.
 
 ## Evaluating models
 
There are several ways to evaluate changes to the models and rules. These are my two favorites:
 * `sbt "core/runMain org.allenai.scienceparse.LabeledDataEvaluation --compareAgainstGold"`: This evaluates SP against gold-annotated documents from the Computer Science domain.
 * `sbt "core/runMain org.allenai.scienceparse.LabeledDataEvaluation --compareAgainstPMC"`: This evaluates SP against documents from [PubMed Central](https://www.ncbi.nlm.nih.gov/pmc/). They are mostly from the medical domain. PMC documents are accompanied by a rich XML structure describing the contents of the PDFs in detail. It is a great source of labeled data for both evaluation and training. Note that this will download several gigabytes of PDFs before it begins the evaluation. As always, those gigabytes are cached and don't have to be downloaded a second time.
 
To see other options, run sbt "core/runMain org.allenai.scienceparse.LabeledDataEvaluation --help".
 
PDF parsing is different from platform to platform, and can depend even on the locally installed packages. As such, we see different training and evaluation performance for the same code, when run on different machines. To keep things consistent, we do all our test runs on Ubuntu 14.04.5.
