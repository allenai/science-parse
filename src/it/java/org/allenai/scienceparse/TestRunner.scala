package org.allenai.scienceparse

import org.scalatest.testng.TestNGWrapperSuite

class TestRunner extends TestNGWrapperSuite(
  List("src/it/resources/testng.xml")
)

