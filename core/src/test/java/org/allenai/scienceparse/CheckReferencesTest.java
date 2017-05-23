package org.allenai.scienceparse;

import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;
import org.allenai.datastore.Datastore;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

@Test
@Slf4j
public class CheckReferencesTest {
  public void smallTest() throws IOException {
    final String jsonFile =
            Datastore.apply().filePath("org.allenai.scienceparse", "gazetteer.json", 5).toString();
    CheckReferences cr = new CheckReferences(jsonFile);
    log.info("num hashes: " + cr.getHashSize());
    Assert.assertEquals(1557178, cr.getHashSize());
    Assert.assertTrue(cr.hasPaper(
      "Ecological Sampling of Gaze Shifts",
      Arrays.asList("Giuseppe Boccignone",
        "Mario Ferraro"), 2014, "KDD"));
    Assert.assertTrue(cr.hasPaper(
      "HIST: A Methodology for the Automatic Insertion of a Hierarchical Self Test",
      Arrays.asList("Oliver F. Haberl",
        "Thomas Kropf"), 1992, "KDD"));
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
