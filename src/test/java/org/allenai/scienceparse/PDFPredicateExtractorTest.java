package org.allenai.scienceparse;

import java.util.Arrays;
import java.util.List;

import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.gs.collections.api.map.primitive.DoubleObjectMap;
import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.net.URL;

@Slf4j
public class PDFPredicateExtractorTest { 
	
	private void titleFontFeatureCheckForStream(InputStream pdfInputStream) throws IOException {
		String target = "How to make words with vectors: Phrase generation in distributional semantics";
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
        Pair<Integer, Integer> pos = PDFToCRFInput.findString(pts, target);
        PDFPredicateExtractor ppe = new PDFPredicateExtractor();
        List<ObjectDoubleMap<String>> preds = ppe.nodePredicates(pts);
        int [] idxes = new int [] {pos.getOne() - 1, pos.getOne(),
        		pos.getTwo(), pos.getTwo() + 1, pos.getTwo() + 2};
        log.info("fonts for " + Arrays.toString(idxes));
        log.info(Arrays.toString(Arrays.stream(idxes).mapToDouble((int a) -> preds.get(a).get("%font")).toArray()));
        log.info("tokens for " + Arrays.toString(idxes));
        log.info(Arrays.toString(Arrays.stream(idxes).mapToObj((int a) -> pts.get(a).getPdfToken().token).toArray()));
        
        
        Assert.assertEquals(preds.get(pos.getOne()).get("%fcb"), 1.0);    
        Assert.assertTrue(!preds.get(pos.getTwo()).containsKey("%fcb"));
//        Assert.assertEquals(preds.get(pos.getTwo()).get("%fcf"), 1.0);    
//        Assert.assertTrue(!preds.get(pos.getOne()).containsKey("%fcf"));
        log.info("Title font change features correct.");
	}
	
	@Test
	public void titleFontFeatureCheck() throws IOException {
		InputStream is = PDFPredicateExtractorTest.class.getResource("/p14-1059.pdf").openStream();
    	titleFontFeatureCheckForStream(is);
    	is.close();
	}
	
	public void titleFontForExplicitFilePath(String f) throws IOException {
		InputStream is = new FileInputStream(new File(f));
    	titleFontFeatureCheckForStream(is);
    	is.close();
	}
	
//	public static void main(String [] args) throws Exception {
//		(new PDFPredicateExtractorTest()).titleFontForExplicitFilePath("src\\test\\resources\\P14-1059.pdf");
//	}
	
}
