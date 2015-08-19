package org.allenai.scienceparse;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

@Test
public class ParserTest {

  public void testMath() throws Exception {
    assertEquals(2 + 2 == 4, "Math is broken!");
  }
}