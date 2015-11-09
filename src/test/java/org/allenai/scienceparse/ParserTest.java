package org.allenai.scienceparse;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.allenai.scienceparse.pdfapi.PDFExtractorTest;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import com.sun.media.jfxmedia.logging.Logger;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Test
@Slf4j
public class ParserTest {

  public String filePathOfResource(String path) {
    return this.getClass().getResource(path).getFile();
  }
  
  public String resourceDirectory(String path) {
	  return (new File(this.getClass().getResource(path).getFile())).getParent();
  }
  
  private final static List<String> pdfKeys = Arrays.asList("/bagnell11", "/seung08", "/ding11", "/mooney05",
	        "/roark13", "/dyer12", "/bohnet09", "/P14-1059", "/map-reduce", "/fader11", "/proto06",
	        "/agarwal11", "/smola10", "/senellart10", "/zolotov04","/pedersen04", "/smith07",
	        "/aimag10");
  
  public void testBootstrap() throws IOException {
	  List<List<Pair<PaperToken, String>>>  labeledData = Parser.bootstrapLabels(resolveKeys(pdfKeys), 100, true);
	   PDFPredicateExtractor ppe = new PDFPredicateExtractor();
	   //NOTE 6 should be index of P14-1059, because only mooney gets skipped
	   List<PaperToken> justTokens = labeledData.get(6).stream().map(p -> 
	   p.getOne()).collect(Collectors.toList());
	   
        List<ObjectDoubleMap<String>> preds = ppe.nodePredicates(justTokens);
        
      Assert.assertTrue((preds.get(26).containsKey("%fcb")));
  }
  
  private List<File> resolveKeys(List<String> keys) {
	  return keys.stream().map((String s) -> new File(filePathOfResource(s + ".pdf"))).collect(Collectors.toList());
  }
   
  private Pair<Double, Double> testModel(String id, Parser p) throws Exception {
        String jsonPath = id + ".extraction.json";
        String pdfPath = id + ".pdf";
        InputStream jsonInputStream = getClass().getResourceAsStream(jsonPath);
        InputStream pdfInputStream = getClass().getResourceAsStream(pdfPath);
        List<List<?>> arr =  new ObjectMapper().readValue(jsonInputStream, List.class);
        jsonInputStream.close();
        ExtractedMetadata em = p.doParse(pdfInputStream, Parser.MAXHEADERWORDS);
        pdfInputStream.close();
        
        double titleTP = 0.0;
        double titleFP = 0.0;
        double authorTP = 0.0;
        double authorFN = 0.0;
        for (List<?> elems : arr) {
            String type = (String) elems.get(0);
            Object expectedValue = elems.get(1);
            if (type.equalsIgnoreCase("title")) {
                String guessValue = em.title;
                if(guessValue != null && guessValue.equals(expectedValue))
                	titleTP++;
                else
                	titleFP++;
                //Assert.assertEquals(guessValue, expectedValue, String.format("Title error on %s", id));
            }
            if (type.equalsIgnoreCase("author")) {
            	if(em.authors.contains(expectedValue))
            		authorTP++;
            	else
            		authorFN++;
            	//Assert.assertTrue(em.authors.contains(expectedValue), 
            	//"could not find author " + expectedValue + " in extracted authors " + em.authors.toString());
            }
//            if (type.equalsIgnoreCase("year")) {
//                Assert.assertEquals(em.year, expectedValue, String.format("Year error on %s", id));
//            }
        }
        return Tuples.pair((titleTP/(titleTP+titleFP+0.000001)), authorTP/(authorTP + authorFN+0.000001));
    }
  
  public void testParser() throws Exception {
	  
	Parser.ParseOpts opts = new Parser.ParseOpts();
  	opts.iterations = 10;
  	opts.threads = 4;
  	opts.modelFile = "src/test/resources/test.model";
  	opts.headerMax = 100;
  	opts.trainFraction = 0.9;
  	File f = new File(opts.modelFile);
  	f.deleteOnExit();
	Parser.trainParser(resolveKeys(pdfKeys), null, null, opts, null);
	Parser p = new Parser(opts.modelFile);
	double avgTitlePrec = 0.0;
	double avgAuthorRec = 0.0;
	double cases = 0.0;
	for(String s : pdfKeys) {
	  val res = testModel(s, p);
	  cases++;
	  avgTitlePrec += res.getOne();
	  avgAuthorRec += res.getTwo();
	}
	avgTitlePrec /= cases;
	avgAuthorRec /= cases;
	log.info("Title precision = recall = " + avgTitlePrec);
	log.info("Author recall = " + avgAuthorRec);
  }
  
  public void testParserWithGroundTruth() throws Exception {
	  Parser.ParseOpts opts = new Parser.ParseOpts();
	  	opts.iterations = 10;
	  	opts.threads = 4;
	  	opts.modelFile = "src/test/resources/test.model";
	  	opts.headerMax = Parser.MAXHEADERWORDS;
	  	opts.backgroundSamples = 3;
	  	opts.gazetteerFile = null;
	  	opts.trainFraction = 0.9;
	  	opts.backgroundDirectory = resourceDirectory("/groundTruth.json");
	  	opts.minYear = -1;
	  	opts.checkAuthors = false;

	  	File f = new File(opts.modelFile);
	  	f.deleteOnExit();
	  	ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/groundTruth.json"));
		Parser.trainParser(null, pgt, resourceDirectory("/groundTruth.json"), opts, null); //assumes pdfs in same dir as groundTruth
		Parser p = new Parser(opts.modelFile);
		double avgTitlePrec = 0.0;
		double avgAuthorRec = 0.0;
		double cases = 0.0;
		for(String s : pdfKeys) {
		  val res = testModel(s, p);
		  cases++;
		  avgTitlePrec += res.getOne();
		  avgAuthorRec += res.getTwo();
		}
		avgTitlePrec /= cases;
		avgAuthorRec /= cases;
		log.info("Title precision = recall = " + avgTitlePrec);
		log.info("Author recall = " + avgAuthorRec);
  }
  
  public void testParserGroundTruth() throws Exception {
	ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/groundTruth.json"));
	Assert.assertEquals(pgt.papers.size(), 4);
  }
  
  public void testParserRobustness() throws Exception {
//	  ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/papers-parseBugs.json"));
//	  Assert.assertEquals(false, true);
  }
  
}