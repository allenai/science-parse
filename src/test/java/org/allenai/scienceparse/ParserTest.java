package org.allenai.scienceparse;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

@Test
public class ParserTest {

  public String filePathOfResource(String path) {
    return this.getClass().getResource(path).getFile();
  }
	
  public void testMath() throws Exception {
    assertEquals(2 + 2, 4);
  }
}