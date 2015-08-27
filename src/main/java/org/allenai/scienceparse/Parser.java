package org.allenai.scienceparse;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

import org.allenai.ml.sequences.crf.conll.Evaluator;
import org.allenai.ml.sequences.crf.conll.Trainer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;

@Slf4j
public class Parser {

  public static void invokeBox(String inFile, String outFile) throws Exception {
	  PDFToCRFInput pdfts = new PDFToCRFInput();
	  PDDocument pdd = PDDocument.load(inFile);
	  PDDocumentCatalog cat = pdd.getDocumentCatalog();
	  //String t = pdfts.getText(pdd);
	  val seq = pdfts.getSequence(pdd, "TITLE");
	  
	  System.out.println("here it is.");
	  for(val i : seq) {
		  System.out.println(i.getOne().word + "\t" + i.getOne().font);
	  }
  }
	  
  public static void main(String[] args) throws Exception {
    // TODO Actually do PDF parsing
    log.info("Hello {}", "world");
    invokeBox("c:\\git\\science-parse\\src\\test\\resources\\P14-1059.pdf", null);
  }
}
