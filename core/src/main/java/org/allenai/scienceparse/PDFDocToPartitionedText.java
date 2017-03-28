package org.allenai.scienceparse;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.gs.collections.api.list.ImmutableList;
import com.gs.collections.api.map.primitive.DoubleIntMap;
import com.gs.collections.api.map.primitive.MutableDoubleIntMap;
import com.gs.collections.api.map.primitive.MutableObjectIntMap;
import com.gs.collections.api.set.primitive.DoubleSet;
import com.gs.collections.api.set.primitive.MutableDoubleSet;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.api.tuple.primitive.DoubleIntPair;
import com.gs.collections.impl.factory.primitive.DoubleIntMaps;
import com.gs.collections.impl.factory.primitive.DoubleSets;
import com.gs.collections.impl.map.mutable.primitive.ObjectIntHashMap;
import com.gs.collections.impl.tuple.Tuples;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;

import lombok.extern.slf4j.Slf4j;
import org.allenai.scienceparse.pdfapi.PDFToken;

@Slf4j
public class PDFDocToPartitionedText {
  /**
   * Returns list of strings representation of this file.  Breaks new lines when pdf line break larger than threshold.
   * All original line breaks indicated by <lb>
   *
   * @param pdf
   * @return
   */
  public static List<String> getRaw(PDFDoc pdf) {
    ArrayList<String> out = new ArrayList<>();

    StringBuilder s = new StringBuilder();
    PDFLine prevLine = null;
    double qLineBreak = getRawBlockLineBreak(pdf);
    for (PDFPage p : pdf.getPages()) {
      for (PDFLine l : p.getLines()) {
        if (breakSize(l, prevLine) > qLineBreak) {
          String sAdd = s.toString();
          if (sAdd.endsWith("<lb>"))
            sAdd = sAdd.substring(0, sAdd.length() - 4);
          out.add(sAdd);
          s = new StringBuilder();
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
        s = new StringBuilder();
      }
    }
    return out;
  }

  public static double breakSize(PDFLine l2, PDFLine l1) {
    if (l2 == null || l1 == null)
      return 0.0;
    float h1 = PDFToCRFInput.getH(l1);
    float h2 = PDFToCRFInput.getH(l2);
    return (PDFToCRFInput.getY(l2, true) - PDFToCRFInput.getY(l1, false)) / Math.min(h1, h2);
  }
  
  private static List<Double> getBreaks(PDFPage p) {
    PDFLine prevLine = null;
    ArrayList<Double> breaks = new ArrayList<>();
    for (PDFLine l : p.getLines()) {
      double bs = breakSize(l, prevLine);
      if (bs > 0) { //<= 0 due to math, tables, new pages, should be ignored
        breaks.add(bs);
      }
      prevLine = l;
    }
    breaks.sort(Double::compare);
    return breaks;
  }
  
  private static List<Double> getBreaks(PDFDoc pdf) {
    ArrayList<Double> breaks = new ArrayList<>();
    for (PDFPage p : pdf.getPages()) {
      breaks.addAll(getBreaks(p));
    }
    breaks.sort(Double::compare);
    return breaks;
  }
  
  public static double getReferenceLineBreak(PDFDoc pdf) {
    List<Double> breaks = getBreaks(pdf);
    if(breaks.isEmpty())
      return 1.0;
    int idx = (7 * breaks.size()) / 9; //hand-tuned threshold good for breaking references
    return breaks.get(idx);
  }
  
  public static double getRawBlockLineBreak(PDFDoc pdf) {
    List<Double> breaks = getBreaks(pdf);
    if(breaks.isEmpty())
      return 1.0;
    int idx = (7 * breaks.size()) / 9; //hand-tuned threshold good for breaking papers
    return breaks.get(idx);
  }
  
  public static double getFirstPagePartitionBreak(PDFPage pdf) {
    List<Double> breaks = getBreaks(pdf);
    if(breaks.isEmpty())
      return 1.0;
    int idx = (3 * breaks.size()) / 6; //hand-tuned threshold good for breaking first pages (abstracts)
    return breaks.get(idx) + 0.50;
  }

  private static String lineToString(PDFLine l) {
    StringBuilder sb = new StringBuilder();
    l.tokens.forEach(t -> { sb.append(t.token); sb.append(' '); } );
    return sb.toString().trim();
  }

  private static String cleanLine(String s) {
    s = s.replaceAll("\r|\t|\n", " ").trim();
    while (s.contains("  "))
      s = s.replaceAll("  ", " ");
    return s;
  }

  public static String getFirstTextBlock(PDFDoc pdf) {
    PDFPage fp = pdf.pages.get(0);
    double fpp = getFirstPagePartitionBreak(fp);
    StringBuilder out = new StringBuilder();
    PDFLine prevLine = null;
    boolean first = true;
    for(PDFLine l : fp.lines) {
      if(first) {
        first=false; //skip the first line (heuristic)
        continue;
      }
      if (breakSize(l, prevLine) > fpp) {
        if(out.length() > 400) { //hand-tuned threshold of min abstract length
          return out.toString().trim();
        } else {
          out.delete(0, out.length());
          out.append(' ');
          out.append(cleanLine(lineToString(l)));
        }
      }
      else {
        out.append(' ');
        out.append(cleanLine(lineToString(l)));
      }
      prevLine = l;
    }
    return "";
  }


  private final static Pattern inLineAbstractPattern =
    Pattern.compile("^abstract ?\\p{P}?", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);

  private final static Pattern[] generalAbstractCleaners = new Pattern[] {
    Pattern.compile("Key ?words(:| |\\.).*$", Pattern.UNICODE_CASE),
    Pattern.compile("KEY ?WORDS(:| |\\.).*$", Pattern.UNICODE_CASE),
    Pattern.compile("Key ?Words(:| |\\.).*$", Pattern.UNICODE_CASE),
    Pattern.compile("(1|I)\\.? Introduction.*$", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE),
    Pattern.compile("Categories and Subject Descriptors.*$", Pattern.UNICODE_CASE),
    Pattern.compile("0 [1-2][0-9]{3}.*$", Pattern.UNICODE_CASE),
    Pattern.compile("Contents.*$", Pattern.UNICODE_CASE),
    Pattern.compile("Index terms\\p{P}.*$", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE),
  };
  private final static Pattern paragraphAbstractCleaner =
    Pattern.compile("^summary ?\\p{P}?", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);

  public static String getAbstract(List<String> raw, PDFDoc pdf) {
    boolean inAbstract = false;
    StringBuilder out = new StringBuilder();
    for (String s : raw) {
      if (inAbstract) {
        if (s.length() < 20)
          break;
        else {
          out.append(' ');
          out.append(s.trim());
        }
      }

      if (s.toLowerCase().contains("abstract") && s.length() < 10) {
        inAbstract = true;
      } else if (s.toLowerCase().contains("a b s t r a c t")) {
        inAbstract = true;
      } else if (RegexWithTimeout.matcher(inLineAbstractPattern, s).find()) {
        out.append(RegexWithTimeout.matcher(inLineAbstractPattern, s).replaceFirst(""));
        inAbstract = true;
      }
    }
    String abs = out.toString().trim();
    if (abs.length() == 0) {
      //we didn't find an abstract.  Pull out the first paragraph-looking thing.
      abs = getFirstTextBlock(pdf);
      abs = RegexWithTimeout.matcher(paragraphAbstractCleaner, abs).replaceFirst("");
    }

    // remove keywords, intro from abstract
    for (Pattern p : generalAbstractCleaners) {
      abs = RegexWithTimeout.matcher(p, abs).replaceFirst("");
    }

    abs = abs.replaceAll("- ", "");
    return abs;
  }

  private static boolean lenientRefStart(PDFLine l, PDFLine prevLine, double qLineBreak) {
    final PDFToken firstToken = l.tokens.get(0);

    return (
          firstToken.token.equals("[1]") ||
          firstToken.token.equals("1.")
        ) && l.tokens.size() > 1 && (
            PDFToCRFInput.getX(l.tokens.get(1), true) > firstToken.fontMetrics.spaceWidth ||
            breakSize(l, prevLine) > qLineBreak
        );
  }
  
  public static Set<String> referenceHeaders = new HashSet<String>(Arrays.asList(
      "references",
      "citations",
      "bibliography",
      "reference",
      "bibliographie"));

  private static Pattern referenceStartPattern =
      Pattern.compile("^\\d{1,2}\\.|^\\[\\d{1,2}\\]");

  private static boolean gapAcrossMiddle(PDFToken t1, PDFToken t2, PDFPage p, float lineSpaceWidth) {
    double gap = PDFToCRFInput.getXGap(t1, t2);
    double pageCenter = p.getPageWidth() / 2.0;
    double gapCenter = (PDFToCRFInput.getX(t1, false) + PDFToCRFInput.getX(t2, true))/2.0;
    return gap > 5*lineSpaceWidth &&
            Math.abs(gapCenter - pageCenter) < 50*lineSpaceWidth; //lenient on center since margins might differ
  }

  /**
   * The lower-level processing sometimes fails to detect column breaks and/or fails to order
   * to column lines correctly.  This function attempts to repair that, returning column-broken lines
   * ordered left-to-right, top-to-bottom.
   * @param lines
   * @return
   */
  public static List<Pair<PDFPage, PDFLine>> repairColumns(List<Pair<PDFPage, PDFLine>> lines) {
    List<Pair<PDFPage, PDFLine>> out = new ArrayList<>();
    List<PDFLine> linesA = new ArrayList<>(); //holds new, broken lines
    PDFPage prevPage = null;
    for(final Pair<PDFPage, PDFLine> pageLinePair : lines) {
      PDFLine line = pageLinePair.getTwo();
      PDFPage page = pageLinePair.getOne();
      if(page != prevPage && prevPage != null) {
        final PDFPage comparePage = prevPage;
        linesA.sort((line1, line2) -> Double.compare(lineSorter(line1, comparePage), lineSorter(line2, comparePage)));
        for(PDFLine linea : linesA)
          out.add(Tuples.pair(prevPage, linea));
        linesA = new ArrayList<>();
      }
      List<PDFToken> lineAcc = new ArrayList<>();
      PDFToken prevToken = null;
      for(final PDFToken token : line.tokens) {
        if(prevToken != null) {
          if(gapAcrossMiddle(prevToken, token, page, prevToken.fontMetrics.spaceWidth)) {
            linesA.add(PDFLine.builder().tokens(new ArrayList<>(lineAcc)).build());
            lineAcc = new ArrayList<>();
          }
        }
        lineAcc.add(token);
        prevToken = token;
      }
      if(lineAcc.size() > 0)
        linesA.add(PDFLine.builder().tokens(new ArrayList<>(lineAcc)).build());
      prevPage = page;
    }
    final PDFPage comparePage = prevPage;
    linesA.sort((line1, line2) -> Double.compare(lineSorter(line1, comparePage), lineSorter(line2, comparePage)));
    for(PDFLine linea : linesA)
      out.add(Tuples.pair(prevPage, linea));
    return out;
  }

  private static double lineSorter(PDFLine line, PDFPage p) {
    return 1E8*(firstCol(line, p)?0.0:1.0) + PDFToCRFInput.getY(line, true);
  }

  private static boolean firstCol(PDFLine line, PDFPage p) {
    double pageThird = p.getPageWidth() / 3.0;
    return PDFToCRFInput.getX(line, true) < pageThird;
  }

  /**
   * Returns best guess of list of strings representation of the references of this file,
   * intended to be one reference per list element, using spacing and indentation as cues
   */
  public static List<String> getRawReferences(PDFDoc pdf) {
    PDFLine prevLine = null;
    boolean inRefs = false;
    boolean foundRefs = false;
    double qLineBreak = getReferenceLineBreak(pdf);
    boolean lenient = false;

    // Find reference lines in the document
    List<Pair<PDFPage, PDFLine>> referenceLines = new ArrayList<>();
    int totalLines = 0;
    for(int pass=0;pass<2;pass++) {
      int passLines = 0;
      if (pass == 1)
        if (foundRefs)
          break;
        else
          lenient = true; //try harder this time.
      for (PDFPage p : pdf.getPages()) {
        double farLeft = Double.MAX_VALUE; //of current column
        double farRight = -1.0; //of current column
        for (PDFLine l : p.getLines()) {
          if (pass == 0)
            totalLines++;
          passLines++;
          if (!inRefs && (l != null && l.tokens != null && l.tokens.size() > 0)) {
            if (
                    l.tokens.get(l.tokens.size() - 1).token != null &&
                            referenceHeaders.contains(
                                    l.tokens.get(l.tokens.size() - 1).token.trim().toLowerCase().replaceAll("\\p{Punct}*$", "")) &&
                            l.tokens.size() < 5
                    ) {
              inRefs = true;
              foundRefs = true;
              prevLine = l;
              continue; //skip this line
            } else if (lenient && passLines > totalLines / 4) { //used if we don't find refs on first pass; must not be in first 1/4 of doc
              if (lenientRefStart(l, prevLine, qLineBreak)) {
                inRefs = true;
                foundRefs = true;
                //DON'T skip this line.
              }
            }
          }
          if (inRefs)
            referenceLines.add(Tuples.pair(p, l));
          prevLine = l;
        }
      }
    }

    referenceLines = repairColumns(referenceLines);

    // find most common font sizes in references
    final MutableDoubleIntMap fontSize2count = DoubleIntMaps.mutable.empty();
    int tokenCount = 0;
    for(final Pair<PDFPage, PDFLine> pageLinePair : referenceLines) {
      final PDFLine l = pageLinePair.getTwo();
      tokenCount += l.tokens.size();
      for(final PDFToken t: l.tokens)
        fontSize2count.addToValue(t.fontMetrics.ptSize, 1);
    }



    // Filter out everything that's in a font size that makes up less than 4%

//    final int tc = tokenCount;
//    DoubleSet allowedFontSizes =
//            fontSize2count.reject((font, count) -> count < tc / 4).keySet();
//    if(allowedFontSizes.isEmpty())
//      allowedFontSizes = fontSize2count.keySet();

    // split reference lines into columns
    final List<List<PDFLine>> referenceLinesInColumns = new ArrayList<>();
    PDFPage lastPage = null;
    double currentColumnBottom = Double.MAX_VALUE;
    for(final Pair<PDFPage, PDFLine> pageLinePair : referenceLines) {
      final PDFPage p = pageLinePair.getOne();
      final PDFLine l = pageLinePair.getTwo();

      // remove empty lines
      if(l.tokens.isEmpty())
        continue;


      // remove tokens with weird font sizes

//      final DoubleSet af = allowedFontSizes;
//      l.tokens = l.tokens.stream().filter(t -> af.contains(t.fontMetrics.ptSize)).collect(Collectors.toList());
//      if(l.tokens.size() == 0)
//        continue;
//      if(l.tokens.stream().anyMatch(t -> !af.contains(t.fontMetrics.ptSize)))
//        continue;

      // Cut into columns. One column is a set of lines with continuously increasing Y coordinates.
      final double lineTop = PDFToCRFInput.getY(l, true);
      final double lineBottom = PDFToCRFInput.getY(l, false);
      if (p != lastPage || lineTop < currentColumnBottom) {
        final List<PDFLine> newColumn = new ArrayList<>();
        newColumn.add(l);
        referenceLinesInColumns.add(newColumn);
        currentColumnBottom = lineBottom;
      } else {
        referenceLinesInColumns.get(referenceLinesInColumns.size() - 1).add(l);
        currentColumnBottom = lineBottom;
      }
      lastPage = p;
    }

    // parse each column into output
    // We assume that the indentation of the first line of every column marks the start of a
    // reference (unless that indentation happens only once)
    final List<String> out = new ArrayList<String>();
    for(final List<PDFLine> column : referenceLinesInColumns) {
      if(column.size()==0) continue;
      // find indentation levels and right limits
      final MutableDoubleIntMap left2count = DoubleIntMaps.mutable.empty();
      final MutableDoubleIntMap right2count = DoubleIntMaps.mutable.empty();

      for(final PDFLine l : column) {

        double left = PDFToCRFInput.getX(l, true);
        double right = PDFToCRFInput.getX(l, false);
        final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;

        // find an indentation level that's close to this one
        // This is not proper clustering, but I don't think we need it.
        for(final double indentLevel : left2count.keySet().toArray()) {
          if(Math.abs(indentLevel - left) < lineSpaceWidth) {
            left = indentLevel;
            break;
          }
        }
        left2count.addToValue(left, 1);

        for(final double rightLevel : right2count.keySet().toArray()) {
          if(Math.abs(rightLevel - right) < lineSpaceWidth) {
            right = rightLevel;
            break;
          }
        }
        left2count.addToValue(left, 1);
        right2count.addToValue(right, 1);  }
      double modeRight = right2count.keyValuesView().maxBy(pair -> pair.getTwo()).getOne();

      // find the indentation that starts a reference
      double startReferenceIndentation = -1.0;
      final ImmutableList<DoubleIntPair> startReferenceIndentCandidates =
          left2count.keyValuesView().toSortedListBy(pair -> -pair.getTwo()).take(2).toImmutable();
      boolean useIndentation = false;
      if(startReferenceIndentCandidates.size() == 1) {
        startReferenceIndentation = startReferenceIndentCandidates.get(0).getOne();
      } else {
        useIndentation = true;
        final DoubleIntPair first = startReferenceIndentCandidates.get(0);
        final DoubleIntPair second = startReferenceIndentCandidates.get(1);
        if(first.getTwo() == second.getTwo()) { // If the counts for both are the same, we pick the first one we see.
          for(final PDFLine l : column) {
            final double left = PDFToCRFInput.getX(l, true);
            final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;
            if(Math.abs(first.getOne() - left) < lineSpaceWidth) {
              startReferenceIndentation = first.getOne();
              break;
            }
            if(Math.abs(second.getOne() - left) < lineSpaceWidth) {
              startReferenceIndentation = second.getOne();
              break;
            }
          }
        } else {
          startReferenceIndentation = second.getOne();
        }
      }

      int linesGrouped = 0; // We never group more than six lines together at a time.
      final StringBuilder builder = new StringBuilder();
      boolean brNext = false;
      for(final PDFLine l : column) {
        final double left = PDFToCRFInput.getX(l, true);
        final double right = PDFToCRFInput.getX(l, false);
        final String lineAsString = lineToString(l);
        final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;
        linesGrouped += 1;

        final boolean br = brNext ||
          linesGrouped >= 6 ||
                  (useIndentation && Math.abs(left-startReferenceIndentation) < lineSpaceWidth) ||
          referenceStartPattern.matcher(lineAsString).find();
        if(br) {
          // save old line
          final String outLine = cleanLine(builder.toString());
          if(!outLine.isEmpty())
            out.add(outLine);
          // start new line
          builder.setLength(0);
          builder.append(lineAsString);
          linesGrouped = 1;
        } else {
          builder.append("<lb>");
          builder.append(lineAsString);
        }

        brNext = right < modeRight - 2*lineSpaceWidth;
      }
      // save last line
      final String outLine = cleanLine(builder.toString());
      if(!outLine.isEmpty())
        out.add(outLine);
    }

    return out;
  }
}

