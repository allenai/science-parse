package org.allenai.scienceparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.gs.collections.impl.tuple.Tuples;

import lombok.val;
import org.allenai.datastore.Datastore;
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
import org.allenai.scienceparse.ExtractReferences.BibStractor;
import org.allenai.scienceparse.ParserGroundTruth.Paper;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class Parser {

  public static final int MAXHEADERWORDS = 500; //set to something high for author/title parsing
  public static final String DATA_VERSION = "0.1";
  private final static Logger logger = LoggerFactory.getLogger(Parser.class);
  private CRFModel<String, PaperToken, String> model;
  private ExtractReferences referenceExtractor;

  private static final Datastore datastore = Datastore.apply();
  public static Path getDefaultProductionModel() {
    return datastore.filePath("org.allenai.scienceparse", "productionModel.dat", 1);
  }
  public static Path getDefaultGazetteer() {
    return datastore.filePath("org.allenai.scienceparse", "gazetteer.json", 1);
  }

  public Parser() throws Exception {
    this(getDefaultProductionModel(), getDefaultGazetteer());
  }

  public Parser(final String modelFile, final String gazetteerFile) throws Exception {
    this(new File(modelFile), new File(gazetteerFile));
  }

  public Parser(final Path modelFile, final Path gazetteerFile) throws Exception {
    this(modelFile.toFile(), gazetteerFile.toFile());
  }

  public Parser(final File modelFile, final File gazetteerFile) throws Exception {
    try(
      final DataInputStream modelIs = new DataInputStream(new FileInputStream(modelFile));
      final InputStream gazetteerIs = new FileInputStream(gazetteerFile);
    ) {
      model = loadModel(modelIs);
      referenceExtractor = new ExtractReferences(gazetteerIs);
    }
  }

  public Parser(final InputStream modelStream, final InputStream gazetteerStream) throws Exception {
    final DataInputStream dis = new DataInputStream(modelStream);
    model = loadModel(dis);
    referenceExtractor = new ExtractReferences(gazetteerStream);
  }

  public static Pair<List<BibRecord>, List<CitationRecord>> getReferences(
    final List<String> raw,
    final List<String> rawReferences,
    final ExtractReferences er
  ) throws IOException {
    Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(rawReferences);
    List<BibRecord> br = fnd.getOne();
    BibStractor bs = fnd.getTwo();
    List<CitationRecord> crs = ExtractReferences.findCitations(raw, br, bs);
    return Tuples.pair(br, crs);
  }

  //slow
  public static String paperToString(File f) {
    try {
      FileInputStream fis = new FileInputStream(f);
      PDFDoc doc = (new PDFExtractor()).extractFromInputStream(fis);
      fis.close();
      val seq = PDFToCRFInput.getSequence(doc, false);
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
          File pdf,
          Paper p,
          PDFExtractor ext,
          boolean heuristicHeader,
          int headerMax) throws IOException {
    return getPaperLabels(pdf, p, ext, heuristicHeader, headerMax, false);
  }

  public static List<Pair<PaperToken, String>> getPaperLabels(
          File pdf,
          Paper p,
          PDFExtractor ext,
          boolean heuristicHeader,
          int headerMax,
          boolean checkAuthors
  ) throws IOException {
    final String paperId = p == null ? null : p.getId();
    logger.debug("{}: starting", paperId);

    PDFDoc doc = null;
    try(final FileInputStream fis = new FileInputStream(pdf)) {
    try {
      doc = ext.extractFromInputStream(fis);
      } catch(final Exception e) {
        logger.warn("{} failed: {}", paperId, e.toString());
        return null;
      }
    }
    if (doc == null) {
      return null;
    }

    List<PaperToken> seq = PDFToCRFInput.getSequence(doc, heuristicHeader);
    if (seq.size() == 0)
      return null;
    seq = seq.subList(0, Math.min(seq.size(), headerMax));
    ExtractedMetadata em = null;
    if (p == null) { //bootstrap:
      em = new ExtractedMetadata(
              doc.getMeta().getTitle(),
              doc.getMeta().getAuthors(),
        doc.getMeta().getCreateDate());
      if (em.title == null) {
        logger.info("{}: skipping", paperId);
        return null;
      }
    } else {
      em = new ExtractedMetadata(p);
    }
    List<Pair<PaperToken, String>> labeledPaper = PDFToCRFInput.labelMetadata(paperId, seq, em);
    if (labeledPaper != null && checkAuthors) {
      ExtractedMetadata checkEM =
              new ExtractedMetadata(
                      labeledPaper.stream().map(Pair::getOne).collect(Collectors.toList()),
                      labeledPaper.stream().map(Pair::getTwo).collect(Collectors.toList()));
      if (checkEM.authors.size() != em.authors.size()) {
        logger.info("{}: author mismatch, discarding. Expected {}, got {}.", paperId, em.authors, checkEM.authors);
        labeledPaper = null;
      }
    }

    return labeledPaper;
  }

  public static List<List<Pair<PaperToken, String>>> labelFromGroundTruth(
    ParserGroundTruth pgt,
    String paperDir,
    int headerMax,
    boolean heuristicHeader,
    int maxFiles,
    int minYear,
    boolean checkAuthors,
    UnifiedSet<String> excludeIDs
  ) throws IOException {
    List<Future<List<Pair<PaperToken, String>>>> labeledDataFutures = new ArrayList<>();
    File dir = new File(paperDir);
    PDFExtractor ext = new PDFExtractor();

    final ExecutorService executor =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    try {
      for (Paper p : pgt.papers) {
        if (minYear > 0 && p.year < minYear)
          continue;
        if (excludeIDs.contains(p.id))
          continue;
        File f = new File(dir, p.id + ".pdf");

        final Future<List<Pair<PaperToken, String>>> labeledDataFuture = executor.submit(() -> {
          return getPaperLabels(f, p, ext, heuristicHeader, headerMax, checkAuthors);
        });

        labeledDataFutures.add(labeledDataFuture);
      }

      List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>(labeledDataFutures.size());
      for (final Future<List<Pair<PaperToken, String>>> labeledDataFuture : labeledDataFutures) {
        try {
          val res = labeledDataFuture.get();
          if (res != null)
            labeledData.add(res);
        } catch (final InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }

        if (labeledData.size() >= maxFiles)
          break;
      }

      return labeledData;
    } finally {
      executor.shutdown();
      try {
        executor.awaitTermination(10, TimeUnit.MINUTES);
      } catch(final InterruptedException e) {
        logger.warn("Interrupted while waiting for thread pool termination. We may be leaking threads.");
      }
    }
  }

  public static List<List<Pair<PaperToken, String>>>
  bootstrapLabels(List<File> files, int headerMax, boolean heuristicHeader) throws IOException {
    List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
    PDFExtractor ext = new PDFExtractor();

    for (File f : files) {
      val labeledPaper = getPaperLabels(f, null, ext, heuristicHeader, headerMax);
      if (labeledPaper != null)
        labeledData.add(labeledPaper);
    }
    return labeledData;
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

  //borrowing heavily from conll.Trainer
  public static void trainParser(
          List<File> files,
          ParserGroundTruth pgt,
          String paperDir,
          ParseOpts opts,
          String excludeIDsFile
  ) throws IOException {
    UnifiedSet<String> excludeIDs = new UnifiedSet<String>();
    if (excludeIDsFile != null)
      excludeIDs = readSet(excludeIDsFile);
    List<List<Pair<PaperToken, String>>> labeledData;
    PDFPredicateExtractor predExtractor;
    if (files != null) {
      labeledData = bootstrapLabels(files, opts.headerMax, true); //don't exclude for pdf meta bootstrap
    } else {
      labeledData = labelFromGroundTruth(
              pgt,
              paperDir,
              opts.headerMax,
              true,
              pgt.papers.size(),
              opts.minYear,
              opts.checkAuthors,
              excludeIDs);
    }
    ParserLMFeatures plf = null;
    if (opts.gazetteerFile != null) {
      ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
      UnifiedSet<String> trainIds = new UnifiedSet<String>();
      pgt.papers.forEach((Paper p) -> trainIds.add(p.id));
      trainIds.addAll(excludeIDs);
      plf = new ParserLMFeatures(
              gaz.papers,
              trainIds,
              new File(opts.backgroundDirectory),
              opts.backgroundSamples);
      predExtractor = new PDFPredicateExtractor(plf);
    } else {
      predExtractor = new PDFPredicateExtractor();
    }

    // Split train/test data
    logger.info("CRF training with {} threads and {} labeled examples", opts.threads, labeledData.size());
    Pair<List<List<Pair<PaperToken, String>>>, List<List<Pair<PaperToken, String>>>> trainTestPair =
      splitData(labeledData, 1.0 - opts.trainFraction);
    val trainLabeledData = trainTestPair.getOne();
    val testLabeledData = trainTestPair.getTwo();

    // Set up Train options
    CRFTrainer.Opts trainOpts = new CRFTrainer.Opts();
    trainOpts.optimizerOpts.maxIters = opts.iterations;
    trainOpts.numThreads = opts.threads;

    // Trainer
    CRFTrainer<String, PaperToken, String> trainer =
      new CRFTrainer<>(trainLabeledData, predExtractor, trainOpts);

    // Setup iteration callback, weird trick here where you require
    // the trainer to make a model for each iteration but then need
    // to modify the iteration-callback to use it
    Parallel.MROpts evalMrOpts = Parallel.MROpts.withIdAndThreads("mr-crf-train-eval", opts.threads);
    CRFModel<String, PaperToken, String> cacheModel = null;
    trainOpts.optimizerOpts.iterCallback = (weights) -> {
      CRFModel<String, PaperToken, String> crfModel = trainer.modelForWeights(weights);
      long start = System.currentTimeMillis();
      List<List<Pair<String, PaperToken>>> trainEvalData = trainLabeledData.stream()
        .map(x -> x.stream().map(Pair::swap).collect(toList()))
        .collect(toList());
      Evaluation<String> eval = Evaluation.compute(crfModel, trainEvalData, evalMrOpts);
      long stop = System.currentTimeMillis();
      logger.info("Train Accuracy: {} (took {} ms)", eval.tokenAccuracy.accuracy(), stop - start);
      if (!testLabeledData.isEmpty()) {
        start = System.currentTimeMillis();
        List<List<Pair<String, PaperToken>>> testEvalData = testLabeledData.stream()
          .map(x -> x.stream().map(Pair::swap).collect(toList()))
          .collect(toList());
        eval = Evaluation.compute(crfModel, testEvalData, evalMrOpts);
        stop = System.currentTimeMillis();
        logger.info("Test Accuracy: {} (took {} ms)", eval.tokenAccuracy.accuracy(), stop - start);
      }
    };

    CRFModel<String, PaperToken, String> crfModel = null;
    crfModel = trainer.train(trainLabeledData);

    Vector weights = crfModel.weights();
    Parallel.shutdownExecutor(evalMrOpts.executorService, Long.MAX_VALUE);

    val dos = new DataOutputStream(new FileOutputStream(opts.modelFile));
    logger.info("Writing model to {}", opts.modelFile);
    saveModel(dos, crfModel.featureEncoder, weights, plf);
    dos.close();
  }

  public static void saveModel(DataOutputStream dos,
                               CRFFeatureEncoder<String, PaperToken, String> fe,
                               Vector weights, ParserLMFeatures plf) throws IOException {
    dos.writeUTF(DATA_VERSION);
    fe.stateSpace.save(dos);
    fe.nodeFeatures.save(dos);
    fe.edgeFeatures.save(dos);
    IOUtils.saveDoubles(dos, weights.toDoubles());
    ObjectOutputStream oos = new ObjectOutputStream(dos);
    oos.writeObject(plf);
  }

  public static CRFModel<String, PaperToken, String> loadModel(
    DataInputStream dis) throws Exception {
    IOUtils.ensureVersionMatch(dis, DATA_VERSION);
    val stateSpace = StateSpace.load(dis);
    Indexer<String> nodeFeatures = Indexer.load(dis);
    Indexer<String> edgeFeatures = Indexer.load(dis);
    Vector weights = DenseVector.of(IOUtils.loadDoubles(dis));
    ObjectInputStream ois = new ObjectInputStream(dis);
    ParserLMFeatures plf = (ParserLMFeatures) ois.readObject();
    val predExtractor = new PDFPredicateExtractor(plf);
    val featureEncoder = new CRFFeatureEncoder<String, PaperToken, String>
      (predExtractor, stateSpace, nodeFeatures, edgeFeatures);
    val weightsEncoder = new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());

    return new CRFModel<String, PaperToken, String>(featureEncoder, weightsEncoder, weights);
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
    return t;
  }

  public static String processTitle(String t) {
    // case fold and remove lead/trail space
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
    return ss.stream().map(s -> lastName(s)).collect(Collectors.toList());
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

  public static String trimAuthor(String s) {
    String sFix = s.replaceAll("(\\W|[0-9])+$", "");
    if (sFix.contains(","))
      sFix = sFix.substring(0, sFix.indexOf(","));
    return sFix;
  }

  public static List<String> trimAuthors(List<String> auth) {
    List<String> out = new ArrayList<String>();
    auth.forEach(s -> {
      s = trimAuthor(s);
      if (!out.contains(s)) out.add(s);
    });
    return out;
  }

  public static void main(String[] args) throws Exception {
    if (!((args.length == 3 && args[0].equalsIgnoreCase("bootstrap")) ||
      (args.length == 5 && args[0].equalsIgnoreCase("parse")) ||
      (args.length == 5 && args[0].equalsIgnoreCase("metaEval")) ||
      (args.length == 7 && args[0].equalsIgnoreCase("learn")) ||
      (args.length == 5 && args[0].equalsIgnoreCase("parseAndScore")) ||
      (args.length == 5 && args[0].equalsIgnoreCase("scoreRefExtraction")))) {
      System.err.println("Usage: bootstrap <input dir> <model output file>");
      System.err.println("OR:    learn <ground truth file> <gazetteer file> <input dir> <model output file> <background dir> <exclude ids file>");
      System.err.println("OR:    parse <input dir> <model input file> <output dir> <gazetteer file>");
      System.err.println("OR:    parseAndScore <input dir> <model input file> <output dir> <ground truth file>");
      System.err.println("OR:    scoreRefExtraction <input dir> <model input file> <output file> <ground truth file>");
    } else if (args[0].equalsIgnoreCase("bootstrap")) {
      File inDir = new File(args[1]);
      List<File> inFiles = Arrays.asList(inDir.listFiles());
      ParseOpts opts = new ParseOpts();
      opts.modelFile = args[2];
      //TODO: use config file
      opts.headerMax = 100;
      opts.iterations = inFiles.size() / 10; //HACK because training throws exceptions if you iterate too much
      opts.threads = Runtime.getRuntime().availableProcessors();
      trainParser(inFiles, null, null, opts, null);

    } else if (args[0].equalsIgnoreCase("learn")) { //learn from ground truth
      ParserGroundTruth pgt = new ParserGroundTruth(args[1]); //holds the labeled data to be used for train, test (S2 json bib format)
      ParseOpts opts = new ParseOpts();
      opts.modelFile = args[4]; //holds the output file for the model.
      //TODO: use config file
      opts.headerMax = MAXHEADERWORDS; //a limit for the length of the header to process, in words.
      opts.iterations = Math.min(1000, pgt.papers.size()); //HACK because training throws exceptions if you iterate too much
      opts.threads = Runtime.getRuntime().availableProcessors();
      opts.backgroundSamples = 400; //use up to this many papers from background dir to estimate background language model
      opts.backgroundDirectory = args[5]; //where to find the background papers
      opts.gazetteerFile = args[2]; //a gazetteer of true bib records  (S2 json bib format)
      opts.trainFraction = 0.9; //what fraction of data to use for training, the rest is test
      opts.checkAuthors = true; //exclude from training papers where we don't find authors
      opts.minYear = 2008; //discard papers from before this date
      trainParser(null, pgt, args[3], opts, args[6]); //args[3] holds the papers in which we can find ground truth
                //args[6] is a list of paper ids (one id per line) that we must exclude from training data and gazetteers 
                //(because they're used for final tests)
    } else if (args[0].equalsIgnoreCase("parse")) {
      final Path modelFile;
      if(args[2].equals("-"))
        modelFile = Parser.getDefaultProductionModel();
      else
        modelFile = Paths.get(args[2]);

      final Path gazetteerFile;
      if(args[4].equals("-"))
        gazetteerFile = Parser.getDefaultGazetteer();
      else
        gazetteerFile = Paths.get(args[4]);

      Parser p = new Parser(modelFile, gazetteerFile);
      File input = new File(args[1]);
      File outDir = new File(args[3]);
      final List<File> inFiles;
      if(input.isFile())
        inFiles = Collections.singletonList(input);
      else
        inFiles = Arrays.asList(input.listFiles());
      ObjectMapper mapper = new ObjectMapper();

      for (File f : inFiles) {
        if (!f.getName().endsWith(".pdf"))
          continue;
        val fis = new FileInputStream(f);
        ExtractedMetadata em = null;
        try {
          em = p.doParse(fis, MAXHEADERWORDS);
        } catch (final Exception e) {
          logger.info("Parse error: " + f, e);
        }
        fis.close();
        //Object to JSON in file
        mapper.writeValue(new File(outDir, f.getName() + ".dat"), em);
      }

    } else if (args[0].equalsIgnoreCase("parseAndScore")) {
      Parser p = new Parser(args[2], args[4]);
      File inDir = new File(args[1]);
      List<File> inFiles = Arrays.asList(inDir.listFiles());
      ParserGroundTruth pgt = new ParserGroundTruth(args[4]);
      int totalFiles = 0;
      int totalProcessed = 0;
      int crfTruePos = 0;
      int crfFalsePos = 0;
      int metaTruePos = 0;
      int metaFalsePos = 0;
      double crfPrecision = 0;
      double crfRecall = 0;
      double crfTotal = 0;
      double metaPrecision = 0;
      double metaRecall = 0;
      double metaTotal = 0;


      for (File f : inFiles) {
        val fis = new FileInputStream(f);
        String key = f.getName().substring(0, f.getName().length() - 4);
        Paper pap = pgt.forKey(key);
        if (pap.year < 2010)
          continue;
        totalFiles++;
        ExtractedMetadata em = null;
        try {
          em = p.doParse(fis, MAXHEADERWORDS);
          totalProcessed++;
        } catch (final Exception e) {
          logger.info("Parse error: " + f, e);
        }

        if (em != null && em.title != null) {
          String expected = pap.title;
          String guessed = em.title;
          String procExpected = processTitle(expected);
          String procGuessed = processTitle(processExtractedTitle(guessed));
          String[] authExpected = pap.authors;
          List<String> authGuessed = trimAuthors(em.authors);

          int tempTP = scoreAuthors(authExpected, authGuessed);
          double prec = ((double) tempTP) / ((double) authGuessed.size() + 0.000000001);
          double rec = ((double) tempTP) / ((double) authExpected.length);
          if (em.source == ExtractedMetadata.Source.CRF) {
            crfPrecision += prec;
            crfRecall += rec;
            crfTotal += 1.0;
          } else {
            metaPrecision += prec;
            metaRecall += rec;
            metaTotal += 1.0;
          }

          if (tempTP != authGuessed.size() || tempTP != authExpected.length) {
            logger.info("auth error: " + tempTP + " right, exp " + Arrays.toString(authExpected) + " got " + authGuessed);
            logger.info(f.getName());
          }
          if (procExpected.equals(procGuessed))
            if (em.source == ExtractedMetadata.Source.CRF)
              crfTruePos++;
            else
              metaTruePos++;
          else {
            if (em.source == ExtractedMetadata.Source.CRF)
              crfFalsePos++;
            else
              metaFalsePos++;
            logger.info(em.source + " error, expected:\r\n" + procExpected + "\r\ngot\r\n" + procGuessed);
          }
        }

        fis.close();
      }
      logger.info("total files: " + totalFiles);
      logger.info("total processed: " + totalProcessed);
      logger.info("crf correct: " + crfTruePos);
      logger.info("crf false positive " + crfFalsePos);
      logger.info("meta correct: " + metaTruePos);
      logger.info("meta false positive " + metaFalsePos);
      logger.info("crf micro-average prec: " + crfPrecision);
      logger.info("crf micro-average rec: " + crfRecall);
      logger.info("crf total: " + crfTotal);
      logger.info("meta micro-average prec: " + metaPrecision);
      logger.info("meta micro-average rec: " + metaRecall);
      logger.info("meta total: " + metaTotal);
      logger.info("overall author precision: " + (crfPrecision + metaPrecision) / (crfTotal + metaTotal));
      logger.info("overall author recall: " + (crfRecall + metaRecall) / ((double) totalFiles));
      //TODO: write output

    } else if (args[0].equalsIgnoreCase("scoreRefExtraction")) {
      Parser p = new Parser(args[2], args[4]);
      File inDir = new File(args[1]);
      File outDir = new File(args[3]);
      List<File> inFiles = Arrays.asList(inDir.listFiles());
      HashSet<String> foundRefs = new HashSet<String>();
      HashSet<String> unfoundRefs = new HashSet<String>();

      ObjectMapper mapper = new ObjectMapper();
      int totalRefs = 0;
      int totalCites = 0;
      int blankAbstracts = 0;
      for (File f : inFiles) {
        if (!f.getName().endsWith(".pdf"))
          continue;
        val fis = new FileInputStream(f);
        ExtractedMetadata em = null;
        try {
          logger.info(f.getName());
          em = p.doParse(fis, MAXHEADERWORDS);
          if(em.abstractText == null || em.abstractText.length() == 0) {
            logger.info("abstract blank!");
            blankAbstracts++;
          }
          else {
            logger.info("abstract: " + em.abstractText);
          }
          final List<BibRecord> br = em.references;
          final List<CitationRecord> cr = em.referenceMentions;
          if (br.size() > 3 && cr.size() > 3) {  //HACK: assume > 3 refs means valid ref list
            foundRefs.add(f.getAbsolutePath());
          } else {
            unfoundRefs.add(f.getAbsolutePath());
          }
          totalRefs += br.size();
          totalCites += cr.size();
          mapper.writeValue(
            new File(outDir, f.getName() + ".dat"),
            Tuples.pair(em.references, em.referenceMentions));
        } catch (Exception e) {
          logger.info("Parse error: " + f);
          e.printStackTrace();
        }
        fis.close();

      }

      //Object to JSON in file
      mapper.writeValue(new File(outDir, "unfoundReferences.dat"), unfoundRefs);
      mapper.writeValue(new File(outDir, "foundReferences.dat"), foundRefs);
      logger.info("found 3+ refs and 3+ citations for " + foundRefs.size() + " papers.");
      logger.info("failed to find that many for " + unfoundRefs.size() + " papers.");
      logger.info("total references: " + totalRefs + "\ntotal citations: " + totalCites);
      logger.info("blank abstracts: " + blankAbstracts);
    }
  }

  static void logExceptionShort(final Throwable t, final String errorType, final String filename) {
    if (t.getStackTrace().length == 0) {
      logger.warn("Exception without stack trace", t);
    } else {
      final StackTraceElement ste = t.getStackTrace()[0];
      logger.warn(
        String.format(
          "%s while processing file %s at %s:%d: %s (%s)",
          errorType,
          filename,
          ste.getFileName(),
          ste.getLineNumber(),
          t.getClass().getName(),
          t.getMessage()));
    }
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

    PDFExtractor ext = new PDFExtractor();
    PDFDoc doc = ext.extractFromInputStream(is);
    List<PaperToken> seq = PDFToCRFInput.getSequence(doc, true);
    seq = seq.subList(0, Math.min(seq.size(), headerMax));
    seq = PDFToCRFInput.padSequence(seq);

    if (doc.meta == null || doc.meta.title == null) { //use the model
      List<String> outSeq = model.bestGuess(seq);
      //the output tag sequence will not include the start/stop states!
      outSeq = PDFToCRFInput.padTagSequence(outSeq);
      em = new ExtractedMetadata(seq, outSeq);
      em.source = ExtractedMetadata.Source.CRF;
    } else {
      em = new ExtractedMetadata(doc.meta.title, doc.meta.authors, doc.meta.createDate);
      em.source = ExtractedMetadata.Source.META;
    }
    if (doc.meta.createDate != null)
      em.setYearFromDate(doc.meta.createDate);
    clean(em);
    em.raw = PDFDocToPartitionedText.getRaw(doc);
    em.creator = doc.meta.creator;
      
    // extract references
    final List<String> rawReferences = PDFDocToPartitionedText.getRawReferences(doc);
    final Pair<List<BibRecord>, List<CitationRecord>> pair =
      getReferences(em.raw, rawReferences, referenceExtractor);
    em.references = pair.getOne();
    List<CitationRecord> crs = new ArrayList<>();
    for (CitationRecord cr: pair.getTwo()) {
      crs.add(extractContext(cr.referenceID, cr.context, cr.startOffset, cr.endOffset));
    }
    em.referenceMentions = crs;

    em.abstractText = PDFDocToPartitionedText.getAbstract(em.raw, doc);

    return em;
  }

  public static class ParseOpts {
    public String modelFile;
    public int iterations;
    public int threads;
    public int headerMax;
    public double trainFraction;
    public String gazetteerFile;
    public int backgroundSamples;
    public String backgroundDirectory;
    public int minYear; //only process papers this year or later
    public boolean checkAuthors; //only bootstraps papers if all authors are found
  }
}
