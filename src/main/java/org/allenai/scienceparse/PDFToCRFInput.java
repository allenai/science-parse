package org.allenai.scienceparse;

import com.gs.collections.api.list.primitive.IntList;
import com.gs.collections.api.set.ImmutableSet;
import com.gs.collections.api.set.primitive.ImmutableIntSet;
import com.gs.collections.api.set.primitive.IntSet;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.api.tuple.primitive.IntIntPair;
import com.gs.collections.impl.factory.primitive.FloatLists;
import com.gs.collections.impl.factory.primitive.IntSets;
import com.gs.collections.impl.tuple.Tuples;
import com.gs.collections.impl.tuple.primitive.IntIntPairImpl;
import com.gs.collections.impl.tuple.primitive.PrimitiveTuples;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;
import org.allenai.scienceparse.pdfapi.PDFToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Slf4j
public class PDFToCRFInput {
  /**
   * Returns the index start (inclusive) and end (exclusive)
   * of end of pattern sequence in token seq, starting from startposes.  Returns -1 if not found
   *
   * @param seq
   * @param patOptional
   * @param seqStartPos
   * @param patStartPos
   * @return
   */
  public static int findPatternEnd(List<String> seq, List<Pair<Pattern, Boolean>> patOptional,
                                   int seqStartPos, int patStartPos) {

    if (patOptional.size() == patStartPos) { //treated as completion
      return seqStartPos;
    }
    if (patOptional.size() == 0) { //treated as error rather than completion
      return -1;
    }
    if (seq.size() == 0 || seqStartPos == seq.size())
      return -1;

    final String pt = StringUtils.normalize(seq.get(seqStartPos));
    if (patOptional.get(patStartPos).getOne().matcher(pt).matches()) {
      //look forward:
      return findPatternEnd(seq, patOptional, seqStartPos + 1, patStartPos + 1);
    }
    if (patOptional.get(patStartPos).getTwo()) {
      //try skipping this pattern:
      return findPatternEnd(seq, patOptional, seqStartPos, patStartPos + 1);
    }
    //no matches from this token
    return -1;

  }

  private static String seqToString(final List<String> seq) {
    final StringBuilder b = new StringBuilder(seq.size() * 10);
    for(final String s: seq) {
      b.append(s);
      b.append(' ');
    }
    if(b.length() > 0)
      b.setLength(b.length() - 1);
    return b.toString();
  }

  private static String patternToString(final List<Pair<Pattern, Boolean>> pattern) {
    final StringBuilder b = new StringBuilder(pattern.size() * 10);
    for(final Pair<Pattern, Boolean> p : pattern) {
      final boolean optional = p.getTwo();
      if(optional)
        b.append('[');

      b.append(p.getOne().pattern());

      if(optional)
        b.append(']');
      b.append(' ');
    }
    b.setLength(b.length() - 1);
    return b.toString();
  }

  /**
   * Returns the index start (inclusive) and end (exclusive)
   * of first occurrence of pattern sequence in token seq, or null if not found
   *
   * @param seq         Token sequence
   * @param patOptional (Pattern, optional) pair indicating pattern to match and whether it can be skipped
   * @return
   */
  public static Pair<Integer, Integer> findPatternSequence(List<String> seq, List<Pair<Pattern, Boolean>> patOptional) {
    if(log.isDebugEnabled()) {
      // patternToString() and seqToString() do a lot of work, and we don't want that done if debug
      // isn't enabled.
      log.debug("Finding {}\nin {}", patternToString(patOptional), seqToString(seq));
    }
    for (int i = 0; i < seq.size(); i++) {
      int end = -1;
      if ((end = findPatternEnd(seq, patOptional, i, 0)) >= 0) {
        return Tuples.pair(i, end);
      }
    }
    return null;
  }

