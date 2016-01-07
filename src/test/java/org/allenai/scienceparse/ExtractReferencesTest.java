package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;
import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.scienceparse.ExtractReferences.BibStractor;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
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

  public void testFindReferencesAndCitations() throws Exception {
    ExtractReferences er = new ExtractReferences(filePathOfResource("/referencesGroundTruth.json"));

    File paper1 = new File(filePathOfResource("/4230b5328df3f8125da9b84a82d92b46a240.pdf"));
    File paper2 = new File(filePathOfResource("/c0690a1d74ab781bd54f9fa7e67267cce656.pdf"));
    //File paper = new File("e:\\data\\science-parse\\qtest\\aaai04.pdf");

    Parser.ParseOpts opts = new Parser.ParseOpts();
    opts.iterations = 10;
    opts.threads = 4;
    opts.modelFile = "src/test/resources/test.model";
    opts.headerMax = Parser.MAXHEADERWORDS;
    opts.backgroundSamples = 3;
    opts.gazetteerFile = null;
    opts.trainFraction = 0.9;
    opts.backgroundDirectory = filePathOfResource("/groundTruth.json");
    opts.minYear = -1;
    opts.checkAuthors = false;

    File f = new File(opts.modelFile);
    f.deleteOnExit();
    ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/groundTruth.json"));
    Parser.trainParser(null, pgt, resourceDirectory("/groundTruth.json"), opts, null); //assumes pdfs in same dir as groundTruth
    Parser p = new Parser(opts.modelFile);


    //paper 1:
    FileInputStream fis = new FileInputStream(paper1);
    ExtractedMetadata em = null;
    try {
      em = p.doParse(fis, Parser.MAXHEADERWORDS);
    } catch (Exception e) {
      log.info("Parse error: " + f);
      //e.printStackTrace();
    }
    fis.close();
    Pair<List<BibRecord>, BibStractor> fnd = er.findReferences(em.rawReferences);
    List<BibRecord> br = fnd.getOne();
    BibStractor bs = fnd.getTwo();

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

    List<CitationRecord> crs = ExtractReferences.findCitations(em.raw, br, bs);
    log.info("found " + crs.size() + " citations.");
    Assert.assertEquals("[12]", em.raw.get(crs.get(0).lineIdx).substring(crs.get(0).startOffset, crs.get(0).endOffset));
    Assert.assertTrue(em.raw.get(crs.get(0).lineIdx).startsWith("Keeton and Katz"));


    //paper2:
    fis = new FileInputStream(paper2);
    em = null;
    try {
      em = p.doParse(fis, Parser.MAXHEADERWORDS);
    } catch (Exception e) {
      log.info("Parse error: " + f);
      //e.printStackTrace();
    }
    fis.close();
    fnd = er.findReferences(em.rawReferences);
    br = fnd.getOne();
    bs = fnd.getTwo();
    j = 0;
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
    crs = ExtractReferences.findCitations(em.raw, br, bs);
    log.info("found " + crs.size() + " citations.");
    CitationRecord cr = crs.get(crs.size() - 1);
    log.info(cr.toString());
    Assert.assertEquals("[Shachnai and Tamir 2000a]", em.raw.get(cr.lineIdx).substring(cr.startOffset, cr.endOffset));
    log.info(em.raw.get(cr.lineIdx));
    Assert.assertTrue(em.raw.get(cr.lineIdx).startsWith("We have implemented"));


  }

}
