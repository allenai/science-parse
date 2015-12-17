package org.allenai.scienceparse

import org.allenai.common.{ EnumCompanion, Enum }

sealed abstract class FigureType(id: String) extends Enum[FigureType](id)
object FigureType extends EnumCompanion[FigureType] {
  case object Table extends FigureType("Table")
  case object Figure extends FigureType("Figure")
  register(Figure, Table)
}

case class CaptionParagraph(name: String, figType: FigureType, page: Int, paragraph: Paragraph) {
  def boundary: Box = paragraph.boundary
  def startLineNumber: Int = paragraph.startLineNumber
  def text: String = Paragraph.convertToNormalizedString(paragraph)
}

object Caption {
  def apply(captionParagraph: CaptionParagraph): Caption = {
    Caption(
      captionParagraph.name,
      captionParagraph.figType,
      captionParagraph.page,
      captionParagraph.text,
      captionParagraph.boundary
    )
  }
}
case class Caption(name: String, figType: FigureType, page: Int, text: String, boundary: Box)

case class Figure(name: String, figType: FigureType, page: Int,
  caption: String, imageText: Seq[String], captionBoundary: Box, regionBoundary: Box)

case class FiguresInDocument(figures: Seq[Figure], failedCaptions: Seq[Caption])