  /**
   * Returns the index of start (inclusive) and end (exclusive)
   * of first occurrence of string in seq, or null if not found
   *
   * @param seq String to find, assumes tokens are space-delimited
   * @return
   */
  public static Pair<Integer, Integer> findString(List<String> seq, String toFind) {
    toFind = StringUtils.normalize(toFind);
    if (seq.size() == 0 || toFind.length() == 0)
      return null;
    final List<String> tokenList = tokenize(toFind);
    final String[] toks = tokenList.toArray(new String[tokenList.size()]);
    if (toks.length == 0) { //can happen if toFind is just spaces
      return null;
    }
    int nextToMatch = 0;
    for (int i = 0; i < seq.size(); i++) {
      String s = StringUtils.normalize(seq.get(i));
      if (toks[nextToMatch].equalsIgnoreCase(s)) {
        nextToMatch++;
      } else {
        i -= nextToMatch; //start back at char after start of match
        nextToMatch = 0;
      }
      if (nextToMatch == toks.length)
        return Tuples.pair(i + 1 - toks.length, i + 1);
    }
    return null;
  }

  public static List<Pair<Pattern, Boolean>> authorToPatternOptPair(String author) {
    List<Pair<Pattern, Boolean>> out = new ArrayList<>();
    //get rid of stuff that can break regexs:
    author = author.replace(")", "");
    author = author.replace("(", "");
    author = author.replace("?", "");
    author = author.replace("*", "");
    author = author.replace("+", "");
    author = author.replace("^", "");

    final List<String> tokenList = tokenize(StringUtils.normalize(author));
    String[] toks = tokenList.toArray(new String[tokenList.size()]);
    for (int i = 0; i < toks.length; i++) {
      boolean optional = true;
      if (i == 0 || i == toks.length - 1)
        optional = false;
      String pat = "";
      if (i < toks.length - 1) {
        if (toks[i].matches("[A-Z](\\.)?")) { //it's a single-letter abbreviation
          pat = toks[i].substring(0, 1) + "(((\\.)?)|([a-z]+))"; //allow arbitrary expansion
        } else {
          if (toks[i].length() > 1)                    //allow single-initial abbreviations
            pat = toks[i].substring(0, 1) + "(((\\.)?)|(" + toks[i].substring(1) + "))";
          else
            pat = toks[i];//catch-all for edge cases
        }
      } else {
        pat = toks[i];
        pat += "((\\W)|[0-9])*"; //allow some non-alpha or number (typically, footnote marker) at end of author
      }
      try {
        //log.info("trying pattern " + pat);
        out.add(Tuples.pair(Pattern.compile(pat, Pattern.CASE_INSENSITIVE), optional));
      } catch (Exception e) {
        log.info("error in author pattern " + pat);
      }
    }
    if (toks.length == 2) { //special case, add optional middle initial
      Pair<Pattern, Boolean> temp = out.get(1);
      for (int i = 2; i < out.size(); i++) {
        val temp2 = out.get(i);
        out.set(i, temp);
        temp = temp2;
      }
      out.add(temp);
      out.set(1, Tuples.pair(Pattern.compile("[A-Z](\\.)?", Pattern.CASE_INSENSITIVE), true));
    }
    return out;
  }

  public static Pair<Integer, Integer> findAuthor(List<String> seq, String toFind) {
    List<Pair<Pattern, Boolean>> pats = authorToPatternOptPair(toFind);
    return findPatternSequence(seq, pats);
  }

  private static void addLineTokens(List<PaperToken> accumulator, List<PDFLine> lines, final int pg) {
    int ln = 0;
    for (PDFLine l : lines) {
      final int lnF = ln++; //ugh (to get around compile error)
      l.tokens.forEach((PDFToken t) -> accumulator.add(
          new PaperToken(t, lnF, pg))
      );
    }
  }


  public static float getY(PDFLine l, boolean upper) {
    if (upper)
      return l.bounds().get(1);
    else
      return l.bounds().get(3);
  }

  public static float getX(PDFLine l, boolean left) {
    if (left)
      return l.bounds().get(0);
    else
      return l.bounds().get(2);
  }

  public static float getX(PDFToken t, boolean left) {
    if(left)
      return t.getBounds().get(0);
    else
      return t.getBounds().get(2);
  }
  
  public static float getY(PDFToken t, boolean upper) {
    if (upper)
      return t.getBounds().get(1);
    else
      return t.getBounds().get(3);
  }
  
  public static float getH(PDFLine l) {
    float result = l.bounds().get(3) - l.bounds().get(1);
    if (result < 0) {
      log.debug("Negative height? Guessing a height of 5.");
      return 5;
    } else {
      return result;
    }
  }

