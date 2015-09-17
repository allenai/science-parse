package org.allenai.scienceparse;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import static java.util.stream.Collectors.toList;
import static org.allenai.ml.util.IOUtils.linesFromPath;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.Evaluation;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFTrainer;
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
	
  
  	public Parser(String modelFile) {
  		
  	}
  
  public static class ParseOpts {
	  public String modelFile;
	  public int iterations;
	  public int threads;
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
  				bootstrapLabels(List<String> files) throws IOException {
	  List<List<Pair<PaperToken, String>>> labeledData = new ArrayList<>();
      PDFExtractor ext = new PDFExtractor(); 	  
     	  
      for(String f : files) {
    	  FileInputStream fis = new FileInputStream(f);
    	  PDFDoc doc = ext.extractFromInputStream(fis);
          List<PaperToken> seq = PDFToCRFInput.getSequence(doc);
          ExtractedMetadata em = new ExtractedMetadata();
          em.title = doc.meta.title;
          em.authors = doc.meta.authors;
          if(em.title == null) {
        	  logger.info("skipping " + f);
        	  continue;
          }
          if(doc.meta.createDate != null)
        	  em.year = doc.meta.createDate.getYear() + 1900;
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
      val labeledData = bootstrapLabels(files);
      // Split train/test data
      logger.info("CRF training with {} threads and {} labeled examples", 1, labeledData.size());
      val trainTestPair =
          splitData(labeledData, 0.5);
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
//      val oos = new ObjectOutputStream(new FileOutputStream(opts.modelFile));
//      logger.info("Writing model to {}", opts.modelFile);
//      oos.writeObject(crfModel); //TODO: make more lean
//      oos.close();
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
