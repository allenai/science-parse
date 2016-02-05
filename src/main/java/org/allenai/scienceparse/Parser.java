package org.allenai.scienceparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.gs.collections.impl.tuple.Tuples;

import lombok.val;
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
import org.allenai.pdfbox.pdmodel.PDDocument;
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
import java.io.PrintWriter;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class Parser {

  public static final int MAXHEADERWORDS = 1000; //set to something high for author/title parsing
  public static final String DATA_VERSION = "0.1";
  private final static Logger logger = LoggerFactory.getLogger(Parser.class);
  private CRFModel<String, PaperToken, String> model;
  private ExtractReferences referenceExtractor;

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

  public static List<Pair<PaperToken, String>> getPaperLabels(File pdf, Paper p, PDFExtractor ext, boolean heuristicHeader,
                                                              int headerMax) throws IOException {
    return getPaperLabels(pdf, p, ext, heuristicHeader, headerMax, false);
  }

  public static List<Pair<PaperToken, String>> getPaperLabels(File pdf, Paper p, PDFExtractor ext, boolean heuristicHeader,
                                                              int headerMax, boolean checkAuthors) throws IOException {

    PDFDoc doc = null;
    try {
      FileInputStream fis = new FileInputStream(pdf);
      doc = ext.extractFromInputStream(fis);
      fis.close();
    } catch (Exception e) {
    }
    ;
    if (doc == null) {
      return null;
    }
    List<PaperToken> seq = PDFToCRFInput.getSequence(doc, heuristicHeader);
    if (seq.size() == 0)
      return null;
    seq = seq.subList(0, Math.min(seq.size(), headerMax));
    ExtractedMetadata em = null;
    if (p == null) { //bootstrap:
      em = new ExtractedMetadata(doc.getMeta().getTitle(), doc.getMeta().getAuthors(),
        doc.getMeta().getCreateDate());
      if (em.title == null) {
        logger.info("skipping " + pdf);
        return null;
      }
    } else {
      em = new ExtractedMetadata(p);
    }
    logger.info("finding " + em.toString());
    List<Pair<PaperToken, String>> labeledPaper =
      PDFToCRFInput.labelMetadata(seq, em);
    if (labeledPaper != null && checkAuthors) {
      ExtractedMetadata checkEM = new ExtractedMetadata(labeledPaper.stream().map(pr -> pr.getOne()).collect(Collectors.toList()),
        labeledPaper.stream().map(pr -> pr.getTwo()).collect(Collectors.toList()));
      if (checkEM.authors.size() != em.authors.size()) {
        logger.info("author mismatch, discarding.  exp " + em.authors + " got " + checkEM.authors);
        labeledPaper = null;
      } else {
        logger.info("author match");
      }
    }

    return labeledPaper;
  }

  public static List<List<Pair<PaperToken, String>>> labelFromGroundTruth(
    ParserGroundTruth pgt, String paperDir, int headerMax, boolean heuristicHeader, int maxFiles,
    int minYear, boolean checkAuthors, UnifiedSet<String> excludeIDs) throws IOException {
    List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
    File dir = new File(paperDir);
    PDFExtractor ext = new PDFExtractor();
    for (Paper p : pgt.papers) {
      if (minYear > 0 && p.year < minYear)
        continue;
      if (excludeIDs.contains(p.id))
        continue;
      File f = new File(dir, p.id.substring(4) + ".pdf"); //first four are directory, rest is file name
      val res = getPaperLabels(f, p, ext, heuristicHeader, headerMax, checkAuthors);

      if (res != null)
        labeledData.add(res);

      if (labeledData.size() >= maxFiles)
        break;
    }
    return labeledData;
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
  public static void trainParser(List<File> files, ParserGroundTruth pgt, String paperDir, ParseOpts opts,
                                 String excludeIDsFile)
    throws IOException {
    UnifiedSet<String> excludeIDs = new UnifiedSet<String>();
    if (excludeIDsFile != null)
      excludeIDs = readSet(excludeIDsFile);
    List<List<Pair<PaperToken, String>>> labeledData;
    PDFPredicateExtractor predExtractor;
    if (files != null) {
      labeledData = bootstrapLabels(files, opts.headerMax, true); //don't exclude for pdf meta bootstrap
    } else {
      labeledData = labelFromGroundTruth(pgt, paperDir, opts.headerMax, true, pgt.papers.size(), opts.minYear, opts.checkAuthors,
        excludeIDs);
    }
    ParserLMFeatures plf = null;
    if (opts.gazetteerFile != null) {
      ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
      int stIdx = 0;
      int endIdx = gaz.papers.size();
      UnifiedSet<String> trainIds = new UnifiedSet<String>();
      pgt.papers.forEach((Paper p) -> trainIds.add(p.id));
      trainIds.addAll(excludeIDs);
      plf = new ParserLMFeatures(gaz.papers, trainIds, stIdx, endIdx, new File(opts.backgroundDirectory), opts.backgroundSamples);
      predExtractor = new PDFPredicateExtractor(plf);
    } else {
      predExtractor = new PDFPredicateExtractor();
    }

    // Split train/test data
    logger.info("CRF training with {} threads and {} labeled examples", opts.threads, labeledData.size());
    val trainTestPair =
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
      opts.threads = 4;
      trainParser(inFiles, null, null, opts, null);
    } else if (args[0].equalsIgnoreCase("learn")) { //learn from ground truth
      ParserGroundTruth pgt = new ParserGroundTruth(args[1]);
      ParseOpts opts = new ParseOpts();
      opts.modelFile = args[4];
      //TODO: use config file
      opts.headerMax = MAXHEADERWORDS;
      opts.iterations = Math.min(1000, pgt.papers.size()); //HACK because training throws exceptions if you iterate too much
      opts.threads = 4;
      opts.backgroundSamples = 400;
      opts.backgroundDirectory = args[5];
      opts.gazetteerFile = args[2];
      opts.trainFraction = 0.9;
      opts.checkAuthors = true;
      opts.minYear = 2008;
      trainParser(null, pgt, args[3], opts, args[6]);
    } else if (args[0].equalsIgnoreCase("parse")) {
      Parser p = new Parser(args[2], args[4]);
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
    } else if (args[0].equalsIgnoreCase("metaEval")) {
      Parser p = new Parser(args[2], args[4]);
      File inDir = new File(args[1]);
      File outDir = new File(args[3]);
      List<File> inFiles = Arrays.asList(inDir.listFiles());
      ObjectMapper mapper = new ObjectMapper();

      final ExecutorService e =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
      try (
        final PrintWriter authorFullNameExact = new PrintWriter(new File(outDir, "authorFullNameExact.tsv"), "UTF-8");
        final PrintWriter titleExact = new PrintWriter(new File(outDir, "titleExact.tsv"), "UTF-8")
      ) {
        final long start = System.currentTimeMillis();
        val paperCount = new AtomicInteger();
        val papersSucceeded = new AtomicInteger();

        for (File f : inFiles) {
          if (!f.getName().endsWith(".pdf"))
            continue;

          e.execute(new Runnable() {
            @Override
            public void run() {
              try {
                paperCount.incrementAndGet();
                val fis = new FileInputStream(f);
                ExtractedMetadata em = null;
                try {
                  em = p.doParse(fis, MAXHEADERWORDS);
                } catch (final Exception e) {
                  logExceptionShort(e, "Parse error", f.getName());
                  return;
                }
                fis.close();

                final String paperId =
                  f.getName().substring(0, f.getName().length() - 4);

                synchronized (authorFullNameExact) {
                  authorFullNameExact.write(paperId);
                  for (String author : em.authors) {
                    authorFullNameExact.write('\t');
                    authorFullNameExact.write(author);
                  }
                  authorFullNameExact.write('\n');
                }

                synchronized (titleExact) {
                  titleExact.write(paperId);
                  titleExact.write('\t');
                  if (em.getTitle() != null)
                    titleExact.write(em.getTitle());
                  titleExact.write('\n');
                }

                papersSucceeded.incrementAndGet();
              } catch (final IOException e) {
                logExceptionShort(e, "IO error", f.getName());
              }
            }
          });
        }

        e.shutdown();
        e.awaitTermination(60, TimeUnit.HOURS);

        final long end = System.currentTimeMillis();
        System.out.println(String.format("Processed %d papers in %d milliseconds.", paperCount.get(), end - start));
        System.out.println(String.format("%d ms per paper", (end - start) / paperCount.get()));
        System.out.println(String.format("%d failures (%f%%)", (paperCount.get() - papersSucceeded.get()), 100.0f * (paperCount.get() - papersSucceeded.get()) / paperCount.get()));
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
          if (em.source.equals("CRF")) {
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
            if (em.source.equals("CRF"))
              crfTruePos++;
            else
              metaTruePos++;
          else {
            if (em.source.equals("CRF"))
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
      int blankAbstracts =0;
      for (File f : inFiles) {
        if (!f.getName().endsWith(".pdf"))
          continue;
        val fis = new FileInputStream(f);
        ExtractedMetadata em = null;
        try {
          logger.info(f.getName());
          em = p.doParse(fis, MAXHEADERWORDS);
          if(em.abstractText==null || em.abstractText.length()==0) {
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

  public ExtractedMetadata doParse(final InputStream is, int headerMax) throws IOException {
    final PDDocument pdDoc = PDDocument.load(is);

    final String abstractText = "";
//    { // Get abstract from figure extraction.
//      final FigureExtractor.Document doc = FigureExtractor.Document$.MODULE$.fromPDDocument(pdDoc);
//      if (doc.abstractText().isDefined()) {
//        Pattern p = Pattern.compile("^(abstract)?[^a-z]*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
//        // remove ABSTRACT and any non-letter characters (such as periods or spaces) in the beginning
//        abstractText = p.matcher(doc.abstractText().get().text().trim()).replaceFirst("");
//      } else {
//        abstractText = "";
//      }
//    }

    final ExtractedMetadata em;
    {
      // extract everything but references
      PDFExtractor ext = new PDFExtractor();
      PDFDoc doc = ext.extractResultFromPDDocument(pdDoc).document;
      List<PaperToken> seq = PDFToCRFInput.getSequence(doc, true);
      seq = seq.subList(0, Math.min(seq.size(), headerMax));
      seq = PDFToCRFInput.padSequence(seq);

      if (doc.meta == null || doc.meta.title == null) { //use the model
        List<String> outSeq = model.bestGuess(seq);
        //the output tag sequence will not include the start/stop states!
        outSeq = PDFToCRFInput.padTagSequence(outSeq);
        em = new ExtractedMetadata(seq, outSeq);
        em.source = "CRF";
      } else {
        em = new ExtractedMetadata(doc.meta.title, doc.meta.authors, doc.meta.createDate);
        em.source = "META";
      }
      if (doc.meta.createDate != null)
        em.setYearFromDate(doc.meta.createDate);
      clean(em);
      em.raw = PDFDocToPartitionedText.getRaw(doc);

      // extract references
      final List<String> rawReferences = PDFDocToPartitionedText.getRawReferences(doc);
      final Pair<List<BibRecord>, List<CitationRecord>> pair =
        getReferences(em.raw, rawReferences, referenceExtractor);
      em.references = pair.getOne();
      em.referenceMentions = pair.getTwo();

      em.abstractText = PDFDocToPartitionedText.getAbstract(em.raw);
    }

    if (abstractText.length() > em.abstractText.length()) {
      em.abstractText = abstractText;
    }

    
    
    // remove keywords from abstract
    Pattern p2 = Pattern.compile("Key ?words(:| |\\.).*$", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    em.abstractText = p2.matcher(em.abstractText).replaceFirst("");
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
