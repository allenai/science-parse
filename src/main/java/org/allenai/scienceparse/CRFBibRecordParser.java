package org.allenai.scienceparse;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.allenai.ml.linalg.DenseVector;
import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.StateSpace;
import org.allenai.ml.sequences.crf.CRFFeatureEncoder;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFWeightsEncoder;
import org.allenai.ml.util.IOUtils;
import org.allenai.ml.util.Indexer;
import org.allenai.scienceparse.ExtractReferences.BibRecordParser;
import org.allenai.scienceparse.ExtractReferences.NamedYear;
import org.allenai.scienceparse.ExtractedMetadata.LabelSpan;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;

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

  public static List<Pair<String, String>> getLabeledLine(String s) {
    final String [] sourceTags = new String [] {"author", "booktitle", "date", "title"}; //MUST BE SORTED
    boolean [] inTag = new boolean [] {false, false, false, false};
    final String [] destTags = new String [] {ExtractedMetadata.authorTag, 
        ExtractedMetadata.venueTag, ExtractedMetadata.yearTag, ExtractedMetadata.titleTag};
    
    final Pattern yearPat= Pattern.compile("[1-2][0-9]{3}");
    String [] toks = s.split(" ");
    List<Pair<String, String>> out = new ArrayList<>();
    for(int i=0; i<toks.length; i++) {
      if(toks[i].endsWith(">")) {
        String t = toks[i].replaceAll("<", "").replaceAll("//", "").replaceAll(">", "");
        int idx = Arrays.binarySearch(sourceTags, t);
        if(idx >= 0) { //ignore unescaped XML chars in bib
           inTag[idx] = (toks[i].startsWith("</"))?false:true;
        }
      }
      else { //write it out with proper tag
        String tag = "O";
        for(int j=0; j<inTag.length; j++) {
          if(inTag[j]) {
            if(j==2) { //special case for date, we only want year
              if(RegexWithTimeout.matcher(yearPat, toks[i]).matches())
                tag = destTags[2];
            }
            else
              tag = destTags[j];
            break;
          }
        }
        Tuples.pair(toks[i], tag);
      }
    }
    return out;
  }
  
  public static List<List<Pair<String, String>>> labelFromCoraFile(File trainFile) throws Exception {
    List<List<Pair<String, String>>> out = new ArrayList<>();
    BufferedReader brIn = new BufferedReader(new InputStreamReader(new FileInputStream(trainFile), "UTF-8"));
    String sLine;
    while((sLine = brIn.readLine())!=null) {
      List<Pair<String,String>> labeledLine = getLabeledLine(sLine);
      out.add(labeledLine);
    }
    brIn.close();
    return out;
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
    line = line.trim();
    Pattern pBracket = Pattern.compile("\\[([0-9]+)\\](.*)");
    Pattern pDot = Pattern.compile("([0-9]+)\\.(.*)$");
    Matcher m = RegexWithTimeout.matcher(pBracket,  line);
    String citeRegEx = null;
    String shortCiteRegEx = null;
    if(m.matches()) {
      citeRegEx = m.group(1);
      shortCiteRegEx = citeRegEx;
      line = m.group(2);
    }
    else {
      m = RegexWithTimeout.matcher(pDot, line);
      if(m.matches()) {
        citeRegEx = m.group(1);
        shortCiteRegEx = citeRegEx;
        line = m.group(2);
      }
    }
    List<String> toks = tokenize(line);
    List<String> labels = model.bestGuess(toks);
    List<LabelSpan> lss = ExtractedMetadata.getSpans(labels);
    
    String title = null;
    String author = null;
    String venue = null;
    String year = null;
    for (LabelSpan ls : lss) {
      //TODO: figure out how to make this code less redundant
      if (title == null && ls.tag.equals(ExtractedMetadata.titleTag)) { //only take the first
        title = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      } else if (author == null && ls.tag.equals(ExtractedMetadata.authorTag)) {
        author = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      } else if (venue == null && ls.tag.equals(ExtractedMetadata.venueTag)) {
        venue = PDFToCRFInput.stringAtForStringList(toks, ls.loc); 
      } else if (year == null && ls.tag.equals(ExtractedMetadata.yearTag)) {
        year = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      }
    }
    List<String> authors = ExtractReferences.authorStringToList(author);
    if(citeRegEx == null) {
      shortCiteRegEx = NamedYear.getCiteAuthorFromAuthors(authors);
      citeRegEx = shortCiteRegEx + ",? " + year;
    }
    BibRecord brOut = null;
    try {
      brOut = new BibRecord(
          title, authors, venue, Pattern.compile(citeRegEx), Pattern.compile(shortCiteRegEx), Integer.parseInt(year));
    }
    catch (NumberFormatException e) {
      return null;
    }
    return brOut;
  }
}
