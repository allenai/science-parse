package org.allenai.scienceparse;

import lombok.val;

import static java.util.stream.Collectors.toList;

import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

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
//import org.allenai.ml.sequences.crf.conll.ConllCRFEndToEndTest;
//import org.allenai.ml.sequences.crf.conll.ConllFormat;
//import org.allenai.ml.sequences.crf.conll.Evaluator;
//import org.allenai.ml.sequences.crf.conll.Trainer;
import org.allenai.ml.util.Parallel;
import org.allenai.scienceparse.ParserGroundTruth.Paper;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.gs.collections.impl.tuple.Tuples;

public class Parser {

  private final static Logger logger = LoggerFactory.getLogger(Parser.class);
	
  private CRFModel<String, PaperToken, String> model;
  
  	public Parser(String modelFile) throws Exception {
  		DataInputStream dis = new DataInputStream(new FileInputStream(modelFile));
  		model = loadModel(dis);
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
	  public boolean recentOnly; //only process papers ~2010 or later
	  public boolean checkAuthors; //only bootstraps papers if all authors are found
  }
  
  public ExtractedMetadata doParse(InputStream is) throws IOException {
	  PDFExtractor ext = new PDFExtractor(); 	  
	  PDFDoc doc = ext.extractFromInputStream(is);
      List<PaperToken> seq = PDFToCRFInput.getSequence(doc, true);
      seq = PDFToCRFInput.padSequence(seq);
      ExtractedMetadata em = null;
      
      if(doc.meta == null || doc.meta.title == null) { //use the model
    	  List<String> outSeq = model.bestGuess(seq);
    	  //the output tag sequence will not include the start/stop states!
    	  outSeq = PDFToCRFInput.padTagSequence(outSeq);
    	  em = new ExtractedMetadata(seq, outSeq);
    	  em.source = "CRF";
      }
      else {
          em = new ExtractedMetadata(doc.meta.title, doc.meta.authors, doc.meta.createDate);
          em.source = "META";
      }
      return em;
  }
  
  //slow
	public static String paperToString(File f) {
		try {
			FileInputStream fis = new FileInputStream(f);
			PDFDoc doc = (new PDFExtractor()).extractFromInputStream(fis);
			fis.close();
			val seq = PDFToCRFInput.getSequence(doc, false);
			return PDFToCRFInput.stringAt(seq, Tuples.pair(0, seq.size()));
		}
		catch(Exception e) {
			return null;
		}
	}

  
  //from conll.Trainer:
  private static <T> Pair<List<T>, List<T>> splitData(List<T> original, double splitForSecond) {
      List<T> first = new ArrayList<>();
      List<T> second = new ArrayList<>();
      if (splitForSecond > 0.0) {
          Collections.shuffle(original, new Random(0L));
          int numFirst = (int) ((1.0-splitForSecond) * original.size());
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
	  }
	  catch(Exception e) {};
	  if(doc == null) {
		  return null;
	  }
      List<PaperToken> seq = PDFToCRFInput.getSequence(doc, heuristicHeader);
      if(seq.size()==0)
    	  return null;
      seq = seq.subList(0, Math.min(seq.size()-1, headerMax));
      ExtractedMetadata em = null;
      if(p==null) { //bootstrap:
	      em = new ExtractedMetadata(doc.getMeta().getTitle(), doc.getMeta().getAuthors(),
	    		  doc.getMeta().getCreateDate());
	      if(em.title == null) {
	    	  logger.info("skipping " + pdf);
	    	  return null;
	      }
      }
      else {
    	  em = new ExtractedMetadata(p);
      }
      logger.info("finding " + em.toString());
      List<Pair<PaperToken, String>> labeledPaper = 
    		  PDFToCRFInput.labelMetadata(seq, em);
      if(labeledPaper != null && checkAuthors) {
	      ExtractedMetadata checkEM = new ExtractedMetadata(labeledPaper.stream().map(pr -> pr.getOne()).collect(Collectors.toList()),
	    		  labeledPaper.stream().map(pr -> pr.getTwo()).collect(Collectors.toList()));
	      if(checkEM.authors.size() != em.authors.size()) {
	    	  logger.info("author mismatch, discarding.  exp " + em.authors + " got " + checkEM.authors);
	    	  labeledPaper = null;
	      }
	      else {
	    	  logger.info("author match");
	      }
      }
//      logger.info("first: " + labeledPaper.get(0).getTwo());
//      logger.info("last: " + labeledPaper.get(labeledPaper.size()-1).getTwo());
      
      return labeledPaper;
  }
  
