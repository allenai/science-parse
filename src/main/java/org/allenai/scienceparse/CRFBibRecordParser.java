package org.allenai.scienceparse;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.allenai.ml.linalg.DenseVector;
import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.StateSpace;
import org.allenai.ml.sequences.crf.CRFFeatureEncoder;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFWeightsEncoder;
import org.allenai.ml.util.IOUtils;
import org.allenai.ml.util.Indexer;
import org.allenai.scienceparse.ExtractReferences.BibRecordParser;
import org.allenai.scienceparse.ExtractedMetadata.LabelSpan;

import lombok.val;

public class CRFBibRecordParser implements BibRecordParser {
  private static CRFModel<String, String, String> model;
  public static final String DATA_VERSION = "0.1";
  
  public CRFBibRecordParser(String modelFile) throws Exception {
      try(
        final DataInputStream modelIs = new DataInputStream(new FileInputStream(modelFile));
      ) {
        model = loadModel(modelIs);
      }
   }

  public static CRFModel<String, String, String> loadModel(
    DataInputStream dis) throws Exception {
    IOUtils.ensureVersionMatch(dis, DATA_VERSION);
    val stateSpace = StateSpace.load(dis);
    Indexer<String> nodeFeatures = Indexer.load(dis);
    Indexer<String> edgeFeatures = Indexer.load(dis);
    Vector weights = DenseVector.of(IOUtils.loadDoubles(dis));
    ObjectInputStream ois = new ObjectInputStream(dis);
    ParserLMFeatures plf = (ParserLMFeatures) ois.readObject();
    val predExtractor = new ReferencesPredicateExtractor(plf);
    val featureEncoder = new CRFFeatureEncoder<String, String, String>
      (predExtractor, stateSpace, nodeFeatures, edgeFeatures);
    val weightsEncoder = new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());

    return new CRFModel<String, String, String>(featureEncoder, weightsEncoder, weights);
  }

  private static List<String> tokenize(String line) { //tokenizes string for use in bib parsing
    String [] toks = line.split(" "); //not fancy
    return Arrays.asList(toks);
  }
  
  public BibRecord parseRecord(String line) {
    List<String> toks = tokenize(line);
    List<String> labels = model.bestGuess(toks);
    List<LabelSpan> lss = ExtractedMetadata.getSpans(labels);
    String title = null;
    String author = null;
    for (LabelSpan ls : lss) {
      if (title == null && ls.tag.equals(ExtractedMetadata.titleTag)) { //only take the first title
        title = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      } else if (author == null && ls.tag.equals(ExtractedMetadata.authorTag)) {
        author = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      }
    }
    return null;
  }
}
