package org.allenai.scienceparse.figureextraction

import org.allenai.pdfbox.pdmodel.font.PDType1Font
import org.allenai.pdfbox.text.TextPosition
import org.allenai.pdfbox.util.Matrix

/** Build mock Paragraph/Line objects, text positions have Fonts but otherwise don't contain
  * real data, words always have the same bounding box as their line and a single text position.
  */
case class MockParagraphBuilder() {
  val lineSpacing = 5.0
  var lineNumber = 0

  def buildTextPosition(): TextPosition =
    new TextPosition(0, 0, 0, Matrix.getScaleInstance(1, 1),
      0, 0, 0, 0, 0, "", Array[Int](), PDType1Font.COURIER, 0, 0)

  def buildLine(str: String, x1: Double, y1: Double, x2: Double, y2: Double): Line = {
    val boundary = Box(x1, y1, x2, y2)
    val line =
      Line(
        str.split(" ").map(txt => Word(txt, boundary, List(buildTextPosition()))).toList,
        boundary, lineNumber
      )
    lineNumber += 1
    line
  }

  def buildParagraph(text: String*): Paragraph = {
    val lines = text.map(lineText => buildLine(lineText, 0, 0, 0, 0))
    Paragraph(lines.toList, null)
  }

  def buildParagraph(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    text: String*
  ): Paragraph = {
    val heightPerLine = (y2 - y1) - (text.size - 1) * lineSpacing
    require(heightPerLine > 0)
    var onY = y1
    val lines = text.map { lineText =>
      val line = buildLine(lineText, x1, onY, x2, onY + heightPerLine)
      onY += heightPerLine + lineSpacing
      line
    }.toList
    Paragraph(lines, Box(x1, y1, x2, y2))
  }
}
