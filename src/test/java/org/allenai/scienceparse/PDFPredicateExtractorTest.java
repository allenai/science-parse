package org.allenai.scienceparse;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import lombok.extern.slf4j.Slf4j;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

@Slf4j
public class PDFPredicateExtractorTest {

  private void titleFontFeatureCheckForStream(InputStream pdfInputStream) throws IOException {
    String target = "How to make words with vectors: Phrase generation in distributional semantics";
    PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
    List<PaperToken> pts = PDFToCRFInput.getSequence(doc);
//    Iterator<PaperToken> it = pts.iterator();
//    while(it.hasNext()) {
//      PaperToken pt = it.next();
//      log.info((pt.getPdfToken()==null)?"null":pt.getPdfToken().token + " f:" + pt.getPdfToken().fontMetrics.ptSize);
//    }
    Pair<Integer, Integer> pos = PDFToCRFInput.findString(PDFToCRFInput.asStringList(pts), target);
    PDFPredicateExtractor ppe = new PDFPredicateExtractor();
    List<ObjectDoubleMap<String>> preds = ppe.nodePredicates(pts);
    int[] idxes = new int[]{pos.getOne() - 1, pos.getOne(),
      pos.getTwo(), pos.getTwo() + 1, pos.getTwo() + 2};
    log.info("fonts for " + Arrays.toString(idxes));
    log.info(Arrays.toString(Arrays.stream(idxes).mapToDouble((int a) -> preds.get(a).get("%font")).toArray()));
    log.info("tokens for " + Arrays.toString(idxes));
    log.info(Arrays.toString(Arrays.stream(idxes).mapToObj((int a) -> pts.get(a).getPdfToken().token).toArray()));


    Assert.assertEquals(preds.get(pos.getOne()).get("%fcb"), 1.0);
    Assert.assertTrue(!preds.get(pos.getTwo() - 1).containsKey("%fcb"));
    log.info("Title font change features correct.");
  }

  @Test
  public void titleFontFeatureCheck() throws IOException {
    InputStream is = PDFPredicateExtractorTest.class.getResource("/P14-1059.pdf").openStream();
    titleFontFeatureCheckForStream(is);
    is.close();
  }

  public void titleFontForExplicitFilePath(String f) throws IOException {
    InputStream is = new FileInputStream(new File(f));
    titleFontFeatureCheckForStream(is);
    is.close();
  }

  @Test
  public void testCaseMasks() {
    String cap = "Exploring";
    List<String> ls = PDFPredicateExtractor.getCaseMasks(cap);
    Assert.assertEquals(ls.size(), 2);
    Assert.assertTrue(ls.contains("%Xxx"));
    Assert.assertTrue(ls.contains("%letters"));

    String nonSimple = "Dharmaratnå";
    ls = PDFPredicateExtractor.getCaseMasks(nonSimple);
    Assert.assertTrue(ls.contains("%hasNonAscii"));
    Assert.assertTrue(!ls.contains("%hasAt"));

    String email = "bob@joe.com";
    ls = PDFPredicateExtractor.getCaseMasks(email);
    Assert.assertTrue(ls.contains("%hasAt"));
  }

	public static void main(String [] args) throws Exception {
		(new PDFPredicateExtractorTest()).titleFontForExplicitFilePath("src\\test\\resources\\P14-1059.pdf");
	}

}
