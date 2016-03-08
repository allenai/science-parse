package org.allenai.scienceparse.figureextraction

import org.allenai.common.testkit.UnitSpec

class FormattingTextExtractorSpec extends UnitSpec {

  val textBuilder = MockParagraphBuilder()

  "findHeaders" should "find headers" in {
    val headerY1 = 10
    val headerY2 = 20
    // Test one of the trickier cases, headers that have inconsistent text and that alternative
    // sides of the page
    val headers = Seq(
      textBuilder.buildParagraph(10, headerY1, 110, headerY2, "header1"),
      textBuilder.buildParagraph(100, headerY1, 200, headerY2, "header2"),
      textBuilder.buildParagraph(10, headerY1, 110, headerY2, "header3"),
      textBuilder.buildParagraph(100, headerY1, 200, headerY2, "header4"),
      textBuilder.buildParagraph(100, headerY1 + 1, 200, headerY2 + 10, "inconsistentHeightHeader")
    )

    val otherText = Seq(
      List(textBuilder.buildParagraph(10, headerY1 - 5, 20, 20, "other1")), // Above header1
      List(
        textBuilder.buildParagraph(10, 50, 20, 100, "other21"),
        textBuilder.buildParagraph(400, 350, 450, 500, "other22")
      ),
      List(textBuilder.buildParagraph(10, 100, 20, 200, "other3")),
      // Not above, but nearly level with header4
      List(textBuilder.buildParagraph(200, headerY1 - 1, 250, headerY2 - 3, "other4")),
      List()
    )

    val pages = headers.zip(otherText).zipWithIndex.map {
      case ((header, other), number) => PageWithText(number, (other :+ header).sorted)
    }

    def getHeaderText(paragraphs: Seq[Seq[Paragraph]]) =
      paragraphs.flatten.map(_.text)

    assertResult(Seq())(FormattingTextExtractor.findHeaders(pages, 4).flatten)
    assertResult(Seq())(FormattingTextExtractor.findHeaders(pages, 3).flatten)
    assertResult(Seq("header2", "header3"))(
      getHeaderText(FormattingTextExtractor.findHeaders(pages, 2))
    )

    // Try with secondary headers, this time with different heights but the same text
    val secondHeader1 = textBuilder.buildParagraph(10, 30, 200, 40, "secondHeader")
    val secondHeader2 = textBuilder.buildParagraph(50, 30, 100, 45, "secondHeader")
    val page2 = pages(1).copy(paragraphs = pages(1).paragraphs :+ secondHeader1)
    val page3 = pages(2).copy(paragraphs = pages(2).paragraphs :+ secondHeader2)
    val pagesWithSecondHeader = pages.updated(1, page2).updated(2, page3)
    assertResult(Seq())(FormattingTextExtractor.findHeaders(pagesWithSecondHeader, 3).flatten)
    assertResult(Seq("header2", "secondHeader", "header3", "secondHeader"))(
      getHeaderText(FormattingTextExtractor.findHeaders(pagesWithSecondHeader, 2))
    )
  }

  "findPageNumber" should "find pageNumbers" in {
    val (px1, py1, px2, py2) = (40, 600, 50, 610)
    val pageNumbers = Seq(
      textBuilder.buildParagraph(px1, py1, px2, py2, "53"),
      textBuilder.buildParagraph(px1 + 200, py1, px2 + 200, py2, "text"),
      textBuilder.buildParagraph(px1, py1, px2, py2, "84"),
      textBuilder.buildParagraph(px1 + 200, py1, px2 + 200, py2, "91")
    )

    val otherText = Seq(
      List(
        textBuilder.buildParagraph(10, 100, 20, 500, "other1"),
        textBuilder.buildParagraph(10, py1 + 5, 20, py2 + 5, "other1")
      ), // Includes text underneath page number 1
      List(textBuilder.buildParagraph(10, 50, 20, 100, "other2")),
      List(textBuilder.buildParagraph(10, 100, 20, 200, "other3")),
      List()
    )

    val pages = pageNumbers.zip(otherText).zipWithIndex.map {
      case ((pageNumber, other), number) => PageWithText(number, (other :+ pageNumber).sorted)
    }

    assertResult(Seq())(FormattingTextExtractor.findPageNumber(pages, 4).flatten)
    assertResult(Seq())(FormattingTextExtractor.findPageNumber(pages, 3).flatten)
    assertResult(Seq(None, None, Some("84"), Some("91"))) {
      FormattingTextExtractor.findPageNumber(pages, 2).map {
        case Some(line) => Some(line.text)
        case _ => None
      }
    }
  }
}