  public static List<List<Pair<PaperToken, String>>> labelFromGroundTruth(
		  ParserGroundTruth pgt, String paperDir, int headerMax, boolean heuristicHeader, int maxFiles,
		  boolean recentOnly, boolean checkAuthors) throws IOException {
	  List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
	  File dir = new File(paperDir);
	  PDFExtractor ext = new PDFExtractor();
	  for(Paper p : pgt.papers) {
		  if(recentOnly && p.year < 2010)
			  continue;
		  File f = new File(dir, p.id.substring(4) + ".pdf"); //first four are directory, rest is file name
		  val res = getPaperLabels(f, p, ext, heuristicHeader, headerMax, checkAuthors);
		  
		  if(res != null)
			  labeledData.add(res);
		  
		  if(labeledData.size() >= maxFiles)
			  break;
	  }
	  return labeledData;
  }
  
  public static List<List<Pair<PaperToken, String>>> 
  				bootstrapLabels(List<File> files, int headerMax, boolean heuristicHeader) throws IOException {
	  List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
      PDFExtractor ext = new PDFExtractor(); 	  
     	  
      for(File f : files) {
    	 val labeledPaper = getPaperLabels(f, null, ext, heuristicHeader, headerMax);
    	 if(labeledPaper != null)
           labeledData.add(labeledPaper);
      }
      return labeledData;
  }
  
