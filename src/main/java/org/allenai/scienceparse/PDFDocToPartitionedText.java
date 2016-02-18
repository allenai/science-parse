package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;

import lombok.extern.slf4j.Slf4j;

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
  
  private static ArrayList<Double> getBreaks(PDFPage p) {
    PDFLine prevLine = null;
    ArrayList<Double> breaks = new ArrayList<>();
    for (PDFLine l : p.getLines()) {
      double bs = breakSize(l, prevLine);
      if (bs > 0) { //<= 0 due to math, tables, new pages, should be ignored
        breaks.add(bs);
      }
      prevLine = l;
    }
    breaks.sort((d1, d2) -> Double.compare(d1, d2));
    return breaks;
  }
  
  private static ArrayList<Double> getBreaks(PDFDoc pdf) {
    ArrayList<Double> breaks = new ArrayList<>();
    for (PDFPage p : pdf.getPages()) {
      breaks.addAll(getBreaks(p));
    }
    breaks.sort(Double::compare);
    return breaks;
  }
  
  public static double getReferenceLineBreak(PDFDoc pdf) {
    ArrayList<Double> breaks = getBreaks(pdf);
    if(breaks.isEmpty())
      return 1.0;
    int idx = (7 * breaks.size()) / 9; //hand-tuned threshold good for breaking references
    return breaks.get(idx);
  }
  
  public static double getRawBlockLineBreak(PDFDoc pdf) {
    ArrayList<Double> breaks = getBreaks(pdf);
    if(breaks.isEmpty())
      return 1.0;
    int idx = (7 * breaks.size()) / 9; //hand-tuned threshold good for breaking papers
    return breaks.get(idx);
  }
  
  public static double getFirstPagePartitionBreak(PDFPage pdf) {
    ArrayList<Double> breaks = getBreaks(pdf);
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
    StringBuffer out = new StringBuffer();
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
          out.append(" " + cleanLine(lineToString(l)));
        }
      }
      else {
        out.append(" " + cleanLine(lineToString(l)));
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
  
  /**
   * Returns best guess of list of strings representation of the references of this file,
   * intended to be one reference per list element, using spacing and indentation as cues
   */
  public static List<String> getRawReferences(PDFDoc pdf) {
    final List<String> refTags = Arrays.asList("References", "REFERENCES", "Citations", "CITATIONS", "Bibliography",
      "BIBLIOGRAPHY");
    List<String> out = new ArrayList<String>();
    PDFLine prevLine = null;
    boolean inRefs = false;
    double qLineBreak = getReferenceLineBreak(pdf);
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
          double left = PDFToCRFInput.getX(l, true);
          double right = PDFToCRFInput.getX(l, false);
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
            } else if (PDFToCRFInput.getX(prevLine, false) + l.tokens.get(0).fontMetrics.spaceWidth < farRight) {
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
}
