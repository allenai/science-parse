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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
  public ParserLMFeatures(
          List<Paper> ps,
          UnifiedSet<String> idsToExclude,
          File paperDirectory,
          int approxNumBackgroundDocs
  ) throws IOException {
    log.info("Excluding {} paper ids from LM features", idsToExclude.size());
    for(Paper p : ps) {
      if (!idsToExclude.contains(p.id)) {
        fillBow(titleBow, p.title, titleFirstBow, titleLastBow, false);
        for (String a : p.authors)
          fillBow(authorBow, a, authorFirstBow, authorLastBow, true);
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
        ct += fillBow(backgroundBow, tokens, null, null, false);
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

  public int fillBow(
          ObjectDoubleHashMap<String> hm,
          String s,
          ObjectDoubleHashMap<String> firstHM,
          ObjectDoubleHashMap<String> lastHM,
          boolean doTrim
  ) {
    if(s == null)
      return 0;
    else
      return fillBow(hm, tokenize(s), firstHM, lastHM, doTrim);
  }

  public int fillBow(ObjectDoubleHashMap<String> hm, String s, boolean doTrim) {
    return fillBow(hm, s, null, null, doTrim);
  }

  private int fillBow(
          ObjectDoubleHashMap<String> hm,
          String[] toks,
          ObjectDoubleHashMap<String> firstHM,
          ObjectDoubleHashMap<String> lastHM,
          boolean doTrim
  ) {
    int ct = 0;
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
    return ct;
  }

  private String[] tokenize(final String s) {
    return s.split("( |,)");  //not great
  }
}
