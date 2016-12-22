package org.allenai.scienceparse;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.testng.annotations.Test;

import com.gs.collections.api.tuple.Pair;

import junit.framework.Assert;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class CRFBibRecordParserTest {
  
  public void testReadData() throws IOException {
    File coraFile = new File(this.getClass().getResource("/coratest.txt").getFile());
    val labels = CRFBibRecordParser.labelFromCoraFile(coraFile);
    log.info(labels.toString());
    Assert.assertEquals(1, labels.size());
    boolean foundOne = false;
    boolean foundTwo = false;
    boolean foundThree = false;
    for(Pair<String, String> p : labels.get(0)) {
      if(p.getOne().equals("Formalising") && p.getTwo().equals("B_T"))
        foundOne = true;
      if(p.getOne().equals("formalism.") && p.getTwo().equals("E_T"))
        foundTwo = true;
      if(p.getOne().equals("1992.") && p.getTwo().equals("W_Y"))
        foundThree = true;
    }
    Assert.assertTrue(foundOne);
    Assert.assertTrue(foundTwo);
    Assert.assertTrue(foundThree);
    
    File umassFile = new File(this.getClass().getResource("/umasstest.txt").getFile());
    val labels2 = CRFBibRecordParser.labelFromUMassFile(umassFile);
    log.info(labels2.toString());
    Assert.assertEquals(1, labels2.size());
    foundOne = false;
    foundTwo = false;
    for(Pair<String, String> p : labels2.get(0)) {
      if(p.getOne().equals("E.") && p.getTwo().equals("B_A"))
        foundOne = true;
      if(p.getOne().equals("1979") && p.getTwo().equals("B_Y"))
        foundTwo = true;
    }
    Assert.assertTrue(foundOne);
    Assert.assertTrue(foundTwo);
    
    File kermitFile = new File(this.getClass().getResource("/kermittest.txt").getFile());
    val labels3 = CRFBibRecordParser.labelFromKermitFile(kermitFile);
    log.info(labels3.toString());
    Assert.assertEquals(2, labels3.size());
    foundOne = false;
    foundTwo = false;
    for(Pair<String, String> p : labels3.get(1)) {
      if(p.getOne().equals("Hinshaw,") && p.getTwo().equals("B_A"))
        foundOne = true;
      if(p.getOne().equals("Shock") && p.getTwo().equals("E_V"))
        foundTwo = true;
    }
    Assert.assertTrue(foundOne);
    Assert.assertTrue(foundTwo);
    
  }
  
  public void testCoraLabeling() throws Exception {
    String s = "<author> A. Cau </author> <title> Formalising Dijkstra's development strategy within Stark's formalism. </title> <booktitle> BCS-FACS Refinement Workshop, </booktitle> <date> 1992. </date>";
    int tokens = 2 + 21  - 8; //start/stop plus tokens in source minus eight tags. 
    List<Pair<String, String>> labeledData = CRFBibRecordParser.getLabeledLineCora(s);
    Assert.assertEquals(tokens, labeledData.size());
    Assert.assertEquals("Cau", labeledData.get(2).getOne());
    Assert.assertEquals("E_A", labeledData.get(2).getTwo());
    Assert.assertEquals("Formalising", labeledData.get(3).getOne());
    Assert.assertEquals("B_T", labeledData.get(3).getTwo());
    Assert.assertEquals("development", labeledData.get(5).getOne());
    Assert.assertEquals("I_T", labeledData.get(5).getTwo());
    Assert.assertEquals("1992.", labeledData.get(13).getOne());
    Assert.assertEquals("W_Y", labeledData.get(13).getTwo());
  }
  
}
