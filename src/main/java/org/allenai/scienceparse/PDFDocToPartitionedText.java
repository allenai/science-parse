package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PDFDocToPartitionedText {
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

  public static double breakSize(PDFLine l2, PDFLine l1) {
    if (l2 == null || l1 == null)
      return 0.0;
    float h1 = PDFToCRFInput.getH(l1);
    float h2 = PDFToCRFInput.getH(l2);
    return (PDFToCRFInput.getY(l2, true) - PDFToCRFInput.getY(l1, false)) / Math.min(h1, h2);
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

  private static String lineToString(PDFLine l) {
    StringBuilder sb = new StringBuilder();
    l.tokens.forEach(t -> sb.append(t.token + " "));
    return sb.toString().trim();
  }

  private static String cleanLine(String s) {
    s = s.replaceAll("\r|\t|\n", " ").trim();
    while (s.contains("  "))
      s = s.replaceAll("  ", " ");
    return s;
  }
  
  public static String getAbstract(List<String> raw) {
    boolean inAbstract = false;
    int linesAdded = 0;
    StringBuffer out = new StringBuffer();
    for(String s : raw) {
      if(inAbstract) {
        if(s.length() < 20 || linesAdded > 2)
          return out.toString();
        else {
          out.append(s);
          linesAdded++;
        }
      }
      if(s.endsWith("Abstract") && s.length() < 10) {
        inAbstract = true;
      }
    }
    return "";
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
