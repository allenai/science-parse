package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.allenai.ml.linalg.DenseVector;
import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.StateSpace;
import org.allenai.ml.sequences.crf.CRFFeatureEncoder;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFWeightsEncoder;
import org.allenai.ml.util.IOUtils;
import org.allenai.ml.util.Indexer;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

@Slf4j
public class ExtractReferences {

  public static final String authUnit = "\\p{Lu}[\\p{L}'`\\-]+";
  public static final String authOneName = authUnit + "(?: " + authUnit + ")?"; //space and repetition for things like De Mori
  public static final String authLastCommaInitial = authOneName + ", (?:\\p{Lu}\\.-? ?)+";
  public static final String authConnect = "(?:; |, |, and |; and | and )";

  public static final String authInitialsLast = "(?:\\p{Lu}\\.?(?:-| )?)+ " + authOneName;
  public static final String authInitialsLastList = authInitialsLast + "(?:" + authConnect + authInitialsLast + ")*";
  public static final String authPlain = authOneName + "(?:\\p{Lu}\\. )?" + authOneName;
  public static final String authPlainList = authPlain + "(?:(?:, and|,) (?:" + authPlain + "))*";

  //pattern for matching single author name, format as in Jones, C. M.
  public static final String authGeneral = "\\p{Lu}[\\p{L}\\.'`\\- ]+";
  public static final String authGeneralList = authGeneral + "(?:(?:; |, |, and |; and | and )" + authGeneral + ")*";

  // patterns for mentions
  private static final String numberOrRangeRe = "(?:[1-9][0-9]*(?: *- *[1-9][0-9]*)?)";
  private static final String separatorRe = "(?: *[,;|] *)";
  private static final String citeCharactersRe = numberOrRangeRe + "(?:" + separatorRe + numberOrRangeRe + ")*";
  public static final Pattern mentions = Pattern.compile(
      "(?:" + citeCharactersRe + ")|" +
      "(?:\\[" + citeCharactersRe + "\\])|" +
      "(?:\\(" + citeCharactersRe + "\\))"
  );

  private ArrayList<BibStractor> extractors = null;

  public static Pattern pBracket = Pattern.compile("\\[([0-9]+)\\](.*)");
  public static Pattern pDot = Pattern.compile("([0-9]+)\\.(.*)");
  
  CheckReferences cr;
  final CRFBibRecordParser bibCRFRecordParser;

  public static final String DATA_VERSION = "0.3-BIB";  // Faster serialization
  
  public ExtractReferences(String gazFile) throws IOException {
    this(new FileInputStream(gazFile));
  }

  public ExtractReferences(String gazFile, String bibModelFile) throws IOException {
    this(new FileInputStream(gazFile), new DataInputStream(new FileInputStream(bibModelFile)));
  }
  
  public ExtractReferences(final InputStream is) throws IOException {
    this(is, null);
  }

  public ExtractReferences(final InputStream is, final DataInputStream bibCRFModel) throws IOException {
    this(is, bibCRFModel, null);
  }

  public ExtractReferences(
      final InputStream is,
      final DataInputStream bibCRFModel,
      final InputStream gazCacheInputStream
  ) throws IOException {
    if(gazCacheInputStream != null) {
      try(final FSTObjectInput in = new FSTObjectInput(gazCacheInputStream)) {
        cr = (CheckReferences)in.readObject();
      } catch(final Exception e) {
        log.warn("Could not load gazetteer from cache. Loading it slowly instead.", e);
      }
    }

    if(cr == null)
      cr = new CheckReferences(is);

    extractors = new ArrayList<>();
    
    if(bibCRFModel != null) {
      final CRFModel<String, String, String> bibCRF = loadModel(bibCRFModel);
      bibCRFRecordParser = new CRFBibRecordParser(bibCRF);
      extractors.addAll(Arrays.asList(
          new NumberCloseParen(new Class [] {
              NumberCloseParenBibRecordParser.class,
              CRFBibRecordParser.class}),
          new BracketNumber(new Class [] {
              BracketNumberInitialsQuotedBibRecordParser.class,
              CRFBibRecordParser.class}),
          new NamedYear(new Class [] {
              NamedYearBibRecordParser.class,
              CRFBibRecordParser.class}),
          new NamedYear(new Class [] {
              NamedYearInParensBibRecordParser.class,
              CRFBibRecordParser.class}),
          new NumberDot(new Class [] {
              NumberDotYearParensBibRecordParser.class,
              CRFBibRecordParser.class}),
          new NumberDot(new Class [] {
              NumberDotAuthorNoTitleBibRecordParser.class,
              CRFBibRecordParser.class}),
          new NumberDot(new Class [] {
              NumberDotYearNoParensBibRecordParser.class,
              CRFBibRecordParser.class}),
          new BracketNumber(new Class [] {
              BracketNumberInitialsYearParensCOMMAS.class,
              CRFBibRecordParser.class}),
          new BracketNumber(new Class [] {
              BracketNumberBibRecordParser.class,
              CRFBibRecordParser.class}),
          new BracketName(new Class [] {
              BracketNameBibRecordParser.class,
              CRFBibRecordParser.class})
      ));
      extractors.addAll(Arrays.asList(
        new BracketNumber(new Class [] {CRFBibRecordParser.class}),
        new NumberDot(new Class [] {CRFBibRecordParser.class}),
        new NumberDotNaturalLineBreaks(new Class [] {CRFBibRecordParser.class}),
        new NamedYear(new Class [] {CRFBibRecordParser.class}),
        new BracketName(new Class [] {CRFBibRecordParser.class})));
    } else {
      bibCRFRecordParser = null;
      extractors.addAll(Arrays.asList(
          new BracketNumber(new Class [] {BracketNumberInitialsQuotedBibRecordParser.class}),
          new NamedYear(new Class [] {NamedYearBibRecordParser.class}),
          new NamedYear(new Class [] {NamedYearInParensBibRecordParser.class}),
          new NumberDot(new Class [] {NumberDotYearParensBibRecordParser.class}),
          new NumberDot(new Class [] {NumberDotAuthorNoTitleBibRecordParser.class}),
          new NumberDot(new Class [] {NumberDotYearNoParensBibRecordParser.class}),
          new BracketNumber(new Class [] {BracketNumberInitialsYearParensCOMMAS.class}),
          new BracketNumber(new Class [] {BracketNumberBibRecordParser.class}),
          new BracketName(new Class [] {BracketNameBibRecordParser.class})));
    }
  }

