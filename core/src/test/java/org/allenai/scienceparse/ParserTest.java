package org.allenai.scienceparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Test
@Slf4j
public class ParserTest {

  private final static List<String> pdfKeys = Arrays.asList("/bagnell11", "/seung08", "/ding11", "/mooney05",
    "/roark13", "/dyer12", "/bohnet09", "/P14-1059", "/map-reduce", "/fader11", "/proto06",
    "/agarwal11", "/smola10", "/senellart10", "/zolotov04", "/pedersen04", "/smith07",
    "/aimag10");

  public static String filePathOfResource(String path) {
    return ParserTest.class.getResource(path).getFile();
  }

  public static String resourceDirectory(String path) {
    return (new File(ParserTest.class.getResource(path).getFile())).getParent();
  }

  public void testBootstrap() throws IOException {
    List<List<Pair<PaperToken, String>>> labeledData = Parser.bootstrapLabels(resolveKeys(pdfKeys), 100);
    PDFPredicateExtractor ppe = new PDFPredicateExtractor();
    //NOTE 6 should be index of P14-1059, because only mooney gets skipped
    List<PaperToken> justTokens = labeledData.get(6).stream().map(p ->
      p.getOne()).collect(Collectors.toList());

    log.info("test bootstrap tokens: " + justTokens.stream().map(t -> (t==null)?"":t.toStringShort()).collect(Collectors.toList()).toString());
    
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
    List<List<?>> arr = new ObjectMapper().readValue(jsonInputStream, List.class);
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
        if (guessValue != null && guessValue.equals(expectedValue))
          titleTP++;
        else
          titleFP++;
        //Assert.assertEquals(guessValue, expectedValue, String.format("Title error on %s", id));
      }
      if (type.equalsIgnoreCase("author")) {
        if (em.authors.contains(expectedValue))
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
    return Tuples.pair((titleTP / (titleTP + titleFP + 0.000001)), authorTP / (authorTP + authorFN + 0.000001));
  }

  public void testParser() throws Exception {
    final File testModelFile = File.createTempFile("science-parse-test-model.", ".dat");
    testModelFile.deleteOnExit();

    Parser.ParseOpts opts = new Parser.ParseOpts();
    opts.iterations = 10;
    opts.threads = 4;
    opts.modelFile = testModelFile.getPath();
    opts.headerMax = 100;
    opts.trainFraction = 0.9;
    File f = new File(opts.modelFile);
    f.deleteOnExit();
    Parser.trainParser(resolveKeys(pdfKeys), null, null, opts);
    final Parser p = new Parser(
            testModelFile,
            Parser.getDefaultGazetteer().toFile(),
            Parser.getDefaultBibModel().toFile());
    double avgTitlePrec = 0.0;
    double avgAuthorRec = 0.0;
    double cases = 0.0;
    for (String s : pdfKeys) {
      val res = testModel(s, p);
      cases++;
      avgTitlePrec += res.getOne();
      avgAuthorRec += res.getTwo();
    }
    avgTitlePrec /= cases;
    avgAuthorRec /= cases;
    log.info("Title precision = recall = " + avgTitlePrec);
    log.info("Author recall = " + avgAuthorRec);

    testModelFile.delete();
  }

  public void testParserWithGroundTruth() throws Exception {
    final File testModelFile = File.createTempFile("science-parse-test-model.", ".dat");
    testModelFile.deleteOnExit();

    Parser.ParseOpts opts = new Parser.ParseOpts();
    opts.iterations = 10;
    opts.threads = 4;
    opts.modelFile = testModelFile.getPath();
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
    Parser.trainParser(
            null,
            pgt,
            new DirectoryPaperSource(new File(resourceDirectory("/groundTruth.json"))),
            opts); //assumes pdfs in same dir as groundTruth
    final Parser p = new Parser(
            testModelFile,
            Parser.getDefaultGazetteer().toFile(),
            Parser.getDefaultBibModel().toFile());
    double avgTitlePrec = 0.0;
    double avgAuthorRec = 0.0;
    double cases = 0.0;
    for (String s : pdfKeys) {
      val res = testModel(s, p);
      cases++;
      avgTitlePrec += res.getOne();
      avgAuthorRec += res.getTwo();
    }
    avgTitlePrec /= cases;
    avgAuthorRec /= cases;
    log.info("Title precision = recall = " + avgTitlePrec);
    log.info("Author recall = " + avgAuthorRec);

    testModelFile.delete();
  }

  public void testParserGroundTruth() throws Exception {
    ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/groundTruth.json"));
    Assert.assertEquals(pgt.papers.size(), 4);
  }

  public void testParserRobustness() throws Exception {
//	  ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/papers-parseBugs.json"));
//	  Assert.assertEquals(false, true);
  }

  public void dummyTestRefExtraction() throws Exception {
    Parser p = new Parser();
    File inDir = new File("e:\\data\\science-parse\\debug2");
    File outDir = new File("e:\\data\\science-parse\\out");
    List<File> inFiles = Arrays.asList(inDir.listFiles());
    HashSet<String> foundRefs = new HashSet<String>();
    HashSet<String> unfoundRefs = new HashSet<String>();

    ObjectMapper mapper = new ObjectMapper();
    int totalRefs = 0;
    int totalCites = 0;
    int blankAbstracts = 0;
    for (File f : inFiles) {
      if (!f.getName().endsWith(".pdf"))
        continue;
      val fis = new FileInputStream(f);
      ExtractedMetadata em = null;
      try {
        log.info(f.getName());
        em = p.doParse(fis, Parser.MAXHEADERWORDS);
        if(em.abstractText == null || em.abstractText.length() == 0) {
          log.info("abstract blank!");
          blankAbstracts++;
        }
        else {
          log.info("abstract: " + em.abstractText);
        }
        final List<BibRecord> br = em.references;
        for(BibRecord b : br)
          log.info(b.toString());
        final List<CitationRecord> cr = em.referenceMentions;
        if (br.size() > 3 && cr.size() > 3) {  //HACK: assume > 3 refs means valid ref list
          foundRefs.add(f.getAbsolutePath());
        } else {
          unfoundRefs.add(f.getAbsolutePath());
        }
        totalRefs += br.size();
        totalCites += cr.size();
        mapper.writeValue(
                new File(outDir, f.getName() + ".dat"), em);
        //Tuples.pair(em.references, em.referenceMentions));
      } catch (Exception e) {
        log.info("Parse error: " + f);
        e.printStackTrace();
      }
      fis.close();

    }

    //Object to JSON in file
    mapper.writeValue(new File(outDir, "unfoundReferences.dat"), unfoundRefs);
    mapper.writeValue(new File(outDir, "foundReferences.dat"), foundRefs);
    log.info("found 3+ refs and 3+ citations for " + foundRefs.size() + " papers.");
    log.info("failed to find that many for " + unfoundRefs.size() + " papers.");
    log.info("total references: " + totalRefs + "\ntotal citations: " + totalCites);
    log.info("blank abstracts: " + blankAbstracts);


  }

}
