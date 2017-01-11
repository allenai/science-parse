package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class StringUtilsSpec extends UnitSpec {
  "author names" should "get split correctly" in {
    val tests = Map(
      "Aryabhata" -> ("", "Aryabhata"),
      "Peter Clark" -> ("Peter", "Clark"),
      "Peter  Clark" -> ("Peter", " Clark"),
      "Arthur C. Clarke" -> ("Arthur C.", "Clarke"),
      "Ludwig van Beethoven" -> ("Ludwig", "van Beethoven"),
      "Ludwig  van  Beethoven" -> ("Ludwig", " van  Beethoven"),
      " Ludwig  van  Beethoven" -> (" Ludwig", " van  Beethoven"),
      "Ludwig  van  Beethoven Jr." -> ("Ludwig", " van  Beethoven Jr."),
      "Ludwig  van  Beethoven Jr.   " -> ("Ludwig", " van  Beethoven Jr.   "),
      "Ayrton Senna da Silva" -> ("Ayrton Senna", "da Silva"),
      "" -> ("", ""),
      "   " -> ("", "   ")
    )

    tests.foreach { case (original, expected) =>
      assertResult(expected)(StringUtils.splitName(original))
    }
  }
}
