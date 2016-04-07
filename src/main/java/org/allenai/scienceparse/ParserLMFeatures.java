package org.allenai.scienceparse;

import com.gs.collections.api.block.procedure.primitive.ObjectDoubleProcedure;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import lombok.extern.slf4j.Slf4j;
import org.allenai.scienceparse.ParserGroundTruth.Paper;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ParserLMFeatures implements Serializable {

  /**
   *
   */
  private static final long serialVersionUID = 1L;

  final ObjectDoubleHashMap<String> titleBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> titleFirstBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> titleLastBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> titleBagOfCharTrigrams = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> authorBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> authorFirstBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> authorLastBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> authorBagOfCharTrigrams = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> backgroundBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> venueBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> venueFirstBow = new ObjectDoubleHashMap<String>();
  final ObjectDoubleHashMap<String> venueLastBow = new ObjectDoubleHashMap<String>();


  public ParserLMFeatures() {

  }

  //paperDirectory must contain pdf docs to use as background language model
  public ParserLMFeatures(
          List<Paper> ps,
          UnifiedSet<String> idsToExclude,
          File paperDirectory,
          int approxNumBackgroundDocs
  ) throws IOException {
    log.info("Excluding {} paper ids from LM features", idsToExclude.size());
    for(Paper p : ps) {
      if (!idsToExclude.contains(p.id)) {
        fillBow(titleBow, p.title, titleFirstBow, titleLastBow, titleBagOfCharTrigrams, false);
        fillBow(venueBow, p.venue, venueFirstBow, venueLastBow, null, false);
        for (String a : p.authors)
          fillBow(authorBow, a, authorFirstBow, authorLastBow, authorBagOfCharTrigrams, true);
      }
    }

    log.info(
            "Getting token statistics from approximately {} background papers in {}",
            approxNumBackgroundDocs,
            paperDirectory);
    final ExecutorService executor =
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    final CompletionService<String[]> completionService = new ExecutorCompletionService<>(executor);
    try {
      final File[] pdfs = paperDirectory.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File f, String s) {
          return s.endsWith(".pdf");
        }
      });

      // put in the paper reading and tokenization tasks
      final double step = ((double) approxNumBackgroundDocs) / ((double) pdfs.length);
      double value = 0;
      int submittedPapers = 0;
      for(File pdf: pdfs) {
        value += step;
        if(value >= 1.0) {
          value -= 1.0;

          completionService.submit(() -> {
            final String paperString = Parser.paperToString(pdf);
            if(paperString != null) {
              return tokenize(paperString);
            } else {
              return null;
            }
          });

          submittedPapers += 1;
        }
      }

      // process the tokenized papers
      int ct = 0;
      int successfulPapers = 0;
      int failedPapers = 0;
      while(submittedPapers > successfulPapers + failedPapers) {
        final String[] tokens;
        try {
          tokens = completionService.take().get();
        } catch(final InterruptedException|ExecutionException e) {
          throw new RuntimeException(e);
        }

        if(tokens != null) {
        ct += fillBow(backgroundBow, tokens, null, null, null, false);
          successfulPapers += 1;
        } else {
          failedPapers += 1;
        }
      }
      log.info("Gazetteer loaded with {} tokens", ct);
      log.info(
              String.format(
                      "Tried %d papers, succeeded on %d (%.2f%%)",
                      submittedPapers,
                      successfulPapers,
                      100.0 * successfulPapers / (double)submittedPapers));

    } finally {
      executor.shutdown();
      try {
        executor.awaitTermination(10, TimeUnit.SECONDS);
      } catch(final InterruptedException e) {
        // do nothing
      }
    }
  }

  public static int fillBow(
          ObjectDoubleHashMap<String> hm,
          String s,
          ObjectDoubleHashMap<String> firstHM,
          ObjectDoubleHashMap<String> lastHM,
          ObjectDoubleHashMap<String> trigramHM,
          boolean doTrim
  ) {
    if(s == null)
      return 0;
    else
      return fillBow(hm, tokenize(s), firstHM, lastHM, trigramHM, doTrim);
  }

  public static int fillBow(ObjectDoubleHashMap<String> hm, String s, boolean doTrim) {
    return fillBow(hm, s, null, null, null, doTrim);
  }

  public static void addTrigrams(ObjectDoubleHashMap<String> hm, String t) {
    if(t == null)
      return;
    t = "^" + t + "$";
    int len = t.length();
    for(int i=0; i<len - 3; i++) {
      hm.addToValue(t.substring(i, i+3), 1.0);
    }
  }

  private static int fillBow(
          ObjectDoubleHashMap<String> hm,
          String[] toks,
          ObjectDoubleHashMap<String> firstHM,
          ObjectDoubleHashMap<String> lastHM,
          ObjectDoubleHashMap<String> trigramHM,
          boolean doTrim
  ) {
    int ct = 0;
    if (toks.length > 0) {
      if (firstHM != null)
        firstHM.addToValue(doTrim ? Parser.fixupAuthors(toks[0]) : toks[0], 1.0);
      if (lastHM != null)
        lastHM.addToValue(doTrim ? Parser.fixupAuthors(toks[toks.length - 1]) : toks[toks.length - 1], 1.0);
    }
    for (String t : toks) {
      hm.addToValue(doTrim ? Parser.fixupAuthors(t) : t, 1.0);
      if(trigramHM != null)
        addTrigrams(trigramHM, doTrim ? Parser.fixupAuthors(t) : t);
      ct++;
    }
    return ct;
  }

  private static String[] tokenize(final String s) {
    return s.split("( )");  //not great
  }

  private void logBow(final String name, final ObjectDoubleHashMap<String> bow) {
    log.debug("{}:", name);
    bow.forEachKeyValue(new ObjectDoubleProcedure<String>() {
      @Override
      public void value(final String key, final double value) {
        log.debug("{} = {}", key, value);
      }
    });
  }

  public void logState() {
    logBow("titleBow", titleBow);
    logBow("titleFirstBow", titleFirstBow);
    logBow("titleLastBow", titleLastBow);
    logBow("titleBagOfCharTrigrams", titleBagOfCharTrigrams);
    logBow("authorBow", authorBow);
    logBow("authorFirstBow", authorFirstBow);
    logBow("authorLastBow", authorLastBow);
    logBow("authorBagOfCharTrigrams", authorBagOfCharTrigrams);
    logBow("backgroundBow", backgroundBow);
    logBow("venueBow", venueBow);
    logBow("venueFirstBow", venueFirstBow);
    logBow("venueLastBow", venueLastBow);
  }
}
