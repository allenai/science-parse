package org.allenai.scienceparse;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.map.primitive.ObjectDoubleMap;

import lombok.val;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Test
public class ParserTest {

  public String filePathOfResource(String path) {
    return this.getClass().getResource(path).getFile();
  }
  
  private final static List<String> pdfKeys = Arrays.asList("/bagnell11", "/seung08", "/ding11", "/mooney05",
	        "/roark13", "/dyer12", "/bohnet09", "/P14-1059", "/map-reduce", "/fader11", "/proto06",
	        "/agarwal11", "/smola10", "/senellart10", "/zolotov04","/pedersen04", "/smith07",
	        "/aimag10");
  
  public void testBootstrap() throws IOException {
	  val labeledData = Parser.bootstrapLabels(resolveKeys(pdfKeys));
	   PDFPredicateExtractor ppe = new PDFPredicateExtractor();
	   //NOTE 6 should be index of P14-1059, because only mooney gets skipped
	   List<PaperToken> justTokens = labeledData.get(6).stream().map(p -> 
	   p.getOne()).collect(Collectors.toList());
        List<ObjectDoubleMap<String>> preds = ppe.nodePredicates(justTokens);
      Assert.assertTrue((preds.get(26).containsKey("%fcb")));
  }
  
  private List<String> resolveKeys(List<String> keys) {
	  return keys.stream().map((String s) -> filePathOfResource(s + ".pdf")).collect(Collectors.toList());
  }
  
  private void testModel(String id, Parser p) throws Exception {
        String jsonPath = id + ".extraction.json";
        String pdfPath = id + ".pdf";
        InputStream jsonInputStream = getClass().getResourceAsStream(jsonPath);
        InputStream pdfInputStream = getClass().getResourceAsStream(pdfPath);
        PDFExtractor.Options opts = PDFExtractor.Options.builder().useHeuristicTitle(true).build();
        PDFDoc doc = new PDFExtractor(opts).extractFromInputStream(pdfInputStream);
        List<List<?>> arr =  new ObjectMapper().readValue(jsonInputStream, List.class);
        jsonInputStream.close();
        pdfInputStream.close();
        pdfInputStream = getClass().getResourceAsStream(pdfPath);
        ExtractedMetadata em = p.doParse(pdfInputStream);
        for (List<?> elems : arr) {
            String type = (String) elems.get(0);
            Object expectedValue = elems.get(1);
            if (type.equalsIgnoreCase("title")) {
                String guessValue = em.title;
                Assert.assertEquals(guessValue, expectedValue, String.format("Title error on %s", id));
            }
            if (type.equalsIgnoreCase("author")) {
            	Assert.assertTrue(em.authors.contains(expectedValue), 
            	"could not find author " + expectedValue + " in extracted authors " + em.authors.toString());
            }
            if (type.equalsIgnoreCase("year")) {
                Assert.assertEquals(em.year, expectedValue, String.format("Year error on %s", id));
            }
        }
    }
  
  public void testParser() throws Exception {
  	Parser.ParseOpts opts = new Parser.ParseOpts();
  	opts.iterations = 20;
  	opts.threads = 4;
  	opts.modelFile = "src/test/resources/test.model";
  	File f = new File(opts.modelFile);
  	f.deleteOnExit();
	Parser.trainParser(resolveKeys(pdfKeys), opts);
	Parser p = new Parser(opts.modelFile);
	for(String s : pdfKeys) {
	  testModel(s, p);
	}
  }
  
}