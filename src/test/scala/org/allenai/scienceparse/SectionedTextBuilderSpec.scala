package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec
import org.allenai.scienceparse.SectionedTextBuilder.DocumentSection

class SectionedTextBuilderSpec extends UnitSpec {

  val textBuilder = MockParagraphBuilder()
  val testParagraphs = List(
    textBuilder.buildParagraph("line1"),
    textBuilder.buildParagraph("header1"),
    textBuilder.buildParagraph("line2", "line3"),
    textBuilder.buildParagraph("line4"),
    textBuilder.buildParagraph("header2"),
    textBuilder.buildParagraph("header3"),
    textBuilder.buildParagraph("line5", "line6"),
    textBuilder.buildParagraph("line7", "line8", "line9"),
    textBuilder.buildParagraph("header4")
  )
  val (titles, rest) = testParagraphs.partition(_.text.startsWith("header"))

  def checkAllSections(sections: Seq[DocumentSection]): Unit = {
    assertResult("line1")(sections(0).paragraphs.mkString(" "))
    assertResult("header1")(sections(1).title.get.text)
    assertResult("line2 line3 line4")(sections(1).paragraphs.mkString(" "))
    assertResult("header2")(sections(2).title.get.text)
    assertResult("")(sections(2).paragraphs.mkString(" "))
    assertResult("header3")(sections(3).title.get.text)
    assertResult("line5 line6 line7 line8 line9")(sections(3).paragraphs.mkString(" "))
    assertResult("header4")(sections(4).title.get.text)
    assertResult("")(sections(4).paragraphs.mkString(" "))
  }

  def buildPage(paragraphs: List[Paragraph], titles: List[Paragraph],
    num: Int = 0): ClassifiedPage = {
    PageWithClassifiedText(num, paragraphs, ClassifiedText(sectionTitles = titles))
  }

  "mergeInSections" should "work with trailing text" in {
    val sections = SectionedTextBuilder.buildSectionedText(
      List(buildPage(
        rest.take(2) ++ rest.lastOption, // line1-3 and
        titles.take(1) ++ titles.slice(2, 3)
      )) // Headers 1 and 3
    )
    assert(sections.forall(_.paragraphs.forall(_.page == 0)))
    assert(sections.forall(s => s.title.isEmpty || s.title.get.page == 0))
    assertResult("header1")(sections(1).title.get.text)
    assertResult("line2 line3")(sections(1).paragraphs.mkString(" "))
    assertResult("header3")(sections(2).title.get.text)
    assertResult("line7 line8 line9")(sections(2).paragraphs.mkString(" "))
  }

  it should "work on split up paragraph" in {
    val sections = SectionedTextBuilder.buildSectionedText(List(buildPage(rest, titles)))
    assert(sections.forall(_.paragraphs.forall(_.page == 0)))
    assert(sections.forall(s => s.title.isEmpty || s.title.get.page == 0))
    checkAllSections(sections)
  }

  it should "work on multiple page input" in {
    val page1 = buildPage(rest.take(2), titles.take(1), 0) // ends with line3
    val page2 = buildPage(rest.slice(2, 3), List(), 1) // ends with line4
    val page3 = buildPage(rest.drop(3), titles.drop(1), 2)
    val sections = SectionedTextBuilder.buildSectionedText(List(page1, page2, page3))
    checkAllSections(sections)
    assert(sections(0).paragraphs.forall(_.page == 0))
    assertResult(0)(sections(1).title.get.page)
    assertResult(0)(sections(1).paragraphs.head.page)
    assertResult(1)(sections(1).paragraphs.last.page)
    assert(sections.drop(2).forall(sections =>
      sections.paragraphs.forall(_.page == 2) && sections.title.get.page == 2))
  }
}
