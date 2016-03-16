package org.allenai.scienceparse

import com.gs.collections.impl.tuple.primitive.PrimitiveTuples
import org.allenai.common.testkit.UnitSpec
import scala.collection.JavaConverters._

class TokenizationSpec extends UnitSpec {
  "PDFToCRFInput.findWordRanges" should "tokenize some strings correctly" in {
    val examples = Map(
      "foo" -> Seq((0, 3)),
      " foo" -> Seq((1, 4)),
      "foo " -> Seq((0, 3)),
      "foo bar" -> Seq((0, 3), (4, 7)),
      "André" -> Seq((0, 5)),
      "André," -> Seq((0, 5), (5, 6)),
      "..." -> Seq((0, 1), (1, 2), (2, 3)),
      "Peter, Paul, and Mary" -> Seq((0, 5), (5, 6), (7, 11), (11, 12), (13, 16), (17, 21)),
      "Yun∗" -> Seq((0, 3), (3, 4)),
      "Kim†" -> Seq((0, 3), (3, 4)),
      "(2005)" -> Seq((0, 1), (1, 5), (5, 6)),
      "Alfred E. Neuman" -> Seq((0, 6), (7, 9), (10, 16)),
      "Alfred E.Neuman" -> Seq((0, 6), (7, 9), (9, 15))
    )

    examples.foreach { case (s, expectedScala) =>
      val expectedJava = expectedScala.map {
        case (start, end) => PrimitiveTuples.pair(start, end)
      }.asJava
      val result = PDFToCRFInput.findWordRanges(s)
      assert(expectedJava === result)
    }
  }
}
