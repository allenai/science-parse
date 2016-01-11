package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ExtractReferences {

  public static final String authUnit = "\\p{Lu}[\\p{L}'`\\-]+";
  public static final String authOneName = authUnit + "(?: " + authUnit + ")?"; //space and repetition for things like De Mori
  public static final String authLastCommaInitial = authOneName + ", (?:\\p{Lu}\\.-? ?)+";
  public static final String authConnect = "(?:; |, |, and |; and | and )";

  //279
  //378
  //480
  //492
  //606
  public static final String authInitialsLast = "(?:\\p{Lu}\\.?(?:-| )?)+ " + authOneName;
  public static final String authInitialsLastList = authInitialsLast + "(?:" + authConnect + authInitialsLast + ")*";
  public static final String authPlain = authOneName + "(?:\\p{Lu}\\. )?" + authOneName;
  public static final String authPlainList = authPlain + "(?:(?:, and|,) (?:" + authPlain + "))*";

  //pattern for matching single author name, format as in Jones, C. M.
  public static final String authGeneral = "\\p{Lu}[\\p{L}\\.'`\\- ]+";
  public static final String authGeneralList = authGeneral + "(?:(?:; |, |, and |; and | and )" + authGeneral + ")*";
  private static List<BibStractor> extractors =
    Arrays.asList(new BracketNumber(BracketNumberInitialsQuotedBibRecordParser.class),
      new NamedYear(NamedYearBibRecordParser.class),
      new NamedYear(NamedYearInParensBibRecordParser.class),
      new NumberDot(NumberDotYearParensBibRecordParser.class),
      new NumberDot(NumberDotAuthorNoTitleBibRecordParser.class),
      new NumberDot(NumberDotYearNoParensBibRecordParser.class),
      new BracketNumber(BracketNumberInitialsYearParensCOMMAS.class),
      new BracketNumber(BracketNumberBibRecordParser.class),
      new BracketName(BracketNameBibRecordParser.class));
  CheckReferences cr;

  public ExtractReferences(String jsonFile) throws IOException {
    cr = new CheckReferences(jsonFile);
  }

  private static Pattern authStrToPat(String s) {
    Pattern p = Pattern.compile(s, Pattern.CASE_INSENSITIVE);
    return p;
  }

  //returns pattern-ready form of author
  private static String cleanAuthString(String s) {
    return s.replaceAll("\\p{P}", ".");//allow anything for any punctuation
  }

  private static int extractRefYear(String sYear) {
    String yearPattern = "[1-2][0-9][0-9][0-9]";
    Matcher mYear = Pattern.compile(yearPattern).matcher(sYear);
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

  /**
   * Takes in a string mentioning several authors, returns normalized list of authors
   *
   * @param authString
   * @return
   */
  public static List<String> authorStringToList(String authString) {
    boolean semiDelim = false;
    if (authString.contains(";")) { //assume semi-colon delimiter
      semiDelim = true;
    }

    //figure out whether M. Johnson or Johnson, M.:
    boolean firstLast = false;
    List<String> out = new ArrayList<>();
    if (Pattern.compile("\\p{Lu}\\..*").matcher(authString).matches()) {
      firstLast = true;
    }
    String[] names;
    if (semiDelim)
      names = authString.split("(;|; and| and | AND | And )+");
    else
      names = authString.split("(,| and | AND | And )+");
    if (firstLast) {
      out = Arrays.asList(names);
    } else {
      if (semiDelim) {
        for (final String name : names)
          out.add(ParserGroundTruth.invertAroundComma(name));
      } else {
        for (int i = 0; i < names.length; i += 2) {
          if (names.length > i + 1)
            out.add(names[i + 1].trim() + " " + names[i].trim());
          else
            out.add(names[i].trim()); //hope for the best
        }
      }
    }
    return out;
  }

  private static <T> List<T> removeNulls(List<T> in) {
    List<T> out = new ArrayList<T>();
    for (T a : in) {
      if (a != null)
        out.add(a);
    }
    return out;
  }

  private static String getAuthorLastName(String authName) {
    int idx = authName.lastIndexOf(" ");
    return authName.substring(idx + 1);
  }

  private static int refStart(List<String> paper) {
    for (int i = 0; i < paper.size(); i++) {
      String s = paper.get(i);
      if (s.endsWith("References") || s.endsWith("Citations") || s.endsWith("Bibliography") ||
        s.endsWith("REFERENCES") || s.endsWith("CITATIONS") || s.endsWith("BIBLIOGRAPHY"))
        return i;
      else if (s.contains("References<lb>") || s.contains("Citations<lb>") || s.contains("Bibliography<lb>") ||
        s.contains("REFERENCES<lb>") || s.contains("CITATIONS<lb>") || s.contains("BIBLIOGRAPHY<lb>")) {
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
		 Matcher m = br.shortCiteRegEx.matcher(s);
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

    int stop = refStart(paper); //stop at start of refs
    if (stop < 0)
      stop = paper.size(); //start of refs not found (should never happen for non-null bibliography...)

    for (int i = 0; i < stop; i++) {
      String s = paper.get(i).replaceAll("-<lb>", "").replaceAll("<lb>", " ");
      paper.set(i, s);
      Matcher m = p.matcher(s);
      while (m.find()) {
        String[] citations = m.group(1).split(bs.getCiteDelimiter());
        for (final String citation : citations) {
          int idx = getIdxOf(bib, citation.trim());
          if (idx >= 0) {
            out.add(new CitationRecord(i, m.start(), m.end(), idx));
          }
        }
      }
      //short-cites are assumed to be e.g.: Etzioni et al. (2005)
      if(bs.getShortCiteRegex() != null) {
    	  Pattern p2 = Pattern.compile(bs.getShortCiteRegex());
    	  Matcher m2 = p2.matcher(s);
    	  while(m2.find()) {
    		  Pair<Integer, Integer> shct = shortCiteSearch(m2.start(), Integer.parseInt(m2.group(1).substring(0, 4)), s, bib);
    		  int start = shct.getOne();
    		  int idx = shct.getTwo();
    		  if(start > 0) {
    			 out.add(new CitationRecord(i, start, m2.end()+1, idx));
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
    return idx;
  }

  /**
   * Returns the list of BibRecords, plus the extractor that produced them (in order to enable citation parsing)
   *
   * @param paper
   * @return
   */
  public Pair<List<BibRecord>, BibStractor> findReferences(List<String> paper) {
    int start = refStart(paper) + 1;
    List<BibRecord>[] results = new ArrayList[extractors.size()];
    for (int i = 0; i < results.length; i++)
      results[i] = new ArrayList<BibRecord>();
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < paper.size(); i++) {
      sb.append("<bb>");
      sb.append(paper.get(i));
    }
    String text = sb.toString();
    for (int i = 0; i < results.length; i++) {
      results[i] = extractors.get(i).parse(text);
    }
    int idx = longestIdx(results);
    return Tuples.pair(results[idx], extractors.get(idx));
  }


  public interface BibRecordParser {
    BibRecord parseRecord(String line);
  }

  public static abstract class BibStractor {
    final BibRecordParser recParser;

    BibStractor(Class c) {
      BibRecordParser b = null;
      try {
        b = (BibRecordParser) c.newInstance();
      } catch (final Exception e) {
        log.warn("Exception while creating BibStractor", e);
      }
      recParser = b;
    }

    public abstract List<BibRecord> parse(String source);

    public abstract String getCiteRegex();
    
    public abstract String getShortCiteRegex(); //may return null for bibstractors without short cites

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
      Matcher m = pattern.matcher(line.trim());
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
      Matcher m = pattern.matcher(line.trim());
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
      Matcher m = pattern.matcher(line.trim());
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
      "([0-9]+)\\. ([^:]+): ([^\\.]+)\\. (?:(?:I|i)n: )?(.*) \\(([0-9]{4})\\)(?: .*)?"; //last part is for header break
    private static final Pattern pattern = Pattern.compile(regEx);

    public NumberDotYearParensBibRecordParser() {
    }

    //example:
    //TODO
    public BibRecord parseRecord(String line) {
      Matcher m = pattern.matcher(line.trim());
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
      Matcher m = pattern.matcher(line.trim());
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
      Matcher m = pattern.matcher(line.trim());
      if (m.matches()) {
        final List<String> authors = authorStringToList(m.group(1));
        final int year = Integer.parseInt(m.group(2).substring(0, 4));
        final String nameStr = NamedYear.getCiteAuthorFromAuthors(authors);
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
      Matcher m = pattern.matcher(line.trim());
      if (m.matches()) {
        List<String> authors = authorStringToList(m.group(1));
        int year = Integer.parseInt(m.group(2).substring(0, 4));
        String nameStr = NamedYear.getCiteAuthorFromAuthors(authors);
        String citeStr = nameStr + ",? " + m.group(2);
        return new BibRecord(m.group(3), authors, m.group(4), authStrToPat(citeStr), authStrToPat(nameStr), year);
      } else {
        return null;
      }
    }
  }

  private static class BracketNumberInitialsYearParensCOMMAS implements BibRecordParser {
    private static final String regEx1 =
      "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*) +\\(.*([1-2][0-9]{3}).*\\).*";
    private static final Pattern pattern1 = Pattern.compile(regEx1);
    private static final String regEx2 =
      "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*), ([1-2][0-9]{3})\\.";
    private static final Pattern pattern2 = Pattern.compile(regEx2);

    public BracketNumberInitialsYearParensCOMMAS() {
    }

    //example:
    // [1] S. Abiteboul, H. Kaplan, and T. Milo, Compact labeling schemes for ancestor queries. Proc. 12th Ann. ACM-SIAM Symp.
    // on Discrete Algorithms (SODA 2001), 547-556.
    public BibRecord parseRecord(String line) {
      Matcher m = pattern1.matcher(line.trim());
      Matcher m2 = pattern2.matcher(line.trim());
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
      Matcher m = pattern1.matcher(line.trim());
      Matcher m2 = pattern2.matcher(line.trim());
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

  private static class NamedYear extends BibStractor {
    private final static String citeRegex =
      "(?:\\[|\\()([^\\[\\(\\]\\)]+ [1-2][0-9]{3}[a-z]?)+(?:\\]|\\))";
    private final static String shortCiteRegex = "(?:\\[|\\()([1-2][0-9]{3}[a-z]?)(?:\\]|\\))";
    private final static String citeDelimiter = "; ?";

    NamedYear(Class c) {
      super(c);
    }
    

    //in regex form
    public static String getCiteAuthorFromAuthors(List<String> authors) {
      if (authors.size() > 2) {
        return cleanAuthString(getAuthorLastName(authors.get(0))) + " et al\\.";
      } else if (authors.size() == 1) {
        return cleanAuthString(getAuthorLastName(authors.get(0)));
      } else if (authors.size() == 2) {
        return cleanAuthString(getAuthorLastName(authors.get(0))) + " and " + cleanAuthString(getAuthorLastName(authors.get(1)));
      }
      return null;
    }

    public String getCiteRegex() {
      return citeRegex;
    }

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
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ");
        out.add(this.recParser.parseRecord(s));
      }
      out = removeNulls(out);
      return out;
    }

  }

  private static class NumberDot extends BracketNumber {
    NumberDot(Class c) {
      super(c);
    }

    public List<BibRecord> parse(String line) {
      line = line.replaceAll("<bb>", "<lb>");
      int i = 0;
      String tag = "<lb>" + (++i) + ". ";
      List<String> cites = new ArrayList<String>();
      int st = line.indexOf(tag);
      while (line.contains(tag) && st >= 0) {
        tag = "<lb>" + (++i) + ". ";
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
        out.add(this.recParser.parseRecord(s));
      }
      out = removeNulls(out);
      return out;
    }
  }

  private static class BracketNumber extends BibStractor {
    protected final String citeRegex = "\\[([0-9, ]+)\\]";
    protected final String citeDelimiter = "(,| |;)+";

    BracketNumber(Class c) {
      super(c);
    }

    public String getCiteRegex() {
      return citeRegex;
    }

    public String getShortCiteRegex() {
    	return null;
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
      while (line.contains(tag)) {
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
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ");
        out.add(this.recParser.parseRecord(s));
      }
      out = removeNulls(out);
      return out;
    }
  }

  private static class BracketName extends BibStractor {
    protected final String citeRegex = "\\[([^\\]]+)\\]";
    protected final String citeDelimiter = "; ?";

    BracketName(Class c) {
      super(c);
    }

    public String getShortCiteRegex() {
    	return null;
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
      for (String s : cites) {
        s = s.replaceAll("-<lb>", "").replaceAll("<lb>", " ");
        out.add(this.recParser.parseRecord(s));
      }
      out = removeNulls(out);
      return out;
    }
  }
}
