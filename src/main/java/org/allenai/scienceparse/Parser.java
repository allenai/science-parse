package org.allenai.scienceparse;

import lombok.val;

import static java.util.stream.Collectors.toList;

import java.io.*;
import java.util.*;

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
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gs.collections.api.tuple.Pair;
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
  }
  
  public ExtractedMetadata doParse(InputStream is) throws IOException {
	  PDFExtractor ext = new PDFExtractor(); 	  
	  PDFDoc doc = ext.extractFromInputStream(is);
      List<PaperToken> seq = PDFToCRFInput.getSequence(doc);
      ExtractedMetadata em = null;
      if(doc.meta.title == null) { //use the model
    	  val outSeq = model.bestGuess(seq);
    	  //logger.info(outSeq.toString());
    	  em = new ExtractedMetadata(seq, outSeq);
          logger.info("CRF extracted title: " + em.title);          
      }
      else {
          em = new ExtractedMetadata(doc.meta.title, doc.meta.authors, doc.meta.createDate);
      }
      return em;
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
 
  public static List<List<Pair<PaperToken, String>>> 
  				bootstrapLabels(List<String> files, int headerMax) throws IOException {
	  List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
      PDFExtractor ext = new PDFExtractor(); 	  
     	  
      for(String f : files) {
    	  FileInputStream fis = new FileInputStream(f);
    	  PDFDoc doc = ext.extractFromInputStream(fis);
    	  fis.close();
          List<PaperToken> seq = PDFToCRFInput.getSequence(doc);
          seq = seq.subList(0, headerMax);
          ExtractedMetadata em = new ExtractedMetadata(doc.meta.title, doc.meta.authors,
        		  doc.meta.createDate);
          if(em.title == null) {
        	  logger.info("skipping " + f);
        	  continue;
          }
          if(doc.meta.createDate != null) {
        	  Calendar cal = Calendar.getInstance();
              cal.setTime(doc.getMeta().getCreateDate());
              em.year = cal.get(Calendar.YEAR);
          }
          fis.close();
          logger.info("finding " + em.toString());
          List<Pair<PaperToken, String>> labeledPaper = 
        		  PDFToCRFInput.labelMetadata(seq, em);
          logger.info("first: " + labeledPaper.get(0).getTwo());
          logger.info("last: " + labeledPaper.get(labeledPaper.size()-1).getTwo());
          
          labeledData.add(labeledPaper);
      }
      return labeledData;
  }
  
  //borrowing heavily from conll.Trainer
  public static void trainParser(List<String> files, ParseOpts opts) 
		  throws IOException {
      val predExtractor = new PDFPredicateExtractor();
      val labeledData = bootstrapLabels(files, opts.headerMax);

      // Split train/test data
      logger.info("CRF training with {} threads and {} labeled examples", opts.threads, labeledData.size());
      val trainTestPair =
          splitData(labeledData, 0.1);
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

      CRFModel<String, PaperToken, String> crfModel = trainer.train(trainLabeledData);
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
    // TODO Actually do PDF parsing
//    logger.info("Hello {}", "world");
    String [] files = new String [] {
    		"c:\\git\\science-parse\\src\\test\\resources\\P14-1059.pdf",
    		"c:\\git\\science-parse\\src\\test\\resources\\P07-1088.pdf",
    		"c:\\git\\science-parse\\src\\test\\resources\\bunescu-acl07.pdf",
    		"c:\\git\\science-parse\\src\\test\\resources\\P14-1059.pdf",
    		"c:\\git\\science-parse\\src\\test\\resources\\P07-1088.pdf",
    		"c:\\git\\science-parse\\src\\test\\resources\\bunescu-acl07.pdf"
    };
    String [] titles = new String [] {
    		"How to make words with vectors: Phrase generation in distributional semantics",
    		"Sparse Information Extraction: Unsupervised Language Models to the Rescue",
    		"Learning to Extract Relations from the Web using Minimal Supervision",
    		"How to make words with vectors: Phrase generation in distributional semantics",
    		"Sparse Information Extraction: Unsupervised Language Models to the Rescue",
    		"Learning to Extract Relations from the Web using Minimal Supervision"
    };
    //trainParser(files,  titles);
//    ConllCRFEndToEndTest ct = new ConllCRFEndToEndTest();
//    ct.testEndToEnd();
    //invokeBox("c:\\git\\science-parse\\src\\test\\resources\\P14-1059.pdf", null);
  }
}
