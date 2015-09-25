package org.allenai.scienceparse;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.util.Arrays;
import java.util.List;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.gs.collections.api.tuple.Pair;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class PDFToCRFInputTest {
    public String filePathOfResource(String path) {
      return this.getClass().getResource(path).getFile();
    }
    
    public void testGetPaperTokens() throws IOException {
        InputStream pdfInputStream = PDFToCRFInputTest.class.getResourceAsStream("/p14-1059.pdf");
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
        log.info("got " + pts.size() + " things.");
        assert(pts.size() > 50);
    }
    
    public void testFindString() throws IOException {
    	String target = "How to make words with vectors: Phrase generation in distributional semantics";
    	InputStream pdfInputStream = PDFToCRFInputTest.class.getResourceAsStream("/p14-1059.pdf");
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
        Pair<Integer, Integer> pos = PDFToCRFInput.findString(pts, target);
        Pair<Integer, Integer> posNot = PDFToCRFInput.findString(pts, "this string won't be found");
        
        Assert.assertTrue(pos != null && pos.getOne()>0 && (pos.getTwo() - pos.getOne() == 11));
        log.info("found title at " + pos.getOne() + ", " + pos.getTwo());
        Assert.assertTrue(posNot == null);
    }
    
    public void testLabelMetadata() throws IOException {
    	InputStream pdfInputStream = PDFToCRFInputTest.class.getResourceAsStream("/p14-1059.pdf");
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
        ExtractedMetadata em = new ExtractedMetadata("How to make words with vectors: Phrase generation in distributional semantics",
        		Arrays.asList("Georgiana Dinu", "Marco Baroni"), new Date(1388556000000L));
        val labeledData = PDFToCRFInput.labelMetadata(pts, em);
        Assert.assertEquals(labeledData.get(24+1).getTwo(), "O");
        Assert.assertEquals(labeledData.get(25+1).getTwo(), "B_T");
        Assert.assertEquals(labeledData.get(32+1).getTwo(), "I_T");
        Assert.assertEquals(labeledData.get(35+1).getTwo(), "E_T");
        Assert.assertEquals(labeledData.get(36+1).getTwo(), "B_A");
        Assert.assertEquals(labeledData.get(60+1).getTwo(), "O");
        Assert.assertEquals(labeledData.get(60+1).getOne(), pts.get(60)); //off by one due to start/stop
        Assert.assertEquals(labeledData.get(0).getTwo(), "<S>");
        Assert.assertEquals(labeledData.get(labeledData.size()-1).getTwo(), "</S>");
    }
    
/*    public void testLabeledDocument() throws IOException {
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

    }
*/}