  //borrowing heavily from conll.Trainer
  public static void trainParser(List<File> files, ParserGroundTruth pgt, String paperDir, ParseOpts opts) 
		  throws IOException {
	  
      List<List<Pair<PaperToken, String>>> labeledData;
      PDFPredicateExtractor predExtractor;
      if(files!= null) {
    	  labeledData = bootstrapLabels(files, opts.headerMax, true);
      }
      else {
    	  labeledData = labelFromGroundTruth(pgt, paperDir, opts.headerMax, true, pgt.papers.size(), opts.recentOnly, opts.checkAuthors);
      }
      ParserLMFeatures plf = null;
      if(opts.gazetteerFile != null) {
    	  ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
    	  int stIdx = 0;
    	  int endIdx = gaz.papers.size();
    	  UnifiedSet<String> trainIds = new UnifiedSet<String>();
    	  pgt.papers.forEach((Paper p) -> trainIds.add(p.id));
          plf = new ParserLMFeatures(gaz.papers, trainIds, stIdx, endIdx, new File(opts.backgroundDirectory), opts.backgroundSamples);
    	  predExtractor = new PDFPredicateExtractor(plf);
      }
      else {
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
          logger.info("Train Accuracy: {} (took {} ms)", eval.tokenAccuracy.accuracy(), stop-start);
          if (!testLabeledData.isEmpty()) {
              start = System.currentTimeMillis();
              List<List<Pair<String, PaperToken>>> testEvalData = testLabeledData.stream()
                  .map(x -> x.stream().map(Pair::swap).collect(toList()))
                  .collect(toList());
              eval = Evaluation.compute(crfModel, testEvalData, evalMrOpts);
              stop = System.currentTimeMillis();
              logger.info("Test Accuracy: {} (took {} ms)", eval.tokenAccuracy.accuracy(), stop-start);
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
  
  public static final String DATA_VERSION = "0.1";
  
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
	  ParserLMFeatures plf  = (ParserLMFeatures)ois.readObject();
	  val predExtractor = new PDFPredicateExtractor(plf);
	  val featureEncoder = new CRFFeatureEncoder<String, PaperToken, String>
	  (predExtractor, stateSpace, nodeFeatures, edgeFeatures);
	  val weightsEncoder = new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());
	  
	  return new CRFModel<String, PaperToken, String>(featureEncoder, weightsEncoder, weights);
  }
  
  public static String processTitle(String t) {
      // case fold and remove lead/trail space
      t = t.trim().toLowerCase();
      // strip accents and unicode changes
      t = Normalizer.normalize(t, Normalizer.Form.NFKD);
      // kill non-character letters
      // kill xml
      t = t.replaceAll("\\&.*?\\;","");
      // kill non-letter chars
      t = t.replaceAll("\\W","");
      return t.replaceAll("\\s+"," ");
  }

  //changes extraction to remove common failure modes
  public static String processExtractedTitle(String t) {
	  String out = t.replaceAll("(?<=[a-z])\\- ", ""); //continuation dash
//	  out = out.replaceAll(" \\?", ""); //special char
	  if(!out.endsWith("?")&&!out.endsWith("\"")&&!out.endsWith(")"))
		out = out.replaceFirst("\\W$", ""); //end of title punctuation if not ?, ", or )
	  return out.trim();
  }
  
  //lowercases
  public static String lastName(String s) {
	  String [] words = s.split(" ");
	  if(words.length > 0)
		  return processTitle(words[words.length-1]);
	  else
		  return "";
  }
  
  public static List<String> lastNames(List<String> ss) {
	  return ss.stream().map(s -> lastName(s)).collect(Collectors.toList());
  }
  
  public static int scoreAuthors(String [] expected, List<String> guessed) {
	  List<String> guessLastName = lastNames(guessed);
	  List<String> expectedLastName = lastNames(Arrays.asList(expected));
	  //slow:
	  int truePos = 0;
	  for(String s : guessLastName) {
		  if(expectedLastName.contains(s))
			  truePos++;
	  }
	  return truePos;
  }
  
  public static void main(String[] args) throws Exception {
	  if(!((args.length==3 && args[0].equalsIgnoreCase("bootstrap"))||
			  (args.length==4 && args[0].equalsIgnoreCase("parse"))||
			  (args.length==6 && args[0].equalsIgnoreCase("learn"))||
			  (args.length==5 && args[0].equalsIgnoreCase("parseAndScore")))) {
		  System.err.println("Usage: bootstrap <input dir> <model output file>");
		  System.err.println("OR:    learn <ground truth file> <gazetteer file> <input dir> <model output file> <background dir>");
		  System.err.println("OR:    parse <input dir> <model input file> <output dir>");
		  System.err.println("OR:    parseAndScore <input dir> <model input file> <output dir> <ground truth file>");
	  }
	  else if(args[0].equalsIgnoreCase("bootstrap")) {
		  File inDir = new File(args[1]);
		  List<File> inFiles = Arrays.asList(inDir.listFiles()); 
		  ParseOpts opts = new ParseOpts();
		  opts.modelFile = args[2];
		  //TODO: use config file
		  opts.headerMax = 100;
		  opts.iterations = inFiles.size()/10; //HACK because training throws exceptions if you iterate too much
		  opts.threads = 4;
		  trainParser(inFiles, null, null, opts);		  
	  }
	  else if(args[0].equalsIgnoreCase("learn")) { //learn from ground truth
		  ParserGroundTruth pgt = new ParserGroundTruth(args[1]);
		  ParseOpts opts = new ParseOpts();
		  opts.modelFile = args[4];
		  //TODO: use config file
		  opts.headerMax = 100;
		  opts.iterations =  Math.min(500, pgt.papers.size()); //HACK because training throws exceptions if you iterate too much
		  opts.threads = 4;
		  opts.backgroundSamples = 400;
		  opts.backgroundDirectory = args[5];
		  opts.gazetteerFile = args[2];
		  opts.trainFraction = 0.9;
		  trainParser(null, pgt, args[3], opts);
	  }
	  else if(args[0].equalsIgnoreCase("parse")) {
		  Parser p = new Parser(args[2]);
		  File inDir = new File(args[1]);
		  List<File> inFiles = Arrays.asList(inDir.listFiles());
		  for(File f : inFiles) {
			  //logger.info("parsing " + f);
			  val fis = new FileInputStream(f);
			  try {
				  p.doParse(fis);
			  }
			  catch(Exception e) {
				  logger.info("Parse error: " + f);
				  //e.printStackTrace();
			  }
			  fis.close();
		  }
		  //TODO: write output
	  }
	  else if(args[0].equalsIgnoreCase("parseAndScore")) {
		  Parser p = new Parser(args[2]);
		  File inDir = new File(args[1]);
		  List<File> inFiles = Arrays.asList(inDir.listFiles());
		  ParserGroundTruth pgt = new ParserGroundTruth(args[4]);
		  int totalFiles = 0;
		  int totalProcessed = 0;
		  int crfTruePos = 0;
		  int crfFalsePos = 0;
		  int metaTruePos = 0;
		  int metaFalsePos = 0;
		  int crfAuthTruePos = 0;
		  int crfAuthFalsePos = 0;
		  int crfAuthFalseNeg = 0;
		  int metaAuthTruePos = 0;
		  int metaAuthFalsePos = 0;
		  int metaAuthFalseNeg = 0;

		  
		  for(File f : inFiles) {
			  //logger.info("parsing " + f);
			  val fis = new FileInputStream(f);
			  String key = f.getName().substring(0, f.getName().length()-4);
			  Paper pap = pgt.forKey(key);
			  if(pap.year < 2010)
				  continue;
			  totalFiles++;
			  ExtractedMetadata em = null;
			  try {
				  em = p.doParse(fis);
				  totalProcessed++;
			  }
			  catch(Exception e) {
				  logger.info("Parse error: " + f);
				  //e.printStackTrace();
			  }
			  
			  if(em != null && em.title != null) {
				  String expected = pap.title;
				  String guessed = em.title;
				  String procExpected = processTitle(expected);
				  String procGuessed =  processTitle(processExtractedTitle(guessed));
				  String [] authExpected = pap.authors;
				  List<String> authGuessed = em.authors;
				  int tempTP = scoreAuthors(authExpected, authGuessed);
				  if(em.source=="CRF") {
					  crfAuthTruePos += tempTP;
					  crfAuthFalsePos += authGuessed.size() - tempTP;
					  crfAuthFalseNeg += authExpected.length - tempTP;
				  }
				  else {
					  metaAuthTruePos += tempTP;
					  metaAuthFalsePos += authGuessed.size() - tempTP;
					  metaAuthFalseNeg += authExpected.length - tempTP;					  
				  }
					  
				  if(tempTP != authGuessed.size() || tempTP != authExpected.length)
					  logger.info("auth error: " + tempTP + " right, exp " + Arrays.toString(authExpected) + " got " + authGuessed);
				  //logger.info("authors: " + em.authors);
				  if(procExpected.equals(procGuessed))
					  if(em.source=="CRF")
						  crfTruePos++;
					  else 
						  metaTruePos++;
				  else {
					  if(em.source=="CRF")
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
		  logger.info("crf author correct: " + crfAuthTruePos);
		  logger.info("crf author false positive: " + crfAuthFalsePos);
		  logger.info("crf author false negative: " + crfAuthFalseNeg);
		  logger.info("meta author correct: " + metaAuthTruePos);
		  logger.info("meta author false positive: " + metaAuthFalsePos);
		  logger.info("meta author false negative: " + metaAuthFalseNeg);

		  //TODO: write output
	  }
  }
}