  private final static ImmutableIntSet whitespaceCharTypes = IntSets.immutable.of(
          Character.CONTROL,
          Character.PARAGRAPH_SEPARATOR,
          Character.LINE_SEPARATOR,
          Character.SPACE_SEPARATOR);
  private final static ImmutableIntSet punctuationCharTypes = IntSets.immutable.of(
          Character.CURRENCY_SYMBOL,
          Character.DASH_PUNCTUATION,
          Character.ENCLOSING_MARK,
          Character.START_PUNCTUATION,
          Character.END_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION,
          Character.INITIAL_QUOTE_PUNCTUATION,
          Character.MATH_SYMBOL,
          Character.MODIFIER_SYMBOL,
          Character.OTHER_PUNCTUATION,
          Character.OTHER_SYMBOL
  );

  /**
   * Returns a list of ranges that define words in the given string
   */
  public static List<IntIntPair> findWordRanges(final CharSequence s) {
    final List<IntIntPair> result = new ArrayList<>(2);
    int wordStart = 0;
    for(int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      final int cType = Character.getType(c);

      if(whitespaceCharTypes.contains(cType)) {
        if(wordStart < i)
          result.add(PrimitiveTuples.pair(wordStart, i));
        wordStart = i + 1;
      } else if(c == '.') {
        // special case for periods, since we want to keep them with the word before, but not with
        // the word after
        result.add(PrimitiveTuples.pair(wordStart, i + 1));
        wordStart = i + 1;
      } else if(punctuationCharTypes.contains(cType)) {
        // add whatever was before the punctuation
        if(i > wordStart)
          result.add(PrimitiveTuples.pair(wordStart, i));
        wordStart = i;

        // add the punctuation itself
        result.add(PrimitiveTuples.pair(wordStart, wordStart + 1));
        wordStart = i + 1;
      }
    }

    if(wordStart < s.length())
      result.add(PrimitiveTuples.pair(wordStart, s.length()));

    return result;
  }

  public static List<String> tokenize(final String s) {
    return findWordRanges(s).stream().map(
            range -> s.substring(range.getOne(), range.getTwo())
    ).collect(Collectors.toList());
  }

  /**
   * Returns the PaperToken sequence form of a given PDF document
   *
   * @param pdf             The PDF Document to convert into instances
   * @return The data sequence
   * @throws IOException
   */
  public static List<PaperToken> getSequence(PDFDoc pdf) throws IOException {
    ArrayList<PaperToken> rawTokens = new ArrayList<>();
    List<PDFPage> pages = pdf.getPages();
    for (int pageNum = 0; pageNum < pages.size(); pageNum++) {
      addLineTokens(rawTokens, pages.get(pageNum).lines, pageNum);
    }
    
    // split tokens according to tokenization rules
    final ArrayList<PaperToken> splitTokens = new ArrayList<>(3 * rawTokens.size());
    for(final PaperToken rawToken : rawTokens) {
      final PDFToken rawPdfToken = rawToken.getPdfToken();

      final String rawTokenText = rawPdfToken.getToken();
      final List<IntIntPair> wordRanges = findWordRanges(rawTokenText);

      final float rawX0 = rawPdfToken.bounds.get(0);
      final float rawY0 = rawPdfToken.bounds.get(1);
      final float rawX1 = rawPdfToken.bounds.get(2);
      final float rawY1 = rawPdfToken.bounds.get(3);
      final float widthPerCharacter = (rawX1 - rawX0) / rawTokenText.length();

      for(final IntIntPair wordRange : wordRanges) {
        // This way of re-calculating the token boundaries only works with left-to-right languages.
        // It also makes the crude assumption that all characters have the same width.
        final PDFToken newPdfToken = PDFToken.builder().
                fontMetrics(rawPdfToken.getFontMetrics()).
                token(rawTokenText.substring(wordRange.getOne(), wordRange.getTwo())).
                bounds(FloatLists.immutable.of(
                        rawX0 + wordRange.getOne() * widthPerCharacter,
                        rawY0,
                        rawX0 + wordRange.getTwo() * widthPerCharacter,
                        rawY1)).
                build();

        final PaperToken newToken =
                new PaperToken(newPdfToken, rawToken.getLine(), rawToken.getPage());
        splitTokens.add(newToken);
      }
    }

    return splitTokens;
  }

