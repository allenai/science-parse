package org.allenai.scienceparse

import org.allenai.pdfbox.cos.COSBase
import org.allenai.pdfbox.pdmodel.PDDocument
import org.allenai.pdfbox.rendering.{ PageDrawer, PageDrawerParameters, PDFRenderer }
import org.allenai.pdfbox.contentstream.operator.Operator

class InterruptiblePDFRenderer(doc: PDDocument) extends PDFRenderer(doc) {

  class InterruptiblePageDrawer(param: PageDrawerParameters) extends PageDrawer(param) {
    override def processOperator(operator: Operator, operands: java.util.List[COSBase]): Unit = {
      if (Thread.interrupted()) throw new InterruptedException()
      super.processOperator(operator, operands)
    }
  }

  override def createPageDrawer(parameters: PageDrawerParameters): PageDrawer = {
    new InterruptiblePageDrawer(parameters)
  }
}
