package org.allenai.scienceparse

import org.scalatest.testng.TestNGWrapperSuite

class JavaTestSuite extends TestNGWrapperSuite(
  List("src/test/resources/testng.xml")
)

