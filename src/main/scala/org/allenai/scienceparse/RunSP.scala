package org.allenai.scienceparse

import java.io.{File, FileInputStream, FileOutputStream, StringWriter}

import com.fasterxml.jackson.databind.ObjectMapper
import org.allenai.common.Resource
import scopt.OptionParser

object RunSP {
  private val objectMapper = new ObjectMapper()

  def printResults(f: File, outputDir: File, metadata: ExtractedMetadata): Unit = {
    case class MetadataWrapper(filename: String, metadata: ExtractedMetadata)
    val wrapper = MetadataWrapper(f.getAbsolutePath, metadata)
    Resource.using(new FileOutputStream(new File(outputDir, f.getName))) { os =>
      objectMapper.writeValue(os, wrapper)
    }
  }

  def main(args: Array[String]) = {
    case class Config(
      modelFile: Option[File] = None,
      bibModelFile: Option[File] = None,
      gazetteerFile: Option[File] = None,
      pdfInputs: Seq[File] = Seq(),
      outputDir: Option[File] = None
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      opt[File]('m', "model") action { (m, c) =>
        c.copy(modelFile = Some(m))
      } text "Specifies the model file to evaluate. Defaults to the production model"

      opt[File]('b', "bibModel") action { (m, c) =>
        c.copy(bibModelFile = Some(m))
      } text "Specifies the model for bibliography parsing. Defaults to the production model"

      opt[File]('g', "gazetteer") action { (g, c) =>
        c.copy(gazetteerFile = Some(g))
      } text "Specifies the gazetteer file. Defaults to the production one. Take care not to use a gazetteer that you also used to train the model."

      opt[File]('o', "outputDirectory") required() action {
        (o, c) => c.copy(outputDir = Some(o))
      } text "Output directory."

      arg[File]("<pdf>...") unbounded() action {
        (f, c) => c.copy(pdfInputs = c.pdfInputs :+ f)
      } text "PDFs you'd like to process"

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val modelFile = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      val bibModelFile = config.bibModelFile.map(_.toPath).getOrElse(Parser.getDefaultBibModel)
      val gazetteerFile = config.gazetteerFile.map(_.toPath).getOrElse(Parser.getDefaultGazetteer)

      val parser = new Parser(modelFile, gazetteerFile, bibModelFile)
      config.pdfInputs.foreach { f =>
        Resource.using(new FileInputStream(f)) { is =>
          val metadata = parser.doParse(is)
          printResults(f, config.outputDir.get, metadata)
        }
      }
    }
  }
}
