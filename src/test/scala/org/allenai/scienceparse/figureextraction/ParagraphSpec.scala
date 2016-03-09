package org.allenai.scienceparse.figureextraction

import org.allenai.common.testkit.UnitSpec

class ParagraphSpec extends UnitSpec {

  val textBuilder = MockParagraphBuilder()

  def getText(paragraph: Seq[Paragraph]) = paragraph.map(_.lines.map(_.text))

  "removeSegments" should "work" in {

    def ts(start: Int, end: Int) = TextSpan(start, end)

    val testParagraphs = Seq(
      textBuilder.buildParagraph("line0", "line1", "line2"),
      textBuilder.buildParagraph("line3"),
      textBuilder.buildParagraph("line4", "line5"),
      textBuilder.buildParagraph("line6", "line7", "line8")
    )
    val txt = getText(testParagraphs)

    {
      val cleaned = Paragraph.removeSpans(Seq(ts(0, 0)), testParagraphs)
      assertResult(txt.updated(0, txt(0).drop(1)))(getText(cleaned))
    }
    {
      val cleaned = Paragraph.removeSpans(Seq(ts(1, 1)), testParagraphs)
      assertResult(Seq(txt.head.take(1), txt.head.drop(2)) ++ txt.tail)(getText(cleaned))
    }
    {
      val cleaned = Paragraph.removeSpans(Seq(ts(1, 1), ts(2, 2)), testParagraphs)
      assertResult(txt.updated(0, txt(0).take(1)))(getText(cleaned))
    }
    {
      val cleaned = Paragraph.removeSpans(Seq(ts(3, 3), ts(4, 5)), testParagraphs)
      assertResult(txt.take(1) ++ txt.drop(3))(getText(cleaned))
    }
    {
      val cleaned = Paragraph.removeSpans(Seq(
        ts(0, 0), ts(2, 4), ts(7, 7)
      ), testParagraphs)
      assertResult(Seq("line1", "line5", "line6", "line8").map(Seq(_)))(getText(cleaned))
    }
  }

  "convertToString" should "remove hyphens" in {
    assertResult("w1 w2 w3 w4")(Paragraph.convertToNormalizedString(
      textBuilder.buildParagraph("w1", "w2 w3", "w4")
    ))
    assertResult("w1 word w2 w3")(Paragraph.convertToNormalizedString(
      textBuilder.buildParagraph("w1 w-", "ord", "w2 w3")
    ))
    assertResult("minus symbol - and also this")(Paragraph.convertToNormalizedString(
      textBuilder.buildParagraph("minus symbol -", "and al-", "so this")
    ))
  }
}
