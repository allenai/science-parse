package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.pdfbox.pdmodel.PDDocument;
import org.allenai.scienceparse.ExtractReferences.BibStractor;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

@Test
@Slf4j
public class ExtractReferencesTest {
  public String filePathOfResource(String path) {
    return this.getClass().getResource(path).getFile();
  }

  public String resourceDirectory(String path) {
    return (new File(this.getClass().getResource(path).getFile())).getParent();
  }

  public void testAuthorPattern() throws Exception {
    String auth = ExtractReferences.authLastCommaInitial;
    Assert.assertTrue(Pattern.matches(auth, "Jones, C. M."));
    Assert.assertTrue(Pattern.matches(auth, "Hu, Y."));
    Assert.assertTrue(Pattern.matches(
      "(" + auth +
        "(?:; " + auth + ")*)",
      //"Jones, C. M.; Henry, E. R.; Hu, Y."));
      "Jones, C. M.; Henry, E. R.; Hu, Y.; Chan, C. K.; Luck, S. D.; Bhuyan, A.; Roder, H.; Hofrichter, J."));
    String auth2 = ExtractReferences.authInitialsLast;
    String test2 = "S.H. Han";
    Assert.assertTrue(Pattern.matches(auth2, test2));
    String auth3 = ExtractReferences.authInitialsLastList + "\\.";
    String test3 = "B.K. Shim, Y.K. Cho, J.B. Won, and S.H. Han.";
    Assert.assertTrue(Pattern.matches(auth3, test3));

    String test4 = "D. Kowalsky and A. Pelc,";
    String auth4 = ExtractReferences.authInitialsLastList + ",";
    Assert.assertTrue(Pattern.matches(auth4, test4));
    String test5 = "E. Agichtein and L. Gravano.";
    String auth5 = ExtractReferences.authInitialsLastList + "(?:,|\\.)";
    Assert.assertTrue(Pattern.matches(auth5, test5));
    String test6 = "E. Agichtein and L.";
    String auth6 = ExtractReferences.authInitialsLastList + "(?:,|\\.)";
    Assert.assertFalse(Pattern.matches(auth6, test6));


  }

  public void testNumberDotAuthorNoTitleBibRecordParser() {
    val f = new ExtractReferences.NumberDotAuthorNoTitleBibRecordParser();
    String line = "1. Jones, C. M.; Henry, E. R.; Hu, Y.; Chan, C. K.; Luck S. D.; Bhuyan, A.; Roder, H.; Hofrichter, J.; "
      + "Eaton, W. A. Proc Natl Acad Sci USA 1993, 90, 11860.";
    BibRecord br = f.parseRecord(line);
    Assert.assertTrue(br != null);
    Assert.assertEquals(br.year, 1993);
  }

  private Pair<List<String>, List<String>> parseDoc(final File file) throws IOException {
    final PDDocument pdDoc;
    try(final InputStream is = new FileInputStream(file)) {
      pdDoc = PDDocument.load(is);
    }
    val ext = new PDFExtractor();
    final PDFDoc doc = ext.extractResultFromPDDocument(pdDoc).document;
    final List<String> raw = PDFDocToPartitionedText.getRaw(doc);
    final List<String> rawReferences = PDFDocToPartitionedText.getRawReferences(doc);
    return Tuples.pair(raw, rawReferences);
  }

  public void testCRFExtractor() throws Exception {
//    ExtractReferences er = new ExtractReferences(Parser.getDefaultGazetteer().toString(),
//        Parser.getDefaultBibModel().toString());
    
  ExtractReferences er = new ExtractReferences(Parser.getDefaultGazetteer().toString(),
      filePathOfResource("/model-bib-crf-test.dat"));

    
    File paper2 = new File(filePathOfResource("/c0690a1d74ab781bd54f9fa7e67267cce656.pdf"));
    final Pair<List<String>, List<String>> content = parseDoc(paper2);
    final List<String> raw = content.getOne();
    final List<String> rawReferences = content.getTwo();
    final Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(rawReferences);
    final List<BibRecord> br = fnd.getOne();
    final BibStractor bs = fnd.getTwo();

    int j = 0;
    for (BibRecord b : br)
      log.info("reference " + (j++) + " " + (b == null ? "null" : b.toString()));
    for (BibRecord b : br)
      Assert.assertNotNull(b);
    Assert.assertEquals(16, br.size());
    BibRecord tbr = br.get(15);
    Assert.assertEquals("DASD dancing: A disk load balancing optimization scheme for video-on-demand computer systems", tbr.title);
    Assert.assertEquals("Wolf et al\\.,? 1995", tbr.citeRegEx.pattern());
    Assert.assertEquals("J. Wolf", tbr.author.get(0));
    Assert.assertEquals("P. Yu", tbr.author.get(1));
    Assert.assertEquals("H. Shachnai", tbr.author.get(2));
    Assert.assertEquals(1995, tbr.year);
    log.info(br.get(0).venue.trim());
    Assert.assertTrue(br.get(0).venue.trim().startsWith("ACM SIGMOD Conference, "));
    final List<CitationRecord> crs = ExtractReferences.findCitations(raw, br, bs);
    log.info("found " + crs.size() + " citations.");
    CitationRecord cr = crs.get(crs.size() - 1);
    log.info(cr.toString());
    Assert.assertEquals("[Shachnai and Tamir 2000a]", cr.context.substring(cr.startOffset, cr.endOffset));
    log.info(cr.context);
    Assert.assertTrue(cr.context.startsWith("We have implemented"));
  }
  