  public static List<PaperToken> padSequence(List<PaperToken> seq) {
    ArrayList<PaperToken> out = new ArrayList<>();
    out.add(PaperToken.generateStartStopToken());
    out.addAll(seq);
    out.add(PaperToken.generateStartStopToken());
    return out;
  }

  public static List<String> padTagSequence(List<String> seq) {
    ArrayList<String> out = new ArrayList<>();
    out.add("<S>");
    out.addAll(seq);
    out.add("</S>");
    return out;
  }

  /**
   * Labels the (first occurrence of) given target in seq with given label
   *
   * @param seq        The sequence
   * @param seqLabeled The same sequence with labels
   * @param target
   * @param labelStem
   * @return True if target was found in seq, false otherwise
   */
  public static boolean findAndLabelWith(
          final String paperId,
          final List<PaperToken> seq,
          final List<Pair<PaperToken, String>> seqLabeled,
          final String target,
          final String labelStem,
          final boolean isAuthor
  ) {
    Pair<Integer, Integer> loc = null;
    if (isAuthor)
      loc = findAuthor(asStringList(seq), target);
    else
      loc = findString(asStringList(seq), target);
    if (loc == null) {
      log.debug("{}: could not find {} string {} in paper.", paperId, labelStem, target);
      return false;
    } else {
      if (loc.getOne() == loc.getTwo() - 1) {
        Pair<PaperToken, String> t = seqLabeled.get(loc.getOne());
        seqLabeled.set(loc.getOne(), Tuples.pair(t.getOne(), "W_" + labelStem));
      } else {
        for (int i = loc.getOne(); i < loc.getTwo(); i++) {
          Pair<PaperToken, String> t = seqLabeled.get(i);
          seqLabeled.set(i, Tuples.pair(t.getOne(),
            (i == loc.getOne() ? "B_" + labelStem : (i == loc.getTwo() - 1 ? "E_" + labelStem : "I_" + labelStem))));
        }
      }
      return true;
    }
  }

  /**
   * Returns the given tokens in a new list with labeled ground truth attached
   * according to the given reference metadata.
   * Only labels positive the first occurrence of each ground-truth string.
   * <br>
   * Labels defined in ExtractedMetadata
   *
   * @param toks
   * @param truth
   * @return
   */
  public static List<Pair<PaperToken, String>> labelMetadata(
          final String paperId,
          final List<PaperToken> toks,
          final ExtractedMetadata truth
  ) {
    val outTmp = new ArrayList<Pair<PaperToken, String>>();
    for (PaperToken t : toks) {
      outTmp.add(Tuples.pair(t, "O"));
    }
    truth.authors.forEach((String s) -> findAndLabelWith(paperId, toks, outTmp, s, ExtractedMetadata.authorTag, true));
    if (!findAndLabelWith(paperId, toks, outTmp, truth.title, ExtractedMetadata.titleTag, false)) //must have title to be valid
      return null;
    val out = new ArrayList<Pair<PaperToken, String>>();
    out.add(Tuples.pair(PaperToken.generateStartStopToken(), "<S>"));
    out.addAll(outTmp);
    out.add(Tuples.pair(PaperToken.generateStartStopToken(), "</S>"));
    return out;
  }

  public static List<String> asStringList(List<PaperToken> toks) {
    return toks.stream().map(pt -> pt.getPdfToken().token).collect(Collectors.toList());
  }

  public static String stringAt(List<PaperToken> toks, Pair<Integer, Integer> span) {
    List<PaperToken> pts = toks.subList(span.getOne(), span.getTwo());
    List<String> words = pts.stream().map(pt -> (pt.getLine() == -1) ? "<S>" : pt.getPdfToken().token).collect(Collectors.toList());
    return appendStringList(words).trim();
  }
  
  public static String stringAtForStringList(List<String> toks, Pair<Integer, Integer> span) {
    List<String> words = toks.subList(span.getOne(), span.getTwo());
    return appendStringList(words).trim();
  }
  
  public static String appendStringList(List<String> toks) {
    StringBuilder sb = new StringBuilder();
    for (String s : toks) {
      sb.append(s);
      sb.append(" ");
    }
    return sb.toString();
  }

  public static String getLabelString(List<Pair<PaperToken, String>> seq) {
    return seq.stream().map(Pair::getTwo).collect(Collectors.toList()).toString();
  }
}