  public static ExtractReferences createAndWriteGazCache(
      final InputStream is,
      final DataInputStream bibCRFModel,
      final OutputStream gazCacheFileOutputStream
  ) throws IOException {
    val result = new ExtractReferences(is, bibCRFModel);

    final FSTObjectOutput out = new FSTObjectOutput(gazCacheFileOutputStream);
    out.writeObject(result.cr);

    return result;
  }

  public static CRFModel<String, String, String> loadModel(
    DataInputStream dis
  ) throws IOException {
    IOUtils.ensureVersionMatch(dis, ExtractReferences.DATA_VERSION);
    val stateSpace = StateSpace.load(dis);
    Indexer<String> nodeFeatures = Indexer.load(dis);
    Indexer<String> edgeFeatures = Indexer.load(dis);
    Vector weights = DenseVector.of(IOUtils.loadDoubles(dis));

    ParserLMFeatures plf = null;
    GazetteerFeatures gf = null;
    try(FSTObjectInput in = new FSTObjectInput(dis)) {
      try {
        plf = (ParserLMFeatures)in.readObject();
      } catch (final Exception e) {
        // do nothing
        // but now plf is NULL. Is that OK?
      }

      try {
        gf = (GazetteerFeatures)in.readObject();
      } catch (final Exception e) {
        log.info("Failed to load kermit gazetteer with this error:", e);
      }
      if (gf != null)
        log.info("kermit gazetteer successfully loaded.");
      else
        log.info("could not load kermit gazetter");
    }
      
    val predExtractor = new ReferencesPredicateExtractor(plf);
    predExtractor.setGf(gf);
    val featureEncoder =
        new CRFFeatureEncoder<String, String, String>(predExtractor, stateSpace, nodeFeatures, edgeFeatures);
    val weightsEncoder =
        new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());

