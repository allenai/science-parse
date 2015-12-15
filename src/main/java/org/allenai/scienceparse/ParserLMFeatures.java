package org.allenai.scienceparse;

import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import lombok.extern.slf4j.Slf4j;
import org.allenai.scienceparse.ParserGroundTruth.Paper;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

@Slf4j
public class ParserLMFeatures implements Serializable {

  /**
   *
   */
  private static final long serialVersionUID = 1L;

  ObjectDoubleHashMap<String> titleBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> titleFirstBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> titleLastBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> authorBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> authorFirstBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> authorLastBow = new ObjectDoubleHashMap<String>();
  ObjectDoubleHashMap<String> backgroundBow = new ObjectDoubleHashMap<String>();


  public ParserLMFeatures() {

  }

  //paperDirectory must contain pdf docs to use as background language model
  public ParserLMFeatures(List<Paper> ps, UnifiedSet<String> idsToExclude, int stIdx, int endIdx, File paperDirectory, int approxNumBackgroundDocs) throws IOException {
    log.info("excluding " + idsToExclude.size() + " paper ids from lm features.");
    for (int i = stIdx; i < endIdx; i++) {
      Paper p = ps.get(i);
      if (!idsToExclude.contains(p.id)) {
        fillBow(titleBow, p.title, titleFirstBow, titleLastBow, false);
        for (String a : p.authors)
          fillBow(authorBow, a, authorFirstBow, authorLastBow, true);
      }
    }
    File[] pdfs = paperDirectory.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File f, String s) {
        return s.endsWith(".pdf");
      }

      ;
    });
    double samp = ((double) approxNumBackgroundDocs) / ((double) pdfs.length);
    int ct = 0;
    for (int i = 0; i < pdfs.length; i++) {
      if (Math.random() < samp) {
        ct += fillBow(backgroundBow, Parser.paperToString(pdfs[i]), false);
      }
    }
    log.info("Gazetteer loaded with " + ct + " tokens.");
  }

  public int fillBow(ObjectDoubleHashMap<String> hm, String s, ObjectDoubleHashMap<String> firstHM, ObjectDoubleHashMap<String> lastHM,
                     boolean doTrim) {
    int ct = 0;
    if (s != null) {
      String[] toks = s.split("( |,)");  //not great
      if (toks.length > 0) {
        if (firstHM != null)
          firstHM.addToValue(doTrim ? Parser.trimAuthor(toks[0]) : toks[0], 1.0);
        if (lastHM != null)
          lastHM.addToValue(doTrim ? Parser.trimAuthor(toks[toks.length - 1]) : toks[toks.length - 1], 1.0);
      }
      for (String t : toks) {
        hm.addToValue(doTrim ? Parser.trimAuthor(t) : t, 1.0);
        ct++;
      }
    }
    return ct;
  }

  public int fillBow(ObjectDoubleHashMap<String> hm, String s, boolean doTrim) {
    return fillBow(hm, s, null, null, doTrim);
  }
}
