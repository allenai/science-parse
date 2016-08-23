package org.allenai.scienceparse.figureextraction

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.{ PDFRenderer, PageDrawer, PageDrawerParameters }

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