    return new CRFModel<String, String, String>(featureEncoder, weightsEncoder, weights);
  }
  
  public static Pattern authStrToPat(String s) {
    if(s == null || s.length() == 0)
      s = "";
    return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
  }

  //returns pattern-ready form of author
  private static String cleanAuthString(String s) {
    return s.replaceAll("\\p{P}", ".");//allow anything for any punctuation
  }

  private static final Pattern yearPattern = Pattern.compile("[1-2][0-9][0-9][0-9]");
  public static int extractRefYear(String sYear) {
    final Matcher mYear = RegexWithTimeout.matcher(yearPattern, sYear);
    int a = 0;
    while (mYear.find()) {
      try {
        a = Integer.parseInt(mYear.group().trim());
      } catch (final NumberFormatException e) {
        // do nothing
      }
      if (a > BibRecord.MINYEAR && a < BibRecord.MAXYEAR)
        return a;
    }
    return a;
  }

  private static final Pattern firstNamesPattern =
      Pattern.compile(
          "\\p{javaUpperCase}{1,3}|" +
          "(\\p{javaUpperCase}\\.){1,3}|" +
          "(\\p{javaUpperCase}\\s+){0,2}(\\p{javaUpperCase})|" +
          "(\\p{javaUpperCase}\\.\\s+){0,2}(\\p{javaUpperCase}\\.)");
  /**
   * Takes in a string mentioning several authors, returns normalized list of authors
   *
   * @param authString
   * @return
   */
  public static List<String> authorStringToList(String authString) {
    authString = authString.trim();

    // remove punctuation at the end, all punctuation but .
    authString = authString.replaceAll("[\\p{Punct}&&[^.]]*$", "");

    // remove punctuation at the beginning
    authString = authString.replaceAll("^\\p{Punct}*", "");

    // remove "et al" at the end
    authString = authString.replaceAll("[\\s\\p{Punct}]*[eE][tT]\\s+[aA][lL].?$", "");

    // remove "etc" at the end
    authString = authString.replaceAll("[\\s\\p{Punct}]*[eE][tT][cC].?$", "");

    // find out the top-level separator of names
    final String mainSplitString;
    if(authString.contains(";"))
      mainSplitString = ";";
    else
      mainSplitString = ",";

    // replace "and" with the top level separator
    authString = authString.replaceAll("\\b[aA][nN][dD]\\b|&", mainSplitString);

    // split into names
    final String[] names = authString.split(mainSplitString);

    // clean up the names
    for(int i = 0; i < names.length; ++i) {
      names[i] = names[i].trim().
        // clean up names that start with punctuation
        replaceAll("^\\p{Punct}\\s+", "");
    }

    // Some look like this: "Divakaran, A., Forlines, C., Lanning, T., Shipman, S., Wittenburg, K."
    // If we split by comma, we need to make sure to glue these back together.
    if(mainSplitString.equals(",")) {
      for(int i = 1; i < names.length; ++i) {
        if(firstNamesPattern.matcher(names[i]).matches()) {
          names[i - 1] = names[i] + " " + names[i - 1];
          names[i] = "";  // We'll clean up empty strings later.
          i += 1;
        }
      }
    }

    // see if we have to reorder first and last names
    int invertAroundCommaCount = 0;
    int invertAroundSpaceCount = 0;
    int doNothingCount = 0;
    for(final String name : names) {
      if(name.isEmpty())
        continue;
      if(name.contains(",")) {
        invertAroundCommaCount += 1;
      } else {
        final String[] individualNames = name.split("\\s+");
        // If the last individual name looks like not-a-last-name, we assume we have to invert.
        if(firstNamesPattern.matcher(individualNames[individualNames.length - 1]).matches())
          invertAroundSpaceCount += 1;
        else
          doNothingCount += 1;
      }
    }

    // invert, if we have to
    if(invertAroundCommaCount > invertAroundSpaceCount && invertAroundCommaCount > doNothingCount) {
      // invert around comma
      for(int i = 0; i < names.length; ++i) {
        final String[] parts = names[i].split("\\s*,\\s*");
        if(parts.length == 2)
          names[i] = parts[1] + " " + parts[0];
      }
    } else if(invertAroundSpaceCount > invertAroundCommaCount && invertAroundSpaceCount > doNothingCount) {
      // invert around space, i.e., make Johnson M into M Johnson
      final StringBuilder b = new StringBuilder(128);
      for(int i = 0; i < names.length; ++i) {
        final String[] parts = names[i].split("\\s+");
        b.append(parts[parts.length - 1]);
        b.append(' ');
        for(int j = 0; j < parts.length - 1; ++j) {
          b.append(parts[j]);
          b.append(' ');
        }
        names[i] = b.toString().trim();
        b.setLength(0);
      }
    }

    // strip out empty strings
    return Arrays.asList(names).stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
  }

  private static <T> List<T> removeNulls(List<T> in) {
    return in.stream().filter(a -> (a != null)).collect(Collectors.toList());
  }

  private static String getAuthorLastName(String authName) {
    int idx = authName.lastIndexOf(" ");
    return authName.substring(idx + 1);
  }

  private static int refStart(List<String> paper) {
    for (int i = paper.size() / 3; i < paper.size(); i++) { //heuristic, assume refs start at least 1/3 into doc
      String s = paper.get(i);
      if (s.endsWith("References") || s.endsWith("Citations") || s.endsWith("Bibliography") || s.endsWith("Bibliographie") ||
        s.endsWith("REFERENCES") || s.endsWith("CITATIONS") || s.endsWith("BIBLIOGRAPHY") || s.endsWith("BIBLIOGRAPHIE"))
        return i;
      else if (s.contains("References<lb>") || s.contains("Citations<lb>") || s.contains("Bibliography<lb>") || s.contains("Bibliographie<lb>") ||
        s.contains("REFERENCES<lb>") || s.contains("CITATIONS<lb>") || s.contains("BIBLIOGRAPHY<lb>") || s.contains("BIBLIOGRAPHIE<lb>")) {
        return i - 1;
      }
    }
    return -1;
  }

  public static int getIdxOf(List<BibRecord> bib, String citeStr) {
    //note: slow
    for (int i = 0; i < bib.size(); i++) {
      if (bib.get(i).citeRegEx.matcher(citeStr).matches())
        return i;
    }
    return -1;
  }

  public static Pair<Integer, Integer> shortCiteSearch(int yearPos, int year, String s, List<BibRecord> bib) {
	  int start = -1;
	  int idx = -1;
	  int ct = 0;
	  for(BibRecord br : bib) {
		 Matcher m = RegexWithTimeout.matcher(br.shortCiteRegEx, s);
		 if(m.find()) {
			 //TODO: handle multiple matches
			 if(m.start() > yearPos)
				 continue;
			 start = m.start();
			 idx = ct;
			 break;
		 }
		 ct++;
	  }
	  return Tuples.pair(start, idx);
  }
  
  //note, also replaces <lb> with spaces in lines with found references
  public static List<CitationRecord> findCitations(List<String> paper, List<BibRecord> bib, BibStractor bs) {
    ArrayList<CitationRecord> out = new ArrayList<>();
    Pattern p = Pattern.compile(bs.getCiteRegex());
    Pattern pRange = Pattern.compile("([0-9]+)\\p{Pd}([0-9]+)");
    
    int stop = refStart(paper); //stop at start of refs
    if (stop < 0)
      stop = paper.size(); //start of refs not found (should never happen for non-null bibliography...)

    for (int i = 0; i < stop; i++) {
      String s = paper.get(i).replaceAll("-<lb>", "").replaceAll("<lb>", " ");
      paper.set(i, s);
      Matcher m = RegexWithTimeout.matcher(p, s);
      while (m.find()) {
        String citationMeat = m.group(1);
        if(citationMeat == null)
          citationMeat = m.group(2);
        String[] citations = citationMeat.split(bs.getCiteDelimiter());
        for (final String citation : citations) {
          Matcher mRange = RegexWithTimeout.matcher(pRange, citation);
          if(mRange.matches()) { //special case for ranges
              int st = Integer.parseInt(mRange.group(1));
              int end = Integer.parseInt(mRange.group(2));
              for(int j=st;j<=end;j++) {
                if(Thread.interrupted())
                  throw new Parser.ParsingTimeout();
                int idx = getIdxOf(bib, j + "");
                if (idx >= 0) {
                  out.add(new CitationRecord(idx, paper.get(i), m.start(), m.end()));
                }
              }
          }
          else {
            int idx = getIdxOf(bib, citation.trim());
            if (idx >= 0) {
              out.add(new CitationRecord(idx, paper.get(i), m.start(), m.end()));
            }
          }
        }
      }
      //short-cites are assumed to be e.g.: Etzioni et al. (2005)
      if(bs.getShortCiteRegex() != null) {
    	  Pattern p2 = Pattern.compile(bs.getShortCiteRegex());
    	  Matcher m2 = RegexWithTimeout.matcher(p2, s);
    	  while(m2.find()) {
    		  Pair<Integer, Integer> shct =
            shortCiteSearch(m2.start(), Integer.parseInt(m2.group(1).substring(0, 4)), s, bib);
    		  int start = shct.getOne();
    		  int idx = shct.getTwo();
    		  if(start > 0) {
    			  out.add(new CitationRecord(idx, paper.get(i), start, m2.end()+1));
    		  }
    	  }
      }
    }
    return out;
  }

  public int numFound(List<BibRecord> brs) {
    int i = 0;
    for (BibRecord br : brs) {
      if (cr.hasPaper(br.title, br.author, br.year, br.venue))
        i++;
    }
    return i;
  }

  public int longestIdx(List<BibRecord>[] results) {
    int maxLen = -1;
    int idx = -1;
    for (int i = 0; i < results.length; i++) {
      int f = 10000 * numFound(results[i]) + results[i].size(); //order by num found, then by size
      if (f > maxLen) {
        idx = i;
        maxLen = f;
      }
    }
//    log.info("chose " + idx + " with " + maxLen);
    return idx;
  }

  //heuristic
  public boolean refEnd(String s) {
    if (s.endsWith("Appendix") || s.endsWith("APPENDIX"))
        return true;
    else
      return false;
  }
  
  private static List<BibRecord> clean(final List<BibRecord> brs) {
    val result = new ArrayList<BibRecord>(brs.size());

    // remove punctuation at the beginning and end of the title
    for(final BibRecord b : brs) {
      final String newTitle =
          b.title.trim().replaceAll("^\\p{P}", "").replaceAll("[\\p{P}&&[^)]]$", "");
      if(
          !newTitle.isEmpty() &&      // delete empty titles
          newTitle.length() < 512 &&  // delete absurdly long bib entries
          (b.venue == null || b.venue.length() < 512) &&
          (b.author == null || b.author.stream().allMatch(a -> a.length() < 512))
      ) {
        result.add(b.withTitle(newTitle));
      }
    }

    return result;
  }
  
  /**
   * Returns the list of BibRecords, plus the extractor that produced them (in order to enable
   * citation parsing)
   */
  public Pair<List<BibRecord>, BibStractor> findReferences(List<String> paper) {
    int start = refStart(paper) + 1;
    List<BibRecord>[] results = new ArrayList[extractors.size()];
    for (int i = 0; i < results.length; i++)
      results[i] = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < paper.size(); i++) {
      if(refEnd(paper.get(i)))
        break;
      sb.append("<bb>");
      sb.append(paper.get(i));
    }
    String text = sb.toString();
    for (int i = 0; i < results.length; i++) {
      results[i] = extractors.get(i).parse(text);
      results[i] = clean(results[i]);
    }
    int idx = longestIdx(results);
    //log.info("references: " + results[idx].toString());
    return Tuples.pair(results[idx], extractors.get(idx));
  }

  public interface BibRecordParser {
    BibRecord parseRecord(String line);
  }

  public abstract class BibStractor {
    final BibRecordParser [] recParser;

    BibStractor(Class [] c) {
      BibRecordParser b = null;
      recParser = new BibRecordParser[c.length];
      for(int i=0; i<c.length; i++) {
        if(c[i] == CRFBibRecordParser.class) { //special case
          b = bibCRFRecordParser;
        } else {
          try {
            b = (BibRecordParser) c[i].newInstance();
          } catch (final Exception e) {
            log.warn("Exception while creating BibStractor", e);
          }
        }
        recParser[i] = b;
      }
    }

    BibStractor() {
      recParser = null;
    }
    
    public abstract List<BibRecord> parse(String source);

    public abstract String getCiteRegex();
    
    public String getShortCiteRegex() { return null; } //may return null for bibstractors without short cites

    public abstract String getCiteDelimiter();
  }

  private static class DefaultBibRecordParser implements BibRecordParser {
    public BibRecord parseRecord(String line) {
      return new BibRecord(line, null, null, null, null, 0);
    }
  }
  
  private static class BracketNumberInitialsQuotedBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "\\[([0-9]+)\\] (.*)(?:,|\\.|:) [\\p{Pi}\"\']+(.*),[\\p{Pf}\"\']+ (?:(?:I|i)n )?(.*)\\.?";
    private static final Pattern pattern = Pattern.compile(regEx);

    public BracketNumberInitialsQuotedBibRecordParser() {
    }

    //example:
    //	"[1] E. Chang and A. Zakhor, “Scalable video data placement on parallel disk "
    //+ "arrays," in IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
    //+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221."
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(4)));
      } else {
        return null;
      }
    }
  }

  private static class BracketNumberBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "\\[([0-9]+)\\] (" + authInitialsLastList + ")\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*) ([1-2][0-9]{3})\\.( .*)?";
    private static final Pattern pattern = Pattern.compile(regEx);

    public BracketNumberBibRecordParser() {
    }

    //example:
    //TODO
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(5)));
      } else {
        return null;
      }
    }
  }

  // Example:
  // 6) Farrell GC, Larter CZ. Nonalcoholic fatty liver disease: from steatosis to cirrhosis.
  //    Hepatology 2006;43(Suppl. 1):S99-S112.
  private static class NumberCloseParenBibRecordParser implements BibRecordParser {
    private static final String uppercaseNameUnit = authUnit;
    private static final String anycaseNameUnit = "[\\p{L}'`\\-]+";
    private static final String multipleNameUnits = "(?:" + anycaseNameUnit + " +)*" + uppercaseNameUnit;
    private static final String initialsLastAuthor = multipleNameUnits + " +\\p{Lu}+";
    private static final String multipleInitialsLastAuthors =
        initialsLastAuthor + "(?:" + authConnect + initialsLastAuthor + ")*";

    private static final String regEx = (
        "([0-9]+)\\)" +                       // "6)"
        " +" +
        "(" + multipleInitialsLastAuthors + ")(?:, et al)?\\." + // "Farrell GC, Larter CZ."
        " +" +
        "([^\\.]+[\\.\\?])" +                 // "Nonalcoholic fatty liver disease: from steatosis to cirrhosis."
        " +" +
        "(.*) +([1-2][0-9]{3});" +            // "Hepatology 2006;"
        ".*");                                // "43(Suppl. 1):S99-S112."
    private static final Pattern pattern = Pattern.compile(regEx);

    public NumberCloseParenBibRecordParser() {
    }

    public BibRecord parseRecord(final String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        String title = m.group(3);
        if(title.endsWith("."))
          title = title.substring(0, title.length() - 1);
        return new BibRecord(
            title,
            authorStringToList(m.group(2)),
            m.group(4),
            Pattern.compile(m.group(1)), null,
            extractRefYear(m.group(5)));
      } else {
        return null;
      }
    }
  }

  static class NumberDotAuthorNoTitleBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "([0-9]+)\\. +(" + authLastCommaInitial + "(?:; " + authLastCommaInitial + ")*)" + " ([^0-9]*) ([1-2][0-9]{3})(?:\\.|,[0-9, ]*)(?:.*)";
    private static final Pattern pattern = Pattern.compile(regEx);

    public NumberDotAuthorNoTitleBibRecordParser() {
    }

    //example:
    //1. Jones, C. M.; Henry, E. R.; Hu, Y.; Chan C. K; Luck S. D.; Bhuyan, A.; Roder, H.; Hofrichter, J.;
    //Eaton, W. A. Proc Natl Acad Sci USA 1993, 90, 11860.
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        return new BibRecord(
          "",
          authorStringToList(m.group(2)),
          m.group(3),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(4)));
      } else {
        return null;
      }
    }
  }

  private static class NumberDotYearParensBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "([0-9]+)\\. ([^:]+): ([^\\.]+)\\. (?:(?:I|i)n: )?(.*) \\([^)]*([0-9]{4})\\)\\.?(?: .*)?"; //last part is for header break
    private static final Pattern pattern = Pattern.compile(regEx);

    public NumberDotYearParensBibRecordParser() {
    }

    //example:
    //TODO
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(5)));
      } else {
        return null;
      }
    }
  }

  private static class NumberDotYearNoParensBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "([0-9]+)\\. (" + authInitialsLastList + "). ([^\\.]+)\\. (?:(?:I|i)n: )?(.*) ([1-2][0-9]{3}).( .*)?"; //last part for header break
    private static final Pattern pattern = Pattern.compile(regEx);

    public NumberDotYearNoParensBibRecordParser() {
    }

    //example:
    //TODO
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(5)));
      } else {
        return null;
      }
    }
  }

  private static class NamedYearBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "(" + authGeneralList + ") +([1-2][0-9]{3}[a-z]?)\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*)\\.?";
    private static final Pattern pattern = Pattern.compile(regEx);

    public NamedYearBibRecordParser() {
    }
    
    //example:
    //STONEBREAKER, M. 1986. A Case for Shared Nothing. Database Engineering 9, 1, 4–9.
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        final List<String> authors = authorStringToList(m.group(1));
        final int year = Integer.parseInt(m.group(2).substring(0, 4));
        final String nameStr = getCiteAuthorFromAuthors(authors);
        final String citeStr = nameStr + ",? " + m.group(2);
        return new BibRecord(m.group(3), authors, m.group(4), authStrToPat(citeStr), authStrToPat(nameStr), year);
      } else {
        return null;
      }
    }

  }

  private static class NamedYearInParensBibRecordParser implements BibRecordParser {
    private static final String regEx =
      "(" + authGeneralList + ") +\\(([1-2][0-9]{3}[a-z]?)\\)\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*)\\.?";
    private static final Pattern pattern = Pattern.compile(regEx);

    public NamedYearInParensBibRecordParser() {
    }

    //example:
    //STONEBREAKER, M. 1986. A Case for Shared Nothing. Database Engineering 9, 1, 4–9.
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern, line.trim());
      if (m.matches()) {
        List<String> authors = authorStringToList(m.group(1));
        int year = Integer.parseInt(m.group(2).substring(0, 4));
        String nameStr = getCiteAuthorFromAuthors(authors);
        String citeStr = nameStr + ",? " + m.group(2);
        return new BibRecord(m.group(3), authors, m.group(4), authStrToPat(citeStr), authStrToPat(nameStr), year);
      } else {
        return null;
      }
    }
  }

  private static class BracketNumberInitialsYearParensCOMMAS implements BibRecordParser {
    private static final String regEx1 =
      "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*)\\(.*([1-2][0-9]{3}).*\\).*";
    private static final Pattern pattern1 = Pattern.compile(regEx1);
    private static final String regEx2 =
      "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*), ((?:20|19)[0-9]{2})(?:\\.|,).*";
    private static final Pattern pattern2 = Pattern.compile(regEx2);

    public BracketNumberInitialsYearParensCOMMAS() {
    }

    //example:
    // [1] S. Abiteboul, H. Kaplan, and T. Milo, Compact labeling schemes for ancestor queries. Proc. 12th Ann. ACM-SIAM Symp.
    // on Discrete Algorithms (SODA 2001), 547-556.
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern1, line.trim());
      Matcher m2 = RegexWithTimeout.matcher(pattern2, line.trim());
      if (m.matches()) {
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          Pattern.compile(m.group(1)), null,
          extractRefYear(m.group(5)));
      } else if (m2.matches()) {
        return new BibRecord(
          m2.group(3),
          authorStringToList(m2.group(2)),
          m2.group(4),
          Pattern.compile(m2.group(1)), null,
          extractRefYear(m2.group(5)));
      } else {
        return null;
      }
    }
  }


  
  private static class BracketNameBibRecordParser implements BibRecordParser {
    private static final String regEx1 =
      "\\[([^\\]]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*), ([1-2][0-9]{3})\\.";
    private static final Pattern pattern1 = Pattern.compile(regEx1);
    private static final String regEx2 =
      "\\[([^\\]]+)\\] (" + authGeneralList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*),? ([1-2][0-9]{3})\\..*";
    private static final Pattern pattern2 = Pattern.compile(regEx2);

    public BracketNameBibRecordParser() {
    }

    //example:
    // [Magnini et al., 2002] B. Magnini, M. Negri, R. Prevete, and
    // H. Tanev. Is it the right answer? exploiting web redundancy
    //   for answer validation. In ACL, 2002.
    public BibRecord parseRecord(String line) {
      Matcher m = RegexWithTimeout.matcher(pattern1, line.trim());
      Matcher m2 = RegexWithTimeout.matcher(pattern2, line.trim());
      if (m.matches()) {
        if (m.group(1).matches("[0-9]+")) //don't override BracketNumber
          return null;
        return new BibRecord(
          m.group(3),
          authorStringToList(m.group(2)),
          m.group(4),
          authStrToPat(cleanAuthString(m.group(1))), null,
          extractRefYear(m.group(5)));
      } else if (m2.matches()) {
        if (m2.group(1).matches("[0-9]+")) //don't override BracketNumber
          return null;
        return new BibRecord(
          m2.group(3),
          authorStringToList(m2.group(2)),
          m2.group(4),
          authStrToPat(cleanAuthString(m2.group(1))), null,
          extractRefYear(m2.group(5)));
      } else {
        return null;
      }
    }
  }

  //in regex form
  public static String getCiteAuthorFromAuthors(List<String> authors) {
    if(authors == null || authors.size()==0)
      return null;
    if (authors.size() > 2) {
      return cleanAuthString(getAuthorLastName(authors.get(0))) + " et al\\.";
    } else if (authors.size() == 1) {
      return cleanAuthString(getAuthorLastName(authors.get(0)));
    } else if (authors.size() == 2) {
      return cleanAuthString(getAuthorLastName(authors.get(0))) + " and " + cleanAuthString(getAuthorLastName(authors.get(1)));
    }
    return null;
  }

  public class NamedYear extends BibStractor {
    private final static String citeRegex =
      "(?:\\[|\\()([^\\[\\(\\]\\)]+ [1-2][0-9]{3}[a-z]?)+(?:\\]|\\))";
    private final static String shortCiteRegex = "(?:\\[|\\()([1-2][0-9]{3}[a-z]?)(?:\\]|\\))";
    private final static String citeDelimiter = "; ?";

    NamedYear(Class [] c) {
      super(c);
    }

    public String getCiteRegex() {
      return citeRegex;
    }

    @Override
    public String getShortCiteRegex() {
    	return shortCiteRegex;
    }
    
    public String getCiteDelimiter() {
      return citeDelimiter;
    }

    public List<BibRecord> parse(String line) {
      if (line.startsWith("<bb>"))
        line = line.substring(4);
      String[] citesa = line.split("<bb>");
      List<String> cites = Arrays.asList(citesa);
      List<BibRecord> out = new ArrayList<BibRecord>();
      boolean first = true;
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ").trim();
        if(first) { //don't overwrite number-bracket or number-dot
          if(s.length() > 0)
              if(RegexWithTimeout.matcher(pBracket, s).matches() ||
            RegexWithTimeout.matcher(pDot, s).matches()) {
                return removeNulls(out);
              }
              else {
                first = false;
              }
        }
        for(int i=0; i<recParser.length;i++) {
          BibRecord br = this.recParser[i].parseRecord(s);
          if(br!=null) {
            out.add(br);
            break;
          }
        }
      }
      out = removeNulls(out);
      return out;
    }
  }

  private class NumberDotNaturalLineBreaks extends NumberDot {

    NumberDotNaturalLineBreaks(Class [] c) {
      super(c);
    }
    
    @Override
    public List<BibRecord> parse(String line) {
      return parseWithGivenBreaks(line, true);
    }
    
  }
  
  private class NumberDot extends BracketNumber {
    NumberDot(Class [] c) {
      super(c);
    }
    
    protected List<BibRecord> parseWithGivenBreaks(String line, boolean bigBreaks) {
      if(!bigBreaks)
        line = line.replaceAll("<bb>", "<lb>");
      else
        line = line.replaceAll("<lb>", " ");
      int i = 0;
      String preTag = "<bb>";
      if(!bigBreaks)
        preTag = "<lb>";
      String tag = preTag + (++i) + ". ";
      List<String> cites = new ArrayList<String>();
      int st = line.indexOf(tag);
      while (st >= 0) {
        tag = preTag + (++i) + ". ";
        int end = line.indexOf(tag, st);
        if (end > 0) {
          cites.add(line.substring(st, end));
        } else {
          cites.add(line.substring(st));
        }
        st = end;
      }
      List<BibRecord> out = new ArrayList<BibRecord>();
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ");
        s = s.replaceAll("-<bb>", "").replaceAll("<bb>", " ");
        for(int j=0; j<recParser.length;j++) {
          BibRecord br = this.recParser[j].parseRecord(s);
          if(br!=null) {
            out.add(br);
            break;
          }
        }
      }
      out = removeNulls(out);
      return out;
    }      
    
    public List<BibRecord> parse(String line) {
      return parseWithGivenBreaks(line,  false);
    }
  }

  private class BracketNumber extends BibStractor {
    protected final String citeRegex =
        "(?:" +
          "[\\[\\(]" +                   // open bracket/paren
          "(" + citeCharactersRe + ")" + // the meat
          "[\\]\\)]" +                   // close bracket/paren
        ")|(?:" +                        // or
          "\\.⍐" +                       // period, followed by superscript
          "(" + citeCharactersRe + ")" + // the meat
          "⍗" +                          // end of superscript
        ")";
    protected final String citeDelimiter = separatorRe;

    BracketNumber(Class[] c) {
      super(c);
    }

    public String getCiteRegex() {
      return citeRegex;
    }
    
    public String getCiteDelimiter() {
      return citeDelimiter;
    }

    public List<BibRecord> parse(String line) {
      line = line.replaceAll("<bb>", "<lb>");
      int i = 0;
      String tag = "[" + (++i) + "]";
      List<String> cites = new ArrayList<String>();
      int st = line.indexOf(tag);
      while (st >= 0) {
        tag = "<lb>[" + (++i) + "]";
        int end = line.indexOf(tag, st);
        if (end > 0) {
          cites.add(line.substring(st, end));
        } else {
          cites.add(line.substring(st));
        }
        st = end;
      }
      List<BibRecord> out = new ArrayList<BibRecord>();
      boolean first = true;
      for (String s : cites) {
        s = s.replaceAll("-<lb>(\\p{Ll})", "$1").replaceAll("<lb>", " ").trim();
        for(int j=0; j<recParser.length;j++) {
          BibRecord br = this.recParser[j].parseRecord(s);
          if(br!=null) {
            out.add(br);
            break;
          }
        }
      }
      out = removeNulls(out);
      return out;
    }
  }

  private class NumberCloseParen extends BibStractor {
    NumberCloseParen(Class[] c) {
      super(c);
    }

    private final String citeRegex = "\\(([0-9, \\p{Pd}]+)\\)";
    public String getCiteRegex() {
      return citeRegex;
    }

    private final String citeDelimiter = "(,| |;)+";
    public String getCiteDelimiter() {
      return citeDelimiter;
    }

    public List<BibRecord> parse(String line) {
      line = line.replaceAll("<bb>", "<lb>");
      int i = 0;
      String tag = (++i) + ")";
      List<String> cites = new ArrayList<String>();
      int st = line.indexOf(tag);
      while (st >= 0) {
        tag = "<lb>" + (++i) + ")";
        int end = line.indexOf(tag, st);
        if (end > 0) {
          cites.add(line.substring(st, end));
        } else {
          cites.add(line.substring(st));
        }
        st = end;
      }
      List<BibRecord> out = new ArrayList<BibRecord>();
      boolean first = true;
      for (String s : cites) {
        s = s.replaceAll("-<lb>(\\p{Ll})", "$1").replaceAll("<lb>", " ").trim();
        for(int j=0; j<recParser.length;j++) {
          BibRecord br = this.recParser[j].parseRecord(s);
          if(br!=null) {
            out.add(br);
            break;
          }
        }
      }
      out = removeNulls(out);
      return out;
    }
  }

  private class BracketName extends BibStractor {
    protected final String citeRegex = "\\[([^\\]]+)\\]";
    protected final String citeDelimiter = "; ?";

    BracketName(Class [] c) {
      super(c);
    }
    
    public String getCiteRegex() {
      return citeRegex;
    }

    public String getCiteDelimiter() {
      return citeDelimiter;
    }

    public List<BibRecord> parse(String line) {
      if (line.startsWith("<bb>"))
        line = line.substring(4);
      String[] citesa = line.split("<bb>");
      List<String> cites = Arrays.asList(citesa);
      List<BibRecord> out = new ArrayList<BibRecord>();
      boolean first = true;
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ").trim();
        if(first) { //don't overwrite number-bracket or number-dot
          if(s.length() > 0)
              if(RegexWithTimeout.matcher(pBracket, s).matches() ||
            RegexWithTimeout.matcher(pDot, s).matches()) {
                return removeNulls(out);
              }
              else {
                first = false;
              }
        }
        for(int i=0; i<recParser.length;i++) {
          BibRecord br = this.recParser[i].parseRecord(s);
          if(br!=null) {
            out.add(br);
            break;
          }
        }
      }
      out = removeNulls(out);
      return out;
    }
  }
}
