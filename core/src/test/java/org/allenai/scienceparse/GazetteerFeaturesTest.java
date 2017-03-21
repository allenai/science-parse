package org.allenai.scienceparse;

import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import junit.framework.Assert;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class GazetteerFeaturesTest {

  public String filePathOfResource(String path) {
    return this.getClass().getResource(path).getFile();
  }
  
  public void testLength() {
    Assert.assertTrue(GazetteerFeatures.withinLength("this string is only six words."));
    Assert.assertFalse(GazetteerFeatures.withinLength("this string by contrast is eight words long."));
    
  }
  
  public void testGazetteers() throws Exception {
    GazetteerFeatures gf = new GazetteerFeatures(filePathOfResource("/gazetteer-test/"));
    
    int namesId = gf.gazetteerNumber("names.male.txt");
    int univId = gf.gazetteerNumber("education.university.small.txt");
    Assert.assertEquals(gf.size(), 2);
    Assert.assertEquals(3, gf.sizeOfSet(univId));
    Assert.assertEquals(5, gf.sizeOfSet(namesId));
    boolean [] abbeyInSet = gf.inSet("Abbey");
    Assert.assertEquals(2, abbeyInSet.length);
    Assert.assertFalse(abbeyInSet[univId]);
    Assert.assertTrue(abbeyInSet[namesId]);
    boolean [] beautyInSet = gf.inSet("marinello school of beauty");
    Assert.assertEquals(2, beautyInSet.length);
    Assert.assertTrue(beautyInSet[univId]);
    Assert.assertFalse(beautyInSet[namesId]);
    boolean [] wilkinsInSet = gf.inSet("d. wilkins school of windmill dunks");
    Assert.assertEquals(2, wilkinsInSet.length);
    Assert.assertFalse(wilkinsInSet[univId]);
    Assert.assertFalse(wilkinsInSet[namesId]);
    boolean [] apolloInSet = gf.inSet("Apollo College Phoenix Inc.");
    Assert.assertTrue(apolloInSet[univId]);
  }
  
  public void testGazetteerFeatures() throws Exception {
    List<String> elems = Arrays.asList("Abbey", "is", "at", "Apollo", "College", "Phoenix", "Inc.");
    ReferencesPredicateExtractor rpe = new ReferencesPredicateExtractor();
    GazetteerFeatures gf = new GazetteerFeatures(filePathOfResource("/gazetteer-test/"));
    val spns = gf.getSpans(elems);
    log.info(spns.toString());
    Assert.assertEquals(2, spns.size());
    
    rpe.setGf(gf);
    val preds = rpe.nodePredicates(elems);
    log.info(preds.toString());
    Assert.assertEquals(1.0, preds.get(0).get("%gaz_W_names.male.txt"));
    Assert.assertFalse(preds.get(2).containsKey("%gaz_B_education.university.small.txt"));
    Assert.assertEquals(1.0, preds.get(3).get("%gaz_B_education.university.small.txt"));
    Assert.assertEquals(1.0, preds.get(4).get("%gaz_I_education.university.small.txt"));
    Assert.assertEquals(1.0, preds.get(6).get("%gaz_E_education.university.small.txt"));
    
  }
}
