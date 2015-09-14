package org.allenai.scienceparse;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

import lombok.val;

@Test
public class PDFToCRFInputTest {
    public String filePathOfResource(String path) {
      return this.getClass().getResource(path).getFile();
    }
    
    public void testLabeledDocument() throws IOException {
      String inFile = filePathOfResource("/P14-1059.pdf");
      String target = "How to make words with vectors: Phrase generation in distributional semantics";
  	  PDFToCRFInput pdfts = new PDFToCRFInput();
  	  PDDocument pdd = PDDocument.load(new java.io.File(inFile));
  	  val seq = pdfts.getSequence(pdd, target);
  	  assert(seq.size() > 50);
  	  boolean seenPhrase = false;
  	  boolean seenHow = false;
  	  for(val i : seq) {
  		  if(!seenHow && i.getOne().word.equals("How")) {
  			  seenHow = true;
  			  assertEquals(i.getTwo(), "B");
  		  }
  		  if(!seenPhrase && i.getOne().word.equals("Phrase")) {
  			  seenPhrase = true;
  			  assertEquals(i.getTwo(), "I");
  		  }
  		  if(i.getOne().word.equals("Computational")) {
  			  assertEquals(i.getTwo(), "O");
  		  }
  	  }
    }
    
    public void testUnlabeledDocument() throws IOException {
        String inFile = filePathOfResource("/P14-1059.pdf");
        String target = null;
    	  PDFToCRFInput pdfts = new PDFToCRFInput();
    	  PDDocument pdd = PDDocument.load(new java.io.File(inFile));
    	  val seq = pdfts.getSequence(pdd, target);
    	  assert(seq.size() > 50);
    	  for(val i : seq) {
    		  if(i.getOne().word.equals("Phrase")) {
    			  assertEquals(i.getOne().font, 205.0f);
    		  }
    		  else
    			  assertEquals(i.getTwo(), null);
    	  }
    }
}
