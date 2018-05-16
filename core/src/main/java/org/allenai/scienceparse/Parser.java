package org.allenai.scienceparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.set.MutableSet;
import com.gs.collections.api.set.primitive.MutableIntSet;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.factory.primitive.IntSets;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.gs.collections.impl.tuple.Tuples;

import lombok.Data;
import lombok.val;
import org.allenai.datastore.Datastore;
import org.allenai.ml.eval.TrainCriterionEval;
import org.allenai.ml.linalg.DenseVector;
import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.Evaluation;
import org.allenai.ml.sequences.StateSpace;
import org.allenai.ml.sequences.crf.CRFFeatureEncoder;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFTrainer;
import org.allenai.ml.sequences.crf.CRFWeightsEncoder;
import org.allenai.ml.util.IOUtils;
import org.allenai.ml.util.Indexer;
import org.allenai.ml.util.Parallel;
import org.allenai.pdffigures2.FigureExtractor;
import org.allenai.scienceparse.ExtractReferences.BibStractor;
import org.allenai.scienceparse.ParserGroundTruth.Paper;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.compat.java8.ScalaStreamSupport;
import scala.compat.java8.OptionConverters;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class Parser {

  public static final int MAXHEADERWORDS = 500; //set to something high for author/title parsing
  public static final String DATA_VERSION = "0.3";  // faster serialization
  private CRFModel<String, PaperToken, String> model;
  private ExtractReferences referenceExtractor;

  private final static Logger logger =
          LoggerFactory.getLogger(Parser.class);
  private final static Logger labeledDataLogger =
          LoggerFactory.getLogger(logger.getName() + ".labeledData");

  private static final Datastore datastore = Datastore.apply();
  public static Path getDefaultProductionModel() {
    return datastore.filePath("org.allenai.scienceparse", "productionModel.dat", 9);
  }
  public static Path getDefaultGazetteer() {
    return datastore.filePath("org.allenai.scienceparse", "gazetteer.json", 5);
  }
  public static Path getDefaultGazetteerDir() {
    return datastore.directoryPath("org.allenai.scienceparse", "kermit-gazetteers", 1);
  }
  public static Path getDefaultBibModel() {
    return datastore.filePath("org.allenai.scienceparse", "productionBibModel.dat", 7);
  }

  private static Parser defaultParser = null;
  public synchronized static Parser getInstance() throws Exception {
    if(defaultParser == null)
      defaultParser = new Parser();
    return defaultParser;
  }

  public Parser() throws Exception {
    this(getDefaultProductionModel(), getDefaultGazetteer(), getDefaultBibModel());
  }

  public Parser(
          final String modelFile,
          final String gazetteerFile,
          final String bibModelFile
  ) throws Exception {
    this(new File(modelFile), new File(gazetteerFile), new File(bibModelFile));
  }

  public Parser(
          final Path modelFile,
          final Path gazetteerFile,
          final Path bibModelFile
  ) throws Exception {
    this(modelFile.toFile(), gazetteerFile.toFile(), bibModelFile.toFile());
  }

  public Parser(
          final File modelFile,
          final File gazetteerFile,
          final File bibModelFile
  ) throws Exception {
    // Load main model in one thread, and the rest in another thread, to speed up startup.
    final AtomicReference<Exception> exceptionThrownByModelLoaderThread = new AtomicReference<>();
    final Thread modelLoaderThread = new Thread(new Runnable() {
      @Override
      public void run() {
        logger.info("Loading model from {}", modelFile);
        try(
            final DataInputStream modelIs =
                new DataInputStream(new FileInputStream(modelFile));
        ) {
          model = loadModel(modelIs);
          logger.info("Loaded model from {}", modelFile);
        } catch(final Exception e) {
          exceptionThrownByModelLoaderThread.compareAndSet(null, e);
          logger.warn("Failed loading model from {}", modelFile);
        }
      }
    }, "ModelLoaderThread");
    modelLoaderThread.start();

    // Load non-main model stuff
    logger.info("Loading gazetteer from {}", gazetteerFile);
    logger.info("Loading bib model from {}", bibModelFile);
    try(
      final InputStream gazetteerIs = new FileInputStream(gazetteerFile);
      final DataInputStream bibModelIs = new DataInputStream(new FileInputStream(bibModelFile))
    ) {
      // Loading the gazetteer takes a long time, so we create a cached binary version of it that
      // loads very quickly. If that version is already there, we use it. Otherwise, we create it.
      val gazCacheFilename = String.format(
          "%s-%08x.gazetteerCache.bin",
          gazetteerFile.getName(),
          gazetteerFile.getCanonicalPath().hashCode());
      val gazCachePath = Paths.get(System.getProperty("java.io.tmpdir"), gazCacheFilename);
      try (
        final RandomAccessFile gazCacheFile = new RandomAccessFile(gazCachePath.toFile(), "rw");
        final FileChannel gazCacheChannel = gazCacheFile.getChannel();
        final FileLock gazCacheLock = gazCacheChannel.lock();
      ) {
        if (gazCacheChannel.size() == 0) {
          logger.info("Creating gazetteer cache at {}", gazCachePath);
          referenceExtractor =
              ExtractReferences.createAndWriteGazCache(
                  gazetteerIs,
                  bibModelIs,
                  Channels.newOutputStream(gazCacheChannel));
        } else {
          logger.info("Reading from gazetteer cache at {}", gazCachePath);
          referenceExtractor = new ExtractReferences(
              gazetteerIs,
              bibModelIs,
              Channels.newInputStream(gazCacheChannel));
        }
      }
    }
    logger.info("Loaded gazetteer from {}", gazetteerFile);
    logger.info("Loaded bib model from {}", bibModelFile);

    // Close out the model loader thread and make sure the results are OK.
    modelLoaderThread.join();
    if(exceptionThrownByModelLoaderThread.get() != null)
      throw exceptionThrownByModelLoaderThread.get();
    assert(model != null);
  }

  public Parser(
          final InputStream modelStream,
          final InputStream gazetteerStream,
          final InputStream bibModelStream
  ) throws Exception {
    final DataInputStream dis = new DataInputStream(modelStream);
    model = loadModel(dis);
    referenceExtractor =
            new ExtractReferences(
                    gazetteerStream,
                    new DataInputStream(bibModelStream));
  }

  public static Pair<List<BibRecord>, List<CitationRecord>> getReferences(
    final List<String> raw,
    final List<String> rawReferences,
    final ExtractReferences er
  ) {
    final Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(rawReferences);
    final List<BibRecord> brs =
            fnd.getOne().stream().map(BibRecord::withNormalizedAuthors).collect(Collectors.toList());
    final BibStractor bs = fnd.getTwo();
    final List<CitationRecord> crs = ExtractReferences.findCitations(raw, brs, bs);
    return Tuples.pair(brs, crs);
  }

  //slow
  public static String paperToString(File f) {
    try {
      FileInputStream fis = new FileInputStream(f);
      PDFDoc doc = (new PDFExtractor()).extractFromInputStream(fis);
      fis.close();
      val seq = PDFToCRFInput.getSequence(doc);
      return PDFToCRFInput.stringAt(seq, Tuples.pair(0, seq.size()));
    } catch (Exception e) {
      return null;
    }
  }

  //from conll.Trainer:
  private static <T> Pair<List<T>, List<T>> splitData(List<T> original, double splitForSecond) {
    List<T> first = new ArrayList<>();
    List<T> second = new ArrayList<>();
    if (splitForSecond > 0.0) {
      Collections.shuffle(original, new Random(0L));
      int numFirst = (int) ((1.0 - splitForSecond) * original.size());
      first.addAll(original.subList(0, numFirst));
      second.addAll(original.subList(numFirst, original.size()));
    } else {
      first.addAll(original);
      // second stays empty
    }
    return Tuples.pair(first, second);
  }

  public static List<Pair<PaperToken, String>> getPaperLabels(
      final String paperId,
      final InputStream is,
      final LabeledData labeledData,
      PDFExtractor ext,
      int headerMax,
      boolean checkAuthors
  ) throws IOException {
    logger.debug("{}: starting", paperId);

    final PDFDoc doc;
    try {
      doc = ext.extractFromInputStream(is);
    } catch(final Exception e) {
      logger.warn("{} failed: {}", paperId, e.toString());
      return null;
    }
    if (doc == null) {
      return null;
    }

    List<PaperToken> seq = PDFToCRFInput.getSequence(doc);
    if (seq.size() == 0)
      return null;
    seq = seq.subList(0, Math.min(seq.size(), headerMax));
    List<Pair<PaperToken, String>> labeledPaper =
        PDFToCRFInput.labelMetadata(paperId, seq, labeledData);
    if (labeledPaper != null && checkAuthors) {
      final ExtractedMetadata checkEM =
              new ExtractedMetadata(
                      labeledPaper.stream().map(Pair::getOne).collect(Collectors.toList()),
                      labeledPaper.stream().map(Pair::getTwo).collect(Collectors.toList()));
      final Collection<String> expectedAuthorNames =
          labeledData.javaAuthorNames().orElse(Collections.emptyList());
      if(checkEM.authors.size() != expectedAuthorNames.size()) {
        logger.debug(
            "{}: author mismatch, discarding. Expected {}, got {}.",
            paperId,
            expectedAuthorNames,
            checkEM.authors);
        labeledPaper = null;
      }
    }

    return labeledPaper;
  }

  @Data
  private static class LabelingOutput {
    public final List<List<Pair<PaperToken, String>>> labeledData;
    public final Set<String> usedPaperIds;
  }

  private static LabelingOutput labelFromGroundTruth(
    final Iterator<LabeledPaper> labeledPapers,
    final int headerMax,
    final int maxFiles,
    final int minYear,
    final boolean checkAuthors,
    final Set<String> excludeIDs
  ) throws IOException {
    final PDFExtractor ext = new PDFExtractor();

    // input we need
    final int parallelism = Runtime.getRuntime().availableProcessors() * 2;
    final int queueSize = parallelism * 4;
    final Queue<Future<List<Pair<PaperToken, String>>>> workQueue = new ArrayDeque<>(queueSize);

    // stuff we need while processing
    final ExecutorService executor = Executors.newFixedThreadPool(parallelism);
    final AtomicInteger triedCount = new AtomicInteger();
    final ConcurrentMap<String, Long> paperId2startTime = new ConcurrentSkipListMap<>();

    // capturing results
    final ArrayList<List<Pair<PaperToken, String>>> results =
        new ArrayList<>(maxFiles > 0 ? maxFiles : 1024);
    final MutableSet<String> usedPaperIds = new UnifiedSet<String>().asSynchronized();

    try {
      if(maxFiles > 0)
        logger.info("Will be labeling {} papers in {} threads.", maxFiles, parallelism);
      else
        logger.info("Will be labeling all papers in {} threads.", parallelism);

      while (labeledPapers.hasNext() && (maxFiles <= 0 || results.size() < maxFiles)) {
        // fill up the queue
        while (labeledPapers.hasNext() && workQueue.size() < queueSize) {
          final LabeledPaper paper = labeledPapers.next();
          final LabeledData labels = paper.labels();

          val title = labels.javaTitle();
          val authors = labels.javaAuthors();

          if (excludeIDs.contains(paper.paperId()))
            continue;

          if(minYear > 0) {
            val year = labels.javaYear();
            if(!year.isPresent() || year.getAsInt() < minYear)
              continue;
          }

          final Future<List<Pair<PaperToken, String>>> future = executor.submit(() -> {
            try {
              paperId2startTime.put(paper.paperId(), System.currentTimeMillis());
              final List<Pair<PaperToken, String>> result;
              try(final InputStream is = paper.inputStream()) {
                result = getPaperLabels(
                    paper.paperId(),
                    is,
                    labels,
                    ext,
                    headerMax,
                    checkAuthors);
              }

              int tried = triedCount.incrementAndGet();
              if (result != null)
                usedPaperIds.add(paper.paperId());
              if (tried % 100 == 0) {
                // find the document that's been in flight the longest
                final long now = System.currentTimeMillis();
                final Set<Map.Entry<String, Long>> entries = paperId2startTime.entrySet();
                final Optional<Map.Entry<String, Long>> maxEntry =
                        entries.stream().max(
                                new Comparator<Map.Entry<String, Long>>() {
                                  @Override
                                  public int compare(
                                          final Map.Entry<String, Long> left,
                                          final Map.Entry<String, Long> right
                                  ) {
                                    final long l = now - left.getValue();
                                    final long r = now - right.getValue();
                                    if (l < r)
                                      return -1;
                                    else if (l > r)
                                      return 1;
                                    else
                                      return 0;
                                  }
                                });

                final int succeeded = usedPaperIds.size();
                if (maxEntry.isPresent()) {
                  logger.info(String.format(
                          "Tried to label %d papers, succeeded %d times (%.2f%%), %d papers in flight, oldest is %s at %.2f seconds",
                          tried,
                          succeeded,
                          succeeded * 100.0 / (double) tried,
                          entries.size(),
                          maxEntry.get().getKey(),
                          (now - maxEntry.get().getValue()) / 1000.0));
                } else {
                  logger.info(String.format(
                          "Tried to label %d papers, succeeded %d times (%.2f%%), 0 papers in flight",
                          tried,
                          succeeded,
                          succeeded * 100.0 / (double) tried));
                }
              }

              return result;
            } catch (final IOException e) {
              logger.warn(
                  "IOException {} while processing paper {}",
                  e.getMessage(),
                  paper.paperId());
              return null;
            } finally {
              paperId2startTime.remove(paper.paperId());
            }
          });
          workQueue.add(future);
        }

        // read from the queue
        val top = workQueue.poll();
        try {
          val result = top.get();
          if(result != null)
            results.add(result);
        } catch (final InterruptedException e) {
          logger.warn("Interrupted while processing paper", e);
        } catch (final ExecutionException e) {
          logger.warn("ExecutionException while processing paper", e);
        }
      }

      // read the queue empty if we have to
      while(!workQueue.isEmpty() && (maxFiles <= 0 || results.size() < maxFiles)) {
        try {
          val result = workQueue.poll().get();
          if(result != null)
            results.add(result);
        } catch (final InterruptedException e) {
          logger.warn("Interrupted while processing paper", e);
        } catch (final ExecutionException e) {
          logger.warn("ExecutionException while processing paper", e);
        }
      }
    } finally {
      executor.shutdown();
      try {
        logger.info("Done labeling papers. Waiting for threads to shut down.");
        executor.awaitTermination(10, TimeUnit.MINUTES);
      } catch(final InterruptedException e) {
        logger.warn("Interrupted while waiting for the executor to shut down. We may be leaking threads.", e);
      }
    }

    logger.info(String.format(
            "Tried to label %d papers, succeeded %d times (%.2f%%), all done!",
            triedCount.get(),
            usedPaperIds.size(),
            usedPaperIds.size() * 100.0 / triedCount.doubleValue()));

    return new LabelingOutput(results, usedPaperIds.asUnmodifiable());
  }

  private static UnifiedSet<String> readSet(String inFile) throws IOException {
    val out = new UnifiedSet<String>();
    BufferedReader brIn = new BufferedReader(new FileReader(inFile));
    String sLine;
    while ((sLine = brIn.readLine()) != null) {
      out.add(sLine);
    }
    brIn.close();
    return out;
  }

  public static void trainBibliographyCRF(
          final File bibsGroundTruthDirectory,
          final ParseOpts opts
  ) throws IOException {
    final File coraTrainFile = new File(bibsGroundTruthDirectory, "cora-citations.txt");
    final File umassTrainFile = new File(bibsGroundTruthDirectory, "umass-citations.txt");
    final File kermitTrainFile = new File(bibsGroundTruthDirectory, "kermit-citations.txt");
    trainBibliographyCRF(coraTrainFile, umassTrainFile, kermitTrainFile, opts);
  }
  
  public static void trainBibliographyCRF(File coraTrainFile, File umassTrainFile, File kermitTrainFile, ParseOpts opts) throws IOException {
    List<List<Pair<String, String>>> labeledData;
    labeledData = CRFBibRecordParser.labelFromCoraFile(coraTrainFile);
    if(umassTrainFile != null)
      labeledData.addAll(CRFBibRecordParser.labelFromUMassFile(umassTrainFile));
    if(kermitTrainFile != null)
      labeledData.addAll(CRFBibRecordParser.labelFromKermitFile(kermitTrainFile));
    ReferencesPredicateExtractor predExtractor;
    
    GazetteerFeatures gf = null;
    try {
    if(opts.gazetteerDir != null)
      gf = new GazetteerFeatures(opts.gazetteerDir);
    }
    catch (IOException e) {
      logger.error("Error importing gazetteer directory, ignoring.");
    }
    ParserLMFeatures plf = null;
    if(opts.gazetteerFile != null) {
      ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
      plf = new ParserLMFeatures(
          gaz.papers,
          new UnifiedSet<>(),
          new File(opts.backgroundDirectory),
          opts.backgroundSamples);
      predExtractor = new ReferencesPredicateExtractor(plf);
    } else {
      predExtractor = new ReferencesPredicateExtractor();
    }
    predExtractor.setGf(gf);
    
    // Split train/test data
    logger.info("CRF training for bibs with {} threads and {} labeled examples", opts.threads, labeledData.size());
    Pair<List<List<Pair<String, String>>>, List<List<Pair<String, String>>>> trainTestPair =
      splitData(labeledData, 1.0 - opts.trainFraction);
    val trainLabeledData = trainTestPair.getOne();
    val testLabeledData = trainTestPair.getTwo();

    // Set up Train options
    CRFTrainer.Opts trainOpts = new CRFTrainer.Opts();
    trainOpts.optimizerOpts.maxIters = opts.iterations;
    trainOpts.numThreads = opts.threads;
    trainOpts.minExpectedFeatureCount = opts.minExpectedFeatureCount;

    // set up evaluation function
    final Parallel.MROpts evalMrOpts =
            Parallel.MROpts.withIdAndThreads("mr-crf-bib-train-eval", opts.threads);
    final ToDoubleFunction<CRFModel<String, String, String>> testEvalFn;
    {
      final List<List<Pair<String, String>>> testEvalData =
              testLabeledData.
                      stream().
                      map(x -> x.stream().map(Pair::swap).collect(toList())).
                      collect(toList());
      testEvalFn = (model) -> {
        final Evaluation<String> eval = Evaluation.compute(model, testEvalData, evalMrOpts);
        return eval.tokenAccuracy.accuracy();
      };
    }

    // set up early stopping so that we stop training after 50 down-iterations
    final TrainCriterionEval<CRFModel<String, String, String>> earlyStoppingEvaluator =
            new TrainCriterionEval<>(testEvalFn);
    earlyStoppingEvaluator.maxNumDipIters = 100;
    trainOpts.iterCallback = earlyStoppingEvaluator;

    // training
    CRFTrainer<String, String, String> trainer =
      new CRFTrainer<>(trainLabeledData, predExtractor, trainOpts);
    trainer.train(trainLabeledData);
    final CRFModel<String, String, String> crfModel = earlyStoppingEvaluator.bestModel;

    Vector weights = crfModel.weights();
    Parallel.shutdownExecutor(evalMrOpts.executorService, Long.MAX_VALUE);

    val dos = new DataOutputStream(new FileOutputStream(opts.modelFile));
    logger.info("Writing model to {}", opts.modelFile);
    saveModel(dos, crfModel.featureEncoder, weights, plf, gf, ExtractReferences.DATA_VERSION);
    dos.close();
  }

  public static void trainParser(
      final Iterator<LabeledPaper> labeledTrainingData,
      final ParseOpts opts
  ) throws IOException {
    trainParser(labeledTrainingData, opts, UnifiedSet.newSet());
  }

  public static void trainParser(
      final Iterator<LabeledPaper> labeledTrainingData,
      final ParseOpts opts,
      final String excludeIDsFile
  ) throws IOException {
    final UnifiedSet<String> excludedIDs;
    if (excludeIDsFile == null)
      excludedIDs = new UnifiedSet<>();
    else
      excludedIDs = readSet(excludeIDsFile);
    trainParser(labeledTrainingData, opts, excludedIDs);
  }

  public static void trainParser(
      Iterator<LabeledPaper> labeledTrainingData,
      ParseOpts opts,
      final UnifiedSet<String> excludeIDs
  ) throws IOException {
    final LabelingOutput labelingOutput = labelFromGroundTruth(
        labeledTrainingData,
        opts.headerMax,
        opts.documentCount,
        opts.minYear,
        opts.checkAuthors,
        excludeIDs);
    ParserLMFeatures plf = null;
    final PDFPredicateExtractor predExtractor;
    if (opts.gazetteerFile != null) {
      ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
      UnifiedSet<String> excludedIds = new UnifiedSet<String>(labelingOutput.usedPaperIds);
      excludedIds.addAll(excludeIDs);
      plf = new ParserLMFeatures(
              gaz.papers,
              excludedIds,
              new File(opts.backgroundDirectory),
              opts.backgroundSamples);
      predExtractor = new PDFPredicateExtractor(plf);
    } else {
      predExtractor = new PDFPredicateExtractor();
    }

    // Split train/test data
    logger.info(
            "CRF training with {} threads and {} labeled examples",
            opts.threads,
            labelingOutput.labeledData.size());
    Pair<List<List<Pair<PaperToken, String>>>, List<List<Pair<PaperToken, String>>>> trainTestPair =
      splitData(labelingOutput.labeledData, 1.0 - opts.trainFraction);
    val trainLabeledData = trainTestPair.getOne();
    val testLabeledData = trainTestPair.getTwo();

    // log test and training data
    if(labeledDataLogger.isDebugEnabled()) {
      labeledDataLogger.info("Training data before:");
      logLabeledData(trainLabeledData);

      labeledDataLogger.info("Test data before:");
      logLabeledData(testLabeledData);
    }

    // Set up Train options
    CRFTrainer.Opts trainOpts = new CRFTrainer.Opts();
    trainOpts.optimizerOpts.maxIters = opts.iterations;
    trainOpts.numThreads = opts.threads;
    trainOpts.minExpectedFeatureCount = opts.minExpectedFeatureCount;

    // set up evaluation function
    final Parallel.MROpts evalMrOpts =
            Parallel.MROpts.withIdAndThreads("mr-crf-train-eval", opts.threads);
    final ToDoubleFunction<CRFModel<String, PaperToken, String>> testEvalFn;
    {
      final List<List<Pair<String, PaperToken>>> testEvalData =
              testLabeledData.
                      stream().
                      map(x -> x.stream().map(Pair::swap).collect(toList())).
                      collect(toList());
      testEvalFn = (model) -> {
        final Evaluation<String> eval = Evaluation.compute(model, testEvalData, evalMrOpts);
        logger.info("Test Label F-measures");
        eval.stateFMeasures.forEach((label, fMeasure) -> {
          logger.info(String.format("-- %s: p:%.3f r:%.3f f1:%.3f",
              label, fMeasure.precision(), fMeasure.recall(), fMeasure.f1()));
        });
        logger.info("");
        return eval.tokenAccuracy.accuracy();
      };
    }

    // set up early stopping so that we stop training after 50 down-iterations
    final TrainCriterionEval<CRFModel<String, PaperToken, String>> earlyStoppingEvaluator =
            new TrainCriterionEval<>(testEvalFn);
    earlyStoppingEvaluator.maxNumDipIters = 100;
    trainOpts.iterCallback = earlyStoppingEvaluator;

    // training
    final CRFTrainer<String, PaperToken, String> trainer =
      new CRFTrainer<>(trainLabeledData, predExtractor, trainOpts);
    trainer.train(trainLabeledData);
    final CRFModel<String, PaperToken, String> crfModel = earlyStoppingEvaluator.bestModel;

    final Vector weights = crfModel.weights();
    Parallel.shutdownExecutor(evalMrOpts.executorService, Long.MAX_VALUE);

    try(val dos = new DataOutputStream(new FileOutputStream(opts.modelFile))) {
      logger.info("Writing model to {}", opts.modelFile);
      saveModel(dos, crfModel.featureEncoder, weights, plf);
    }

    // log test and training data
    if(labeledDataLogger.isDebugEnabled()) {
      labeledDataLogger.info("Training data after:");
      logLabeledData(trainLabeledData);

      labeledDataLogger.info("Test data after:");
      logLabeledData(testLabeledData);
    }
  }

  private static void logLabeledData(final List<List<Pair<PaperToken, String>>> data) {
    for(List<Pair<PaperToken, String>> line : data) {
      final String logLine = line.stream().map(pair ->
              String.format(
                      "%s/%x/%s",
                      pair.getOne().getPdfToken() == null ?
                              "null" :
                              pair.getOne().getPdfToken().getToken(),
                      pair.getOne().hashCode(),
                      pair.getTwo())).collect(Collectors.joining(" "));
      labeledDataLogger.info(logLine);
    }
  }

  public static <T> void saveModel(
    final DataOutputStream dos,
    final CRFFeatureEncoder<String, T, String> fe,
    final Vector weights,
    final ParserLMFeatures plf,
    final String dataVersion
  ) throws IOException {
    saveModel(dos, fe,weights, plf, null, dataVersion);
  }
  
  public static <T> void saveModel(
    final DataOutputStream dos,
    final CRFFeatureEncoder<String, T, String> fe,
    final Vector weights,
    final ParserLMFeatures plf,
    final GazetteerFeatures gf,
    final String dataVersion
  ) throws IOException {
    dos.writeUTF(dataVersion);
    fe.stateSpace.save(dos);
    fe.nodeFeatures.save(dos);
    fe.edgeFeatures.save(dos);
    IOUtils.saveDoubles(dos, weights.toDoubles());

    logger.debug("Saving ParserLMFeatures");
    try(final FSTObjectOutput out = new FSTObjectOutput(dos)) {
      out.writeObject(plf);
      if (plf != null)
        plf.logState();

      logger.debug("Saving gazetteer features");
      out.writeObject(gf);
    }
  }

  public static <T> void saveModel(
    final DataOutputStream dos,
    final CRFFeatureEncoder<String, T, String> fe,
    final Vector weights,
    final ParserLMFeatures plf
  ) throws IOException {
    saveModel(dos, fe, weights, plf, Parser.DATA_VERSION);
  }

  @Data
  public static class ModelComponents {
    public final PDFPredicateExtractor predExtractor;
    public final CRFFeatureEncoder<String, PaperToken, String> featureEncoder;
    public final CRFWeightsEncoder<String> weightsEncoder;
    public final CRFModel<String, PaperToken, String> model;
  }

  public static ModelComponents loadModelComponents(
    final DataInputStream dis,
    String dataVersion
  ) throws IOException {
    IOUtils.ensureVersionMatch(dis, dataVersion);
    val stateSpace = StateSpace.load(dis);
    Indexer<String> nodeFeatures = Indexer.load(dis);
    Indexer<String> edgeFeatures = Indexer.load(dis);
    Vector weights = DenseVector.of(IOUtils.loadDoubles(dis));

    logger.debug("Loading ParserLMFeatures");
    final ParserLMFeatures plf;
    try(final FSTObjectInput in = new FSTObjectInput(dis)) {
      try {
        plf = (ParserLMFeatures) in.readObject();
      } catch (final ClassNotFoundException e) {
        throw new IOException("Model file contains unknown class.", e);
      }
    }
    if(plf != null && logger.isDebugEnabled())
      plf.logState();

    val predExtractor = new PDFPredicateExtractor(plf);
    val featureEncoder = new CRFFeatureEncoder<String, PaperToken, String>
            (predExtractor, stateSpace, nodeFeatures, edgeFeatures);
    val weightsEncoder = new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());
    val model = new CRFModel<String, PaperToken, String>(featureEncoder, weightsEncoder, weights);
    return new ModelComponents(predExtractor, featureEncoder, weightsEncoder, model);
  }

  public static ModelComponents loadModelComponents(
    final DataInputStream dis
  ) throws IOException {
    return loadModelComponents(dis, DATA_VERSION);
  }

  public static CRFModel<String, PaperToken, String> loadModel(
    final DataInputStream dis
  ) throws IOException {
    return loadModelComponents(dis).model;
  }

  public static void clean(ExtractedMetadata em) {
    em.title = cleanTitle(em.title);
    em.authors = trimAuthors(em.authors);
  }

  public static String cleanTitle(String t) {
    if (t == null || t.length() == 0)
      return t;
    // strip accents and unicode changes
    t = Normalizer.normalize(t, Normalizer.Form.NFKD);
    // kill non-character letters
    // kill xml
    t = t.replaceAll("\\&.*?\\;", "");
    if (t.endsWith("."))
      t = t.substring(0, t.length() - 1);
    return t;
  }

  public static String processTitle(String t) {
    // case fold and remove lead/trail space
    if(t==null || t.length()==0)
      return t;
    t = t.trim().toLowerCase();
    t = cleanTitle(t);
    // kill non-letter chars
    t = t.replaceAll("\\W", "");
    return t.replaceAll("\\s+", " ");
  }

  //changes extraction to remove common failure modes
  public static String processExtractedTitle(String t) {
    String out = t.replaceAll("(?<=[a-z])\\- ", ""); //continuation dash
    if (!out.endsWith("?") && !out.endsWith("\"") && !out.endsWith(")"))
      out = out.replaceFirst("\\W$", ""); //end of title punctuation if not ?, ", or )
    return out.trim();
  }

  //lowercases
  public static String lastName(String s) {
    String[] words = s.split(" ");
    if (words.length > 0)
      return processTitle(words[words.length - 1]);
    else
      return "";
  }

  public static List<String> lastNames(List<String> ss) {
    return (ss==null)?null:ss.stream().map(s -> lastName(s)).collect(Collectors.toList());
  }

  public static int scoreAuthors(String[] expected, List<String> guessed) {
    List<String> guessLastName = lastNames(guessed);
    List<String> expectedLastName = lastNames(Arrays.asList(expected));
    //slow:
    int truePos = 0;
    for (String s : expectedLastName) {
      if (guessLastName.contains(s))
        truePos++;
    }
    return truePos;
  }

  /** Fixes common author problems. This is applied to the output of both normalized and
   * unnormalized author names, and it is used in training as well. Experience shows that if you
   * make changes here, there is a good chance you'll need to retrain, even if you think the change
   * is fairly trivial. */
  public static String fixupAuthors(String s) {
    // delete trailing special characters
    String sFix = s.replaceAll("([^\\p{javaLowerCase}\\p{javaUpperCase}])+$", "");
    if (sFix.contains(","))
      sFix = sFix.substring(0, sFix.indexOf(","));
    if (sFix.endsWith("Jr"))
      sFix = sFix + ".";
    return sFix;
  }

  private final static int MAX_AUTHOR_LENGTH = 32;
  private final static int MIN_AUTHOR_LENGTH = 2;
  public static List<String> trimAuthors(List<String> auth) {
    return auth.stream().
            flatMap(s -> Arrays.stream(s.split("(?!,\\s*Jr),|\\band\\b"))).
            map(String::trim).
            map(Parser::fixupAuthors).
            filter(s -> !s.isEmpty()).
            filter(s -> s.length() <= MAX_AUTHOR_LENGTH).
            filter(s -> s.length() >= MIN_AUTHOR_LENGTH).
            distinct().
            collect(Collectors.toList());
  }

  public static class ParsingTimeout extends RuntimeException { }
  private final Timer parserKillerTimer = new Timer("Science-parse killer timer", true);
  private final MutableIntSet parseNumbersInProgress = IntSets.mutable.empty();
  private final AtomicInteger nextParseNumber = new AtomicInteger();
  public ExtractedMetadata doParseWithTimeout(final InputStream is, final long timeoutInMs) throws IOException {
    final int parseNumber = nextParseNumber.getAndIncrement();

    final Thread t = Thread.currentThread();

    final TimerTask killTaskHard = new TimerTask() {
      @Override
      public void run() {
        synchronized (parseNumbersInProgress) {
          if(parseNumbersInProgress.contains(parseNumber)) {
            logger.info("Killing parsing thread {} because it's taking too long", t.getId());
            t.stop();
            // I know this is dangerous. This is a last resort.
          }
        }
      }
    };

    final TimerTask killTaskSoftly = new TimerTask() {
      @Override
      public void run() {
        synchronized (parseNumbersInProgress) {
          if(parseNumbersInProgress.contains(parseNumber)) {
            logger.info("Interrupting parsing thread {} because it's taking too long", t.getId());
            t.interrupt();
          }
        }
      }
    };

    synchronized (parseNumbersInProgress) {
      parseNumbersInProgress.add(parseNumber);
    }

    final ExtractedMetadata result;
    final boolean wasInterrupted;
    parserKillerTimer.schedule(killTaskSoftly, timeoutInMs);
    parserKillerTimer.schedule(killTaskHard, 3*timeoutInMs);
    try {
      try {
        result = doParse(is);
      } catch(final ThreadDeath e) {
        throw new RuntimeException("Science-parse killer got impatient", e);
      }
    } finally {
      synchronized (parseNumbersInProgress) {
        parseNumbersInProgress.remove(parseNumber);
      }

      // This clears the interrupted flag, in case it happened after we were already done parsing,
      // but before we could remove the parse number. We don't want to leave this function with
      // the interrupted flag set on the thread.
      // Actually, the window of opportunity is from the last time that doParse() checks the
      // flag to the time we remove the parse number, which is quite a bit bigger.
      wasInterrupted = Thread.interrupted();
    }

    if(wasInterrupted)
      logger.info("Overriding interruption of parsing thread {} because it finished before we could react", t.getId());
    assert !Thread.interrupted();

    return result;
  }

  public ExtractedMetadata doParse(final InputStream is) throws IOException {
    return doParse(is, MAXHEADERWORDS);
  }

  /**
   * Given a body of text (e.g. an entire section of a paper) within which a citation is mentioned, extracts a
   * single-sentence context from that larger body of text. Used both here for Science Parse extraction and in
   * GrobidParser for evaluation of Grobid citation mention extraction.
   */
  public static CitationRecord extractContext(int referenceID, String context, int begin, int end) {
    int sentenceStart = context.substring(0, begin).lastIndexOf('.') + 1; // this evaluates to 0 if '.' is not found

    // Trim away superscripts at the beginning of sentences.
    if(context.charAt(sentenceStart) == '⍐') {
      final int newSentenceStart = context.indexOf('⍗', sentenceStart);
      if(newSentenceStart > 0 && newSentenceStart < begin)
        sentenceStart = newSentenceStart + 1;
    }

    int crSentenceEnd = context.indexOf('.', end);
    if(crSentenceEnd < 0)
      crSentenceEnd = context.length();
    else
      crSentenceEnd += 1;

    String contextSentenceUntrimmed = context.substring(sentenceStart, crSentenceEnd);
    String contextSentence = contextSentenceUntrimmed.trim();
    sentenceStart += contextSentenceUntrimmed.indexOf(contextSentence);
    return new CitationRecord(referenceID, contextSentence, begin - sentenceStart,
            end - sentenceStart);
  }

  public ExtractedMetadata doParse(final InputStream is, int headerMax) throws IOException {
    final ExtractedMetadata em;
    final PDDocument pdDoc = PDDocument.load(is);

    //
    // Run Science-parse
    //
    {
      PDFExtractor ext = new PDFExtractor();
      PDFDoc doc = ext.extractResultFromPDDocument(pdDoc).document;

      List<PaperToken> seq = PDFToCRFInput.getSequence(doc.withoutSuperscripts());
      seq = seq.subList(0, Math.min(seq.size(), headerMax));
      seq = PDFToCRFInput.padSequence(seq);

      { // get title and authors from the CRF
        List<String> outSeq = model.bestGuess(seq);
        //the output tag sequence will not include the start/stop states!
        outSeq = PDFToCRFInput.padTagSequence(outSeq);
        em = new ExtractedMetadata(seq, outSeq);
        em.source = ExtractedMetadata.Source.CRF;
      }

      // use PDF metadata if it's there
      if (doc.meta != null) {
        if (doc.meta.title != null) {
          em.setTitle(doc.meta.title);
          em.source = ExtractedMetadata.Source.META;
        }
        if (doc.meta.createDate != null)
          em.setYearFromDate(doc.meta.createDate);
      }

      clean(em);
      final List<String> lines = PDFDocToPartitionedText.getRaw(doc);

      em.creator = doc.meta.creator;
      // extract references
      try {
        final List<String> rawReferences = PDFDocToPartitionedText.getRawReferences(doc);
        final Pair<List<BibRecord>, List<CitationRecord>> pair =
            getReferences(lines, rawReferences, referenceExtractor);
        em.references = pair.getOne();
        List<CitationRecord> crs = new ArrayList<>();
        for (CitationRecord cr : pair.getTwo()) {
          final CitationRecord crWithContext =
              extractContext(cr.referenceID, cr.context, cr.startOffset, cr.endOffset);
          final int contextLength =
              crWithContext.context.length() -
              (crWithContext.endOffset - crWithContext.startOffset);
          if(contextLength >= 10) // Heuristic number
            crs.add(crWithContext);
        }
        em.referenceMentions = crs;
      } catch (final RegexWithTimeout.RegexTimeout|Parser.ParsingTimeout e) {
        logger.warn("Timeout while extracting references. References may be incomplete or missing.");
        if (em.references == null)
          em.references = Collections.emptyList();
        if (em.referenceMentions == null)
          em.referenceMentions = Collections.emptyList();
      }
      logger.debug(em.references.size() + " refs for " + em.title);

      try {
        em.abstractText = PDFDocToPartitionedText.getAbstract(lines, doc).trim();
        if (em.abstractText.isEmpty())
          em.abstractText = null;
      } catch (final RegexWithTimeout.RegexTimeout|Parser.ParsingTimeout e) {
        logger.warn("Timeout while extracting abstract. Abstract will be missing.");
        em.abstractText = null;
      }
    }

    //
    // Run figure extraction to get sections
    //
    try {
      final FigureExtractor fe = new FigureExtractor(false, true, true, true, true);

      final FigureExtractor.Document doc =
          fe.getFiguresWithText(pdDoc, scala.Option.apply(null), scala.Option.apply(null));

      em.sections = ScalaStreamSupport.stream(doc.sections()).map(documentSection ->
          new Section(
              OptionConverters.toJava(documentSection.titleText()).orElse(null),
              documentSection.bodyText()
          )
      ).filter(documentSection ->
          // filter out reference sections
          !(
              documentSection.getHeading() != null &&
              PDFDocToPartitionedText.referenceHeaders.contains(
                documentSection.getHeading().trim().toLowerCase().replaceAll("\\p{Punct}*$", ""))
          )
      ).collect(Collectors.toList());
    } catch (final Exception e) {
      logger.warn(
          "Exception {} while getting sections. Section data will be missing.",
          e.getMessage());
      em.sections = null;
    }

    return em;
  }

  public static class ParseOpts {
    public String modelFile;
    public int iterations;
    public int threads;
    public int headerMax;
    public double trainFraction;
    public String gazetteerFile; //record of references
    public String gazetteerDir; //directory of entity lists (universities, person names, etc.)
    public int backgroundSamples;
    public String backgroundDirectory;
    public int minYear; //only process papers this year or later
    public boolean checkAuthors; //only bootstraps papers if all authors are found
    public int documentCount = -1; // how many documents to train on. set to -1 to train on all.
    public int minExpectedFeatureCount = 1;
  }
}