  public void testFindReferencesAndCitations() throws Exception {
    ExtractReferences er = new ExtractReferences(Parser.getDefaultGazetteer().toString());

    File paper1 = new File(filePathOfResource("/2a774230b5328df3f8125da9b84a82d92b46a240.pdf"));
    File paper2 = new File(filePathOfResource("/c0690a1d74ab781bd54f9fa7e67267cce656.pdf"));

    //paper 1:
    {
      final Pair<List<String>, List<String>> content = parseDoc(paper1);
      final List<String> raw = content.getOne();
      final List<String> rawReferences = content.getTwo();
      final Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(rawReferences);
      final List<BibRecord> br = fnd.getOne();
      final BibStractor bs = fnd.getTwo();

      int j = 0;
      for (BibRecord b : br)
        log.info("reference " + (j++) + " " + (b == null ? "null" : b.toString()));
      for (BibRecord b : br)
        Assert.assertNotNull(b);
      Assert.assertEquals(17, br.size());
      Assert.assertEquals("Scalable video data placement on parallel disk arrays", br.get(0).title);
      Assert.assertEquals("1", br.get(0).citeRegEx.pattern());
      Assert.assertEquals("E. Chang", br.get(0).author.get(0));
      Assert.assertEquals("A. Zakhor", br.get(0).author.get(1));
      Assert.assertEquals(1994, br.get(0).year);
      Assert.assertTrue(br.get(0).venue.trim().startsWith("IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208"));
      //can't use below because dash is special:
//		Assert.assertEquals("IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
//				+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221.", br.get(0).venue.trim());

      final List<CitationRecord> crs = ExtractReferences.findCitations(raw, br, bs);
      log.info("found " + crs.size() + " citations.");
      Assert.assertEquals("[12]", crs.get(0).context.substring(crs.get(0).startOffset, crs.get(0).endOffset));
      Assert.assertTrue(crs.get(0).context.startsWith("Keeton and Katz"));
    }


    //paper2:
    {
      final Pair<List<String>, List<String>> content = parseDoc(paper2);
      final List<String> raw = content.getOne();
      final List<String> rawReferences = content.getTwo();
      final Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(rawReferences);
      final List<BibRecord> br = fnd.getOne();
      final BibStractor bs = fnd.getTwo();

      int j = 0;
      for (BibRecord b : br)
        log.info("reference " + (j++) + " " + (b == null ? "null" : b.toString()));
      for (BibRecord b : br)
        Assert.assertNotNull(b);
      Assert.assertEquals(16, br.size());
      BibRecord tbr = br.get(15);
      Assert.assertEquals("DASD dancing: A disk load balancing optimization scheme for video-on-demand computer systems", tbr.title);
      Assert.assertEquals("Wolf et al\\.,? 1995", tbr.citeRegEx.pattern());
      Assert.assertEquals("J. Wolf", tbr.author.get(0));
      Assert.assertEquals("P. Yu", tbr.author.get(1));
      Assert.assertEquals("H. Shachnai", tbr.author.get(2));
      Assert.assertEquals(1995, tbr.year);
      log.info(br.get(0).venue.trim());
      Assert.assertTrue(br.get(0).venue.trim().startsWith("ACM SIGMOD Conference, "));
      final List<CitationRecord> crs = ExtractReferences.findCitations(raw, br, bs);
      log.info("found " + crs.size() + " citations.");
      CitationRecord cr = crs.get(crs.size() - 1);
      log.info(cr.toString());
      Assert.assertEquals("[Shachnai and Tamir 2000a]", cr.context.substring(cr.startOffset, cr.endOffset));
      log.info(cr.context);
      Assert.assertTrue(cr.context.startsWith("We have implemented"));
    }
  }
}
