package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class StringUtilsSpec extends UnitSpec {
  "author names" should "get split correctly" in {
    val tests = Map(
      "Aryabhata" -> Tuple2("", "Aryabhata"),
      "Peter Clark" -> Tuple2("Peter", "Clark"),
      "Peter  Clark" -> Tuple2("Peter", " Clark"),
      "Arthur C. Clarke" -> Tuple2("Arthur C.", "Clarke"),
      "Ludwig van Beethoven" -> Tuple2("Ludwig", "van Beethoven"),
      "Ludwig  van  Beethoven" -> Tuple2("Ludwig", " van  Beethoven"),
      " Ludwig  van  Beethoven" -> Tuple2(" Ludwig", " van  Beethoven"),
      "Ludwig  van  Beethoven Jr." -> Tuple2("Ludwig", " van  Beethoven Jr."),
      "Ludwig  van  Beethoven Jr.   " -> Tuple2("Ludwig", " van  Beethoven Jr.   "),
      "Ayrton Senna da Silva" -> Tuple2("Ayrton Senna", "da Silva"),
      "" -> Tuple2("", ""),
      "   " -> Tuple2("", "   ")
    )

    tests.foreach { case (original, expected) =>
      assertResult(expected)(StringUtils.splitName(original))
    }
  }
}
