package org.allenai.scienceparse;

import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

@Test
@Slf4j
public class CheckReferencesTest {

  public void smallTest() throws IOException {
    String jsonFile = Parser.getDefaultGazetteer().toString();
    CheckReferences cr = new CheckReferences(jsonFile);
    log.info("num hashes: " + cr.getHashSize());
    Assert.assertEquals(cr.getHashSize(), 13579);
    Assert.assertTrue(cr.hasPaper(
      "Text-based measures of document diversity",
      Arrays.asList("Kevin Bache",
        "David Newman",
        "Padhraic Smyth"), 2013, "KDD"));
    Assert.assertTrue(cr.hasPaper(
      "Text-based measures of document diversity",
      Arrays.asList("K. Bache",
        "D. Newman",
        "P. Smyth"), 2013, "KDD"));
    Assert.assertFalse(cr.hasPaper(
      "Fake paper titles: A case study in negative examples",
      Arrays.asList("Kevin Bache",
        "David Newman",
        "Padhraic Smyth"), 2013, "KDD"));
    Assert.assertFalse(cr.hasPaper(
      "Text-based measures of document diversity",
      Arrays.asList("Captain Bananas",
        "David Newman",
        "Padhraic Smyth"), 2013, "KDD"));

  }

}
