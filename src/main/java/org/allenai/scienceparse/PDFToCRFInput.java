package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;
import org.allenai.scienceparse.pdfapi.PDFToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
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

    String pt = seq.get(seqStartPos);
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

  /**
   * Returns the index start (inclusive) and end (exclusive)
   * of first occurrence of pattern sequence in token seq, or null if not found
   *
   * @param seq         Token sequence
   * @param patOptional (Pattern, optional) pair indicating pattern to match and whether it can be skipped
   * @return
   */
  public static Pair<Integer, Integer> findPatternSequence(List<String> seq, List<Pair<Pattern, Boolean>> patOptional) {
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
    if (seq.size() == 0 || toFind.length() == 0)
      return null;
    String[] toks = toFind.split(" ");
    if (toks.length == 0) { //can happen if toFind is just spaces
      return null;
    }
    int nextToMatch = 0;
    for (int i = 0; i < seq.size(); i++) {
      String s = seq.get(i);
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

    String[] toks = author.split(" ");
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
      out.set(1, Tuples.pair(Pattern.compile("[A-Z](\\.)?"), true));
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

  public static double breakSize(PDFLine l2, PDFLine l1) {
    if (l2 == null || l1 == null)
      return 0.0;
    float h1 = getH(l1);
    float h2 = getH(l2);
    return (getY(l2, true) - getY(l1, false)) / Math.min(h1, h2);
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

  public static float getH(PDFLine l) {
    float result = l.bounds().get(3) - l.bounds().get(1);
    if (result < 0) {
      log.debug("Negative height? Guessing a height of 5.");
      return 5;
    } else {
      return result;
    }
  }

  public static double getTopQuartileLineBreak(PDFDoc pdf) {
    ArrayList<Double> breaks = new ArrayList<>();
    PDFLine prevLine = null;
    for (PDFPage p : pdf.getPages()) {
      for (PDFLine l : p.getLines()) {
        double bs = breakSize(l, prevLine);
        if (bs > 0) { //<= 0 due to math, tables, new pages, should be ignored
          breaks.add(bs);
        }
        prevLine = l;
      }
    }
    breaks.sort((d1, d2) -> Double.compare(d1, d2));
    int idx = (7 * breaks.size()) / 9; //hand-tuned threshold good for breaking references
    return breaks.get(idx);
  }

  public static String lineToString(PDFLine l) {
    StringBuilder sb = new StringBuilder();
    l.tokens.forEach(t -> sb.append(t.token + " "));
    return sb.toString().trim();
  }

  public static String cleanLine(String s) {
    s = s.replaceAll("\r|\t|\n", " ").trim();
    while (s.contains("  "))
      s = s.replaceAll("  ", " ");
    return s;
  }

  /**
   * Returns best guess of list of strings representation of the references of this file,
   * intended to be one reference per list element, using spacing and indentation as cues
   *
   * @param pdf
   * @return
   */
  public static List<String> getRawReferences(PDFDoc pdf) {
    final List<String> refTags = Arrays.asList("References", "REFERENCES", "Citations", "CITATIONS", "Bibliography",
      "BIBLIOGRAPHY");
    List<String> out = new ArrayList<String>();
    PDFLine prevLine = null;
    boolean inRefs = false;
    double qLineBreak = getTopQuartileLineBreak(pdf);
    StringBuffer sb = new StringBuffer();
    for (PDFPage p : pdf.getPages()) {
      double farLeft = Double.MAX_VALUE; //of current column
      double farRight = -1.0; //of current column
      for (PDFLine l : p.getLines()) {
        if (!inRefs && (l != null && l.tokens != null && l.tokens.size() > 0)) {
          if (l.tokens.get(l.tokens.size() - 1).token != null &&
            refTags.contains(l.tokens.get(l.tokens.size() - 1).token.trim())) {
            inRefs = true;
          }
        } else if (inRefs) {
          double left = getX(l, true);
          double right = getX(l, false);
          if (farRight >= 0 && right > farRight) { //new column, reset
            farLeft = Double.MAX_VALUE;
            farRight = -1.0;
          }
          farLeft = Math.min(left, farLeft);
          farRight = Math.max(right, farRight);
          boolean br = false;
          if (l.tokens != null && l.tokens.size() > 0) {
            String sAdd = lineToString(l);
            if (left > farLeft + l.tokens.get(0).fontMetrics.spaceWidth) {
              br = false;
            } else if (getX(prevLine, false) + l.tokens.get(0).fontMetrics.spaceWidth < farRight) {
              br = true;
            } else if (breakSize(l, prevLine) > qLineBreak) {
              br = true;
            }
            if (br) {
              out.add(cleanLine(sb.toString()));
              sb = new StringBuffer(sAdd);
            } else {
              sb.append("<lb>");
              sb.append(sAdd);
            }
          }
        }
        prevLine = l;
      }
      //HACK(dcdowney): always break on new page.  Should be safe barring "bad breaks" I think
      if (sb.length() > 0) {
        String sAdd = sb.toString();
        if (sAdd.endsWith("<lb>"))
          sAdd = sAdd.substring(0, sAdd.length() - 4);
        out.add(cleanLine(sAdd));
        sb = new StringBuffer();
      }
    }
    return out;
  }

  /**
   * Returns list of strings representation of this file.  Breaks new lines when pdf line break larger than median line break.
   * All original line breaks indicated by <lb>
   *
   * @param pdf
   * @return
   */
  public static List<String> getRaw(PDFDoc pdf) {
    ArrayList<String> out = new ArrayList<>();

    //log.info("median line break: " + qLineBreak);
    StringBuffer s = new StringBuffer();
    PDFLine prevLine = null;
    double qLineBreak = getTopQuartileLineBreak(pdf);
    for (PDFPage p : pdf.getPages()) {
      for (PDFLine l : p.getLines()) {
        if (breakSize(l, prevLine) > qLineBreak) {
          String sAdd = s.toString();
          if (sAdd.endsWith("<lb>"))
            sAdd = sAdd.substring(0, sAdd.length() - 4);
          out.add(sAdd);
          s = new StringBuffer();
        }
        String sAdd = lineToString(l);
        if (sAdd.length() > 0) {
          s.append(sAdd);
          s.append("<lb>");
        }
        prevLine = l;
      }
      //HACK(dcdowney): always break on new page.  Should be safe barring "bad breaks" I think
      if (s.length() > 0) {
        String sAdd = s.toString();
        if (sAdd.endsWith("<lb>"))
          sAdd = sAdd.substring(0, sAdd.length() - 4);
        out.add(sAdd);
        s = new StringBuffer();
      }
    }
    return out;
  }

  /**
   * Returns the PaperToken sequence form of a given PDF document<br>
   *
   * @param pdf             The PDF Document to convert into instances
   * @param heuristicHeader If true, tries to use heuristic header if found
   * @return The data sequence
   * @throws IOException
   */
  public static List<PaperToken> getSequence(PDFDoc pdf, boolean heuristicHeader) throws IOException {

    ArrayList<PaperToken> out = new ArrayList<>();
    if (heuristicHeader && pdf.heuristicHeader() != null) {
      List<PDFLine> header = pdf.heuristicHeader();
      addLineTokens(out, header, 0);
    } else {
      int pg = 0;
      for (PDFPage p : pdf.getPages()) {
        addLineTokens(out, p.getLines(), pg);
      }
    }
    return out;
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
  public static boolean findAndLabelWith(List<PaperToken> seq, List<Pair<PaperToken, String>> seqLabeled,
                                         String target, String labelStem, boolean isAuthor) {
    Pair<Integer, Integer> loc = null;
    if (isAuthor)
      loc = findAuthor(asStringList(seq), target);
    else
      loc = findString(asStringList(seq), target);
    if (loc == null)
      return false;
    else {
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
  public static List<Pair<PaperToken, String>> labelMetadata(List<PaperToken> toks, ExtractedMetadata truth) {
    val outTmp = new ArrayList<Pair<PaperToken, String>>();
    for (PaperToken t : toks) {
      outTmp.add(Tuples.pair(t, "O"));
    }
    truth.authors.forEach((String s) -> findAndLabelWith(toks, outTmp, s, ExtractedMetadata.authorTag, true));
    if (!findAndLabelWith(toks, outTmp, truth.title, ExtractedMetadata.titleTag, false)) //must have title to be valid
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
    StringBuilder sb = new StringBuilder();
    for (String s : words) {
      sb.append(s);
      sb.append(" ");
    }
    return sb.toString().trim();
  }


  public static String getLabelString(List<Pair<PaperToken, String>> seq) {
    return seq.stream().map((Pair<PaperToken, String> a) -> a.getTwo()).collect(Collectors.toList()).toString();
  }
}
