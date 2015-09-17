package org.allenai.scienceparse;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;

import lombok.val;

import static org.testng.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collector;
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
  
  public void testParser() throws IOException {
  		Parser.ParseOpts opts = new Parser.ParseOpts();
  		opts.iterations = 20;
  		opts.threads = 4;
  		opts.modelFile = "default.model";
	  Parser.trainParser(resolveKeys(pdfKeys), opts);
  }
}