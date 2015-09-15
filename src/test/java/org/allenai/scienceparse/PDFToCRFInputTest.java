package org.allenai.scienceparse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.allenai.scienceparse.pdfapi.PDFExtractorTest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.testng.annotations.Test;

import com.sun.media.jfxmedia.logging.Logger;

import static org.testng.Assert.*;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class PDFToCRFInputTest {
    public String filePathOfResource(String path) {
      return this.getClass().getResource(path).getFile();
    }
    
    public void testGetPapertokens() throws IOException {
        String target = "How to make words with vectors: Phrase generation in distributional semantics";
        InputStream pdfInputStream = PDFExtractorTest.class.getResourceAsStream("/p14-1059.pdf");
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
        log.info("got " + pts.size() + " things.");
        assert(pts.size() > 50);
    }
    
/*    public void testLabeledDocument() throws IOException {
      String inFile = filePathOfResource("/P14-1059.pdf");
      String target = "How to make words with vectors: Phrase generation in distributional semantics";
  	  PDFToCRFInput pdfts = new PDFToCRFInput();
  	  PDDocument pdd = PDDocument.load(inFile);
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
        
    }
*/}
