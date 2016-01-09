package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class CaptionBuilderSpec extends UnitSpec {

  val builder = MockParagraphBuilder()
  "build caption" should "work on basic captions" in {

    val captionLine = builder.buildLine("Figure 1: Blah Blah-", 100, 100, 200, 110)
    val captionStart = CaptionStart("", "", FigureType.Figure, "",
      captionLine, None, 0, false, false)
    val secondLine = builder.buildLine("Line 2", 100, 120, 200, 130)

    // Runs `CaptionBuilder.buildCaption` and returns the line in the resulting Caption
    def buildCaption(lines: List[Line], spacing: Double, graphics: Seq[Box] = Seq()): List[Line] = {
      CaptionBuilder.buildCaption(captionStart, Seq(),
        Seq(Paragraph(lines)), graphics, spacing).paragraph.lines
    }

    // Second line is close enough to use
    assertResult(List(captionLine, secondLine))(
      buildCaption(List(captionLine, secondLine), 15.0)
    )

    // Second line is too far away
    assertResult(List(captionLine))(
      buildCaption(List(captionLine, secondLine), 5.0)
    )

    // Adding the second line cause us to intersect a graphic region
    assertResult(List(captionLine))(
      buildCaption(List(captionLine, secondLine), 15.0, Seq(Box(100, 115, 150, 130)))
    )

    // Both caption and the second line intersect a graphic region, so we should add the second line
    assertResult(List(captionLine, secondLine))(
      buildCaption(List(captionLine, secondLine), 15.0, Seq(Box(80, 100, 190, 200)))
    )

    // Line continues the second line
    val firstLineContinued = builder.buildLine("Line 2 cont", 205, 100, 230, 110)
    assertResult(List(captionLine, firstLineContinued))(
      buildCaption(List(captionLine, firstLineContinued), 8.0)
    )

    // Third line breaks justification, so don't add fourth line
    val thirdLine = builder.buildLine("Line 3", 100, 140, 150, 150)
    val fourthLine = builder.buildLine("Line 4", 100, 160, 200, 170)
    assertResult(List(captionLine, secondLine, thirdLine))(
      buildCaption(List(captionLine, secondLine, thirdLine, fourthLine), 8.0)
    )

    // Now we have enough spacing tolerance to add the fourth line anyway
    assertResult(List(captionLine, secondLine, thirdLine, fourthLine))(
      buildCaption(List(captionLine, secondLine, thirdLine, fourthLine), 11.0)
    )

  }
}
