package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gs.collections.api.list.ImmutableList;
import com.gs.collections.api.map.primitive.DoubleIntMap;
import com.gs.collections.api.map.primitive.MutableDoubleIntMap;
import com.gs.collections.api.map.primitive.MutableFloatIntMap;
import com.gs.collections.api.map.primitive.MutableObjectIntMap;
import com.gs.collections.api.set.primitive.DoubleSet;
import com.gs.collections.api.set.primitive.MutableDoubleSet;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.api.tuple.primitive.DoubleIntPair;
import com.gs.collections.api.tuple.primitive.FloatIntPair;
import com.gs.collections.impl.factory.primitive.DoubleIntMaps;
import com.gs.collections.impl.factory.primitive.DoubleSets;
import com.gs.collections.impl.factory.primitive.FloatIntMaps;
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

  public static float breakSize(PDFLine l2, PDFLine l1) {
    if (l2 == null || l1 == null)
      return 0.0f;
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
    for(PDFToken token : l.tokens) {
      sb.append(token.token);
      sb.append(' ');
    }
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
    for(String s : raw) {
      if(inAbstract) {
        if(s.length() < 20)
          break;
        else {
          out.append(' ');
          out.append(s.trim());
        }
      }

      if(s.toLowerCase().contains("abstract") && s.length() < 10) {
        inAbstract = true;
      } else if(s.toLowerCase().contains("a b s t r a c t")) {
        inAbstract = true;
      } else if(RegexWithTimeout.matcher(inLineAbstractPattern, s).find()) {
        out.append(RegexWithTimeout.matcher(inLineAbstractPattern, s).replaceFirst(""));
        inAbstract = true;
      }
    }
    String abs = out.toString().trim();
    if(abs.length()==0) {
      //we didn't find an abstract.  Pull out the first paragraph-looking thing.
      abs = getFirstTextBlock(pdf);
      abs = RegexWithTimeout.matcher(paragraphAbstractCleaner, abs).replaceFirst("");
    }
    
    // remove keywords, intro from abstract
    for(Pattern p : generalAbstractCleaners) {
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
      Pattern.compile("^\\d{1,2}(\\.|\\s|$)|^\\[.+?\\]");

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

    ArrayList<Double> gaps = new ArrayList<>();

    double meanSpaceWidth = 0.0;

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
            gaps.add((PDFToCRFInput.getX(prevToken,false) + PDFToCRFInput.getX(token, true))/2.0);
            meanSpaceWidth += prevToken.fontMetrics.spaceWidth;
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
    //if gaps are consistent and there are at least ten of them:
    boolean useReorderedLines = false;
    if(gaps.size() > 10) {
      useReorderedLines = true;
      double mean = 0.0;
      meanSpaceWidth /= gaps.size();
      for(double d : gaps) {
        mean += d;
      }
      mean /= gaps.size();
      for(double d : gaps) {
        if(Math.abs(d - mean) > 3*meanSpaceWidth) {
          useReorderedLines = false;
          break;
        }
      }
    }
    if(useReorderedLines) {
      final PDFPage comparePage = prevPage;
      linesA.sort((line1, line2) -> Double.compare(lineSorter(line1, comparePage), lineSorter(line2, comparePage)));
      for (PDFLine linea : linesA)
        out.add(Tuples.pair(prevPage, linea));
      log.info("using re-ordered lines: " + out.size());
      return out;
    }
    else
      return lines;
  }

  private static double lineSorter(PDFLine line, PDFPage p) {
    return 1E8*(firstCol(line, p)?0.0:1.0) + PDFToCRFInput.getY(line, true);
  }

  private static boolean firstCol(PDFLine line, PDFPage p) {
    double pageThird = p.getPageWidth() / 3.0;
    return PDFToCRFInput.getX(line, true) < pageThird;
  }

  private static boolean referenceIsSplit(String lineOne, String lineTwo) {
    Matcher lineOneMatcher = referenceStartPattern.matcher(lineOne);
    return (lineOneMatcher.find() &&
            lineOneMatcher.end() == lineOne.length() &&
            !referenceStartPattern.matcher(lineTwo).find());
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
      if(pass==1)
        if(foundRefs)
          break;
        else
          lenient=true; //try harder this time.
      for (PDFPage p : pdf.getPages()) {
        double farLeft = Double.MAX_VALUE; //of current column
        double farRight = -1.0; //of current column
        for (PDFLine l : p.getLines()) {
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
            }
            else if (lenient && passLines > totalLines / 4) { //used if we don't find refs on first pass; must not be in first 1/4 of doc
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
        if(pass==0)
          totalLines++;
        passLines++;
      }
    }

    referenceLines = repairColumns(referenceLines);

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
      // find indentation levels
      final MutableFloatIntMap left2count = FloatIntMaps.mutable.empty();
      for(final PDFLine l : column) {
        final float left = PDFToCRFInput.getX(l, true);
        final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;

        // find an indentation level that's close to this one
        // This is not proper clustering, but I don't think we need it.
        float foundLeft = left;
        for(final float indentLevel : left2count.keySet().toArray()) {
          if(Math.abs(indentLevel - left) < lineSpaceWidth) {
            foundLeft = indentLevel;
            break;
          }
        }

        final int oldCount = left2count.getIfAbsent(foundLeft, 0);
        left2count.remove(foundLeft);
        left2count.put(
            (foundLeft * oldCount + left) / (oldCount + 1),
            oldCount + 1);
      }

      // find the indentation that starts a reference
      float startReferenceIndentation = -1000; // default is some number that's definitely more than one space width away from a realistic indent
      final ImmutableList<FloatIntPair> startReferenceIndentCandidates =
          left2count.keyValuesView().toSortedListBy(pair -> -pair.getTwo()).take(2).toImmutable();
      if(startReferenceIndentCandidates.size() > 1) {
        final FloatIntPair first = startReferenceIndentCandidates.get(0);
        final FloatIntPair second = startReferenceIndentCandidates.get(1);

        // find lines that look like they start references, and use them to determine which indent
        // starts a reference
        float firstIndentSeen = -1;
        for(final PDFLine l : column) {
          float left = PDFToCRFInput.getX(l, true);
          final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;

          // snap to candidate indentations
          if(Math.abs(first.getOne() - left) < lineSpaceWidth)
            left = first.getOne();
          else if(Math.abs(second.getOne() - left) < lineSpaceWidth)
            left = second.getOne();
          else
            continue; // only consider candidates

          if(firstIndentSeen < 0)
            firstIndentSeen = left;

          final String lineAsString = lineToString(l);
          if(referenceStartPattern.matcher(lineAsString).find()) {
            startReferenceIndentation = left;
            break;
          }
        }

        if(startReferenceIndentation < 0) {
          startReferenceIndentation = second.getOne();  // pick the one that's less common
        }
      }

      // find vertical spaces that might separate references
      final float breakSizeTolerance = 0.1f;
      MutableFloatIntMap vspace2count = FloatIntMaps.mutable.empty();
      for(int i = 1; i < column.size(); ++i) {
        final PDFLine l1 = column.get(i - 1);
        final PDFLine l2 = column.get(i);
        final float thisVspace = breakSize(l2, l1); // vspace is measured in line heights

        // if it's close to another break size, we should cluster them
        float foundVspace = thisVspace;
        for(final float v : vspace2count.keySet().toArray()) {
          if(Math.abs(v - thisVspace) <= breakSizeTolerance) {
            foundVspace = v;
            break;
          }
        }

        final int oldCount = vspace2count.getIfAbsent(foundVspace, 0);
        vspace2count.remove(foundVspace);
        vspace2count.put(
            (foundVspace * oldCount + thisVspace) / (oldCount + 1),
            oldCount + 1);
      }
      // filter down to reasonable vspaces
      final long numberOfBreaks = vspace2count.sum();
      vspace2count = vspace2count.select((vspace, count) ->
          count >= numberOfBreaks / 5 &&  // must account for 20% of breaks
          count > 1 &&                    // must occur at least once
          vspace < 3                      // more than 3 lines of gap is crazy
      );

      float breakReferenceVspace = -1.0f;
      if(column.size() > 5 && vspace2count.size() >= 2) // only if we have at least 5 lines
        breakReferenceVspace = vspace2count.keySet().max();

      int linesGrouped = 0; // We never group more than six lines together at a time.
      final StringBuilder builder = new StringBuilder();
      prevLine = null;
      for(final PDFLine l : column) {
        final float left = PDFToCRFInput.getX(l, true);
        final String lineAsString = lineToString(l);
        final float lineSpaceWidth = l.tokens.get(0).fontMetrics.spaceWidth;
        linesGrouped += 1;

        final boolean br =
          linesGrouped >= 6 || (
            breakReferenceVspace < 0 && // if we have vspace, we don't use indent
            startReferenceIndentation > 0 &&
            Math.abs(left-startReferenceIndentation) < lineSpaceWidth
          ) || referenceStartPattern.matcher(lineAsString).find() || (
            prevLine != null &&
            breakReferenceVspace > 0 &&
            breakSize(l, prevLine) >= breakReferenceVspace - breakSizeTolerance
          );

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
          if (!builder.toString().isEmpty())
            builder.append("<lb>");
          builder.append(lineAsString);
        }

        prevLine = l;
      }
      // save last line
      final String outLine = cleanLine(builder.toString());
      if(!outLine.isEmpty())
        out.add(outLine);
    }

    // If two columns were found incorrectly, the out array may consist of alternating
    // reference numbers and reference information. In this case, combine numbers with
    // the content that follows.
    List<String> mergedRefs = new ArrayList<String>();
    int i=0;
    while (i<out.size()) {
      String thisRef = out.get(i);
      String nextRef = i < out.size()-1 ? out.get(i+1) : null;
      if (nextRef != null && referenceIsSplit(thisRef, nextRef)) {
        mergedRefs.add(String.join(" ", thisRef, nextRef));
        i += 2;
      } else {
        mergedRefs.add(thisRef);
        i += 1;
      }
    }

    return mergedRefs;
  }
}
