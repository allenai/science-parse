package org.allenai.scienceparse

import java.awt.Desktop
import java.io.{PrintWriter, File}

import org.allenai.common.Resource
import org.allenai.scienceparse.pdfapi.{PDFFontMetrics, PDFExtractor}
import org.apache.commons.lang3.StringEscapeUtils
import scopt.OptionParser
import scala.collection.JavaConverters._

object PrintCRFInput extends App {
  case class Config(
    paperDir: Option[File] = None,
    paperId: String = null
  )

  val parser = new OptionParser[Config](this.getClass.getSimpleName) {
    opt[File]('d', "paperDir") action { (d, c) =>
      c.copy(paperDir = Some(d))
    } text "The directory that contains the papers"

    arg[String]("<paperId>") required() action { (p, c) =>
      c.copy(paperId = p)
    } text "The ID of the paper whose CRF input you want to see"
  }

  parser.parse(args, Config()).foreach { config =>
    val paperSource = config.paperDir.map(new DirectoryPaperSource(_)).getOrElse {
      new RetryPaperSource(ScholarBucketPaperSource.getInstance(), 5)
    }

    val seq = Resource.using(paperSource.getPdf(config.paperId)) { is =>
      val ext = new PDFExtractor
      val doc = ext.extractFromInputStream(is)
      PDFToCRFInput.getSequence(doc, true).asScala
    }

    // make a font-to-color map
    def font2style(fm: PDFFontMetrics) = f"font${fm.hashCode()}%x"
    val fonts = seq.map(_.getPdfToken.fontMetrics).toSet.map(font2style)
    val colors = Stream.from(1).
      map { n => (n * 0.61803398875 * 360).round % 360 }.
      map { hue => s"hsl($hue, 90%%, 85%%)" }
    val font2color = (fonts zip colors).toMap

    val tempFile = File.createTempFile(s"CRFInput-${config.paperId}.", ".html")
    tempFile.deleteOnExit()
    try {
      Resource.using(new PrintWriter(tempFile, "UTF-8")) { out =>
        out.println("<html>")
        out.println("<head>")
        out.println(s"<title>CRF input for ${config.paperId}</title>")
        out.println("<style type=\"text/css\">")
        font2color.foreach { case (style, color) =>
          out.println(s".$style { background-color: $color; }")
        }
        out.println("</style>")
        out.println("</head>")
        out.println("<body>")
        var line = 0
        var page = 0
        seq.foreach { token =>
          if(token.getPage != page) {
            out.println("<hr>")
            line = 0
            page = token.getPage
          } else if(token.getLine != line) {
            out.println("<br>")
            line = token.getLine
          }

          val style = font2style(token.getPdfToken.fontMetrics)
          val escaped = StringEscapeUtils.escapeHtml4(token.getPdfToken.token)
          out.println(s"<span class=$style>$escaped</span>")
        }
        out.println("</body>")
        out.println("</html>")
      }

      Desktop.getDesktop.browse(tempFile.toURI)
      Thread.sleep(5000)
    } finally {
      tempFile.delete()
    }
  }
}
