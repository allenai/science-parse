package org.allenai.scienceparse;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
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
import com.sun.media.jfxmedia.logging.Logger;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CRFBibRecordParser implements BibRecordParser {
  private CRFModel<String, String, String> model;
  public static final String DATA_VERSION = "0.1";
  
  public CRFBibRecordParser(CRFModel<String, String, String> inModel) {
      model = inModel;
  }
  
  public static List<Pair<String, String>> getLabeledLineUMass(String s) {
    final String [] sourceTags = new String [] {"address", "authors", "booktitle", "editor", "institution",
        "journal", "pages", "publisher", "tech", "thesis", "title", "volume", "year"}; //MUST BE SORTED
    final String [] destTags = new String [] {"address", ExtractedMetadata.authorTag, ExtractedMetadata.venueTag,
        "editor", "institution", ExtractedMetadata.venueTag, "pages", "publisher", "tech", ExtractedMetadata.venueTag, 
        ExtractedMetadata.titleTag, "volume", ExtractedMetadata.yearTag};

    //pull the ref marker
    s.replaceAll("<ref-marker>.*</ref-marker>", "");
    
    return labelAccordingToTags(s, sourceTags, destTags);
  }
  
  public static List<Pair<String, String>> labelAccordingToTags(
          String s,
          String[] sourceTags,
          String[] destTags
  ) {
    List<String> toks = tokenize(s);
    List<Pair<String, String>> out = new ArrayList<>();
    out.add(Tuples.pair("<S>", "<S>"));
    boolean atStart = false;
    int curTagIndex = -1; //the current source tag that we're in, and labeling for
    for(int i=0; i<toks.size(); i++) {
      String sTok = toks.get(i);
      if(sTok.endsWith(">")) {
        String t = sTok.replaceAll("<", "").replaceAll("/", "").replaceAll(">", "");
        int idx = Arrays.binarySearch(sourceTags, t);
        if(idx >= 0) { //ignore unescaped XML chars in bib
          if(sTok.startsWith("</"))
            curTagIndex = -1; //our tag closed
          else {
            curTagIndex = idx;
            atStart = true;
          }
        }
      } else { //write it out with proper tag
        String tag = "O";
        if(curTagIndex >= 0) {
          tag = destTags[curTagIndex];
          if(i<toks.size()-1) {
            if(toks.get(i+1).equals("</" + sourceTags[curTagIndex] + ">")) { //our tag ends on next word
              if(atStart) { //single-word span
                tag = "W_" + tag;
              } else {
                tag = "E_" + tag;
              }
            } else if(atStart) {
              tag = "B_" + tag;
            } else {
              tag = "I_" + tag;
            }
          }
        }
        out.add(Tuples.pair(sTok, tag));
        atStart = false;
      }
    }
    
    out.add(Tuples.pair("</S>", "</S>"));
    return out;
  }
  
  public static List<Pair<String, String>> getLabeledLineCora(String s) {
    final String [] sourceTags = new String [] {"author", "booktitle", "date", "editor", "institution",
        "journal", "location", "note", "pages", "publisher", "tech", "title", "volume"}; //MUST BE SORTED
    final String [] destTags = new String [] {ExtractedMetadata.authorTag, 
        ExtractedMetadata.venueTag, ExtractedMetadata.yearTag, "editor", "institution", 
        ExtractedMetadata.venueTag, "location", "note", "pages", "publisher", ExtractedMetadata.venueTag,
        ExtractedMetadata.titleTag, "volume"};
    
    return labelAccordingToTags(s, sourceTags, destTags);
  }
  
  public static List<Pair<String, String>> getLabeledLineKermit(String s) {
    final String [] sourceTags = new String [] {"author", "booktitle", "date", "editor", 
        "note", "pages", "publisher", "pubPlace", "title", "volume"}; //MUST BE SORTED
    final String [] destTags = new String [] {ExtractedMetadata.authorTag, 
        ExtractedMetadata.venueTag, ExtractedMetadata.yearTag, "editor", "note", "pages",  
        "publisher", "location", ExtractedMetadata.titleTag, "volume"};
    
    s = s.replaceAll("<title level=\"j\"> ([^<]+) </title>", "<booktitle> $1 </booktitle>");
    s = s.replaceAll("<title level=\"m\"> ([^<]+) </title>", "<booktitle> $1 </booktitle>");
    s = s.replaceAll("<title level=\"a\"> ([^<]+) </title>", "<title> $1 </title>");
    s = s.replaceAll("biblScope type=\"vol\" ([^<]+) </biblScope>", "<vol> $1 </vol>");
    s = s.replaceAll("biblScope type=\"pp\" ([^<]+) </biblScope>", "<pages> $1 </pages>");
    s = s.replaceAll("biblScope type=\"issue\" ([^<]+) </biblScope>", "<issue> $1 </issue>");
    
    return labelAccordingToTags(s, sourceTags, destTags);
  }
  
  public static List<List<Pair<String, String>>> labelFromCoraFile(File trainFile) throws IOException {
    return labelFromFile(trainFile, s->getLabeledLineCora(s));
  }
  
  public static List<List<Pair<String, String>>> labelFromUMassFile(File trainFile) throws IOException {
    return labelFromFile(trainFile, s-> getLabeledLineUMass(s));
  }
  
  public static List<List<Pair<String, String>>> labelFromKermitFile(File trainFile) throws IOException {
    return labelFromFile(trainFile, s-> getLabeledLineKermit(s));
  }
  
  
  public static List<List<Pair<String, String>>> labelFromFile(File trainFile, Function<String, List<Pair<String, String>>> gll) throws IOException {
    List<List<Pair<String, String>>> out = new ArrayList<>();
    BufferedReader brIn = new BufferedReader(new InputStreamReader(new FileInputStream(trainFile), "UTF-8"));
    String sLine;
    while((sLine = brIn.readLine())!=null) {
      List<Pair<String,String>> labeledLine = gll.apply(sLine);
      out.add(labeledLine);
    }
    brIn.close();
    return out;
  }
  
  private static List<String> tokenize(String line) { //tokenizes string for use in bib parsing
    //line = line.replaceAll("([a-z0-9])(,|\\.)", "$1 $2"); //break on lowercase alpha or num before . or ,
    String [] toks = line.split(" "); //otherwise not fancy
    return Arrays.asList(toks);
  }
  
  public BibRecord parseRecord(String line) {
    line = line.trim();
    if(line.isEmpty())
      return null;
    Matcher m = RegexWithTimeout.matcher(ExtractReferences.pBracket,  line);
    String citeRegEx = null;
    String shortCiteRegEx = null;
    if(m.matches()) {
      citeRegEx = m.group(1);
      shortCiteRegEx = citeRegEx;
      line = m.group(2);
    } else {
      m = RegexWithTimeout.matcher(ExtractReferences.pDot, line);
      if(m.matches()) {
        citeRegEx = m.group(1);
        shortCiteRegEx = citeRegEx;
        line = m.group(2);
      }
    }
    line = line.trim();
    if(line.isEmpty())
      return null;
    ArrayList<String> toks = new ArrayList<String>();
    toks.add("<S>");
    toks.addAll(tokenize(line));
    toks.add("</S>");
    List<String> labels;
    try{
      labels = model.bestGuess(toks);
    } catch(final Exception e) {
      return null;
    }
    labels = PDFToCRFInput.padTagSequence(labels);
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
    
    List<String> authors =
            author == null ?
                    null :
                    ExtractReferences.authorStringToList(author);
//    log.info("authors first extracted: " + ((authors==null)?"":authors.toString()));
//    log.info("year first extracted: " + year);
    
    int iYear = -1;
    if(year == null) { //backoff to any year-like string
      Matcher mY = RegexWithTimeout.matcher(ReferencesPredicateExtractor.yearPattern, line);
      while(mY.find()) {
        year = mY.group(1);
      }
    }
    if(year != null)
     iYear = ExtractReferences.extractRefYear(year);

    if(citeRegEx == null && year != null) {
      shortCiteRegEx = ExtractReferences.getCiteAuthorFromAuthors(authors);
      citeRegEx = shortCiteRegEx + ",? " + ((iYear > 0)?Pattern.quote(iYear + ""):"");
    }
    if(citeRegEx == null || shortCiteRegEx == null || title == null || authors == null || year == null)
      return null;
//    log.info("cite string " + citeRegEx);
    BibRecord brOut = null;
    try {
      brOut = new BibRecord(
        CRFBibRecordParser.cleanTitle(title),
        Parser.trimAuthors(authors),
        Parser.cleanTitle(venue),
        Pattern.compile(citeRegEx),
        Pattern.compile(shortCiteRegEx),
        iYear);
    } catch (final NumberFormatException e) {
      return null;
    }
    if(iYear==0) //heuristic -- if we don't find year, almost certainly we didn't extract correctly.
      return null;
//    log.info("returning " + brOut.toString());
    return brOut;
  }
  
  public static String cleanTitle(String title) {
    return title.replaceAll("^\\p{Pi}", "").
        replaceAll("(\\p{Pe}|,|\\.)$", "");
  }
}
