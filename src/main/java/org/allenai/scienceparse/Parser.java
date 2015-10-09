package org.allenai.scienceparse;

import lombok.val;

import static java.util.stream.Collectors.toList;

import java.io.*;
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
  
  	public Parser(String modelFile) throws IOException {
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
    	  
    	  //logger.info(seq.stream().map((PaperToken p) -> (p.getLine()==-1)?"<S>":p.getPdfToken().token).collect(Collectors.toList()).toString());
    	  //logger.info(outSeq.toString());
    	  em = new ExtractedMetadata(seq, outSeq);
          logger.info("CRF extracted title:\r\n" + em.title);
          //logger.info("author:\r\n" + em.authors);
      }
      else {
          em = new ExtractedMetadata(doc.meta.title, doc.meta.authors, doc.meta.createDate);
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
		catch(IOException e) {
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
//      logger.info("first: " + labeledPaper.get(0).getTwo());
//      logger.info("last: " + labeledPaper.get(labeledPaper.size()-1).getTwo());
      
      return labeledPaper;
  }
  
  public static List<List<Pair<PaperToken, String>>> labelFromGroundTruth(
		  ParserGroundTruth pgt, String paperDir, int headerMax, boolean heuristicHeader) throws IOException {
	  return labelFromGroundTruth(pgt, paperDir, headerMax, heuristicHeader, pgt.papers.size());
  }
  
  public static List<List<Pair<PaperToken, String>>> labelFromGroundTruth(
		  ParserGroundTruth pgt, String paperDir, int headerMax, boolean heuristicHeader, int maxFiles) throws IOException {
	  List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
	  File dir = new File(paperDir);
	  PDFExtractor ext = new PDFExtractor();
	  for(Paper p : pgt.papers) {
		  File f = new File(dir, p.id.substring(4) + ".pdf"); //first four are directory, rest is file name
		  val res = getPaperLabels(f, p, ext, heuristicHeader, headerMax);
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
    	  labeledData = labelFromGroundTruth(pgt, paperDir, opts.headerMax, true);
      }
      if(opts.gazetteerFile != null) {
    	  ParserGroundTruth gaz = new ParserGroundTruth(opts.gazetteerFile);
    	  int stIdx = 0;
    	  int endIdx = gaz.papers.size();
    	  UnifiedSet<String> trainIds = new UnifiedSet<String>();
    	  pgt.papers.forEach((Paper p) -> trainIds.add(p.id));
          ParserLMFeatures plf = new ParserLMFeatures(gaz.papers, trainIds, stIdx, endIdx, new File(opts.backgroundDirectory), opts.backgroundSamples);
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
      Parallel.MROpts evalMrOpts = Parallel.MROpts.withIdAndThreads("mr-crf-train-eval", 1);
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
      saveModel(dos, crfModel.featureEncoder, weights);
      dos.close();
  }
  
  public static final String DATA_VERSION = "0.1";
  
  public static void saveModel(DataOutputStream dos,
		  CRFFeatureEncoder<String, PaperToken, String> fe,
		  Vector weights) throws IOException {
	  dos.writeUTF(DATA_VERSION);
	  fe.stateSpace.save(dos);
	  fe.nodeFeatures.save(dos);
	  fe.edgeFeatures.save(dos);
	  IOUtils.saveDoubles(dos, weights.toDoubles());
  }
  
  public static CRFModel<String, PaperToken, String> loadModel(
		  DataInputStream dis) throws IOException {
	  IOUtils.ensureVersionMatch(dis, DATA_VERSION);
	  val predExtractor = new PDFPredicateExtractor();
	  val stateSpace = StateSpace.load(dis);
	  Indexer<String> nodeFeatures = Indexer.load(dis);
	  Indexer<String> edgeFeatures = Indexer.load(dis);
	  Vector weights = DenseVector.of(IOUtils.loadDoubles(dis));
	  val featureEncoder = new CRFFeatureEncoder<String, PaperToken, String>
	  (predExtractor, stateSpace, nodeFeatures, edgeFeatures);
	  val weightsEncoder = new CRFWeightsEncoder<String>(stateSpace, nodeFeatures.size(), edgeFeatures.size());
	  return new CRFModel<String, PaperToken, String>(featureEncoder, weightsEncoder, weights);
  }
  
//  public static void endToEnd() {
//      Trainer.trainAndSaveModel(trainOpts);
//      val evalOpts = new Evaluator.Opts();
//      evalOpts.modelPath = modelFile.getAbsolutePath();
//      evalOpts.dataPath = filePathOfResource("/crf/test.data");
//      val accPerfPair = Evaluator.evaluateModel(evalOpts);
//  }
	  
  public static void main(String[] args) throws Exception {
	  if(!((args.length==3 && args[0].equalsIgnoreCase("bootstrap"))||
			  (args.length==4 && args[0].equalsIgnoreCase("parse"))||
			  (args.length==6 && args[0].equalsIgnoreCase("learn")))) {
		  System.err.println("Usage: bootstrap <input dir> <model output file>");
		  System.err.println("OR:    learn <ground truth file> <gazetteer file> <input dir> <model output file> <background dir>");
		  System.err.println("OR:    parse <input dir> <model input file> <output dir>");
	  }
	  else if(args[0].equalsIgnoreCase("bootstrap")) {
		  File inDir = new File(args[1]);
		  List<File> inFiles = Arrays.asList(inDir.listFiles()); 
		  //HACK: limit to 5000 for development
		  //inFiles = inFiles.subList(0,  Math.min(inFiles.size(), 2000));
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
		  opts.iterations =  pgt.papers.size(); //HACK because training throws exceptions if you iterate too much
		  opts.threads = 4;
		  opts.backgroundSamples = 100;
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
  }
}
