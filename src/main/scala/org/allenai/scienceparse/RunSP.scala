package org.allenai.scienceparse

import java.io.{File, FileInputStream, FileOutputStream, StringWriter}

import com.fasterxml.jackson.core
import com.fasterxml.jackson.databind.{ObjectMapper, ObjectReader}
import scopt.OptionParser

object RunSP {
  def printResults(f: File, outputDir: File, metadata: ExtractedMetadata) = {
    val om = new ObjectMapper()
    val sw = new StringWriter()
    val wrapper = new MetadataWrapper()
    wrapper.filename = f.getAbsolutePath
    wrapper.metadata = metadata
    val fOs = new FileOutputStream(new File(outputDir, f.getName))
    om.writeValue(fOs, wrapper)
    fOs.flush()
    fOs.close()
  }

  def main(args: Array[String]) = {
    case class Config(
        modelFile: Option[File] = None,
        gazetteerFile: Option[File] = None,
        pdfInputs: Seq[File] = Seq(),
        outputDir: Option[File] = None
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      opt[File]('m', "model") action { (m, c) =>
        c.copy(modelFile = Some(m))
      } text "Specifies the model file to evaluate. Defaults to the production model"

      opt[File]('g', "gazetteer") action { (g, c) =>
        c.copy(gazetteerFile = Some(g))
      } text "Specifies the gazetteer file. Defaults to the production one. Take care not to use a gazetteer that you also used to train the model."

      opt[Seq[File]]('p', "pdfs") action { (p, c) =>
        c.copy(pdfInputs = p)
      } text "PDF files to process."

      opt[File]('o', "outputDirectory") action { (o, c) => c.copy(outputDir = Some(o)) } text "Output directory."

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val modelFile = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      val gazetteerFile = config.gazetteerFile.map(_.toPath).getOrElse(Parser.getDefaultGazetteer)

      val parser = new Parser(modelFile, gazetteerFile)
      for {
        f <- config.pdfInputs
        fIs = new FileInputStream(f)
        metadata = parser.doParse(fIs)
      } printResults(f, config.outputDir.get, metadata)
    }
  }
}