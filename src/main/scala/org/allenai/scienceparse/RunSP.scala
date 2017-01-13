package org.allenai.scienceparse

import java.io.{ File, FileInputStream, FileOutputStream }
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.allenai.common.{ Logging, Resource }
import scopt.OptionParser
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import language.postfixOps

object RunSP extends Logging {
  case class MetadataWrapper(filename: String, metadata: ExtractedMetadata)

  val objectMapper = new ObjectMapper() with ScalaObjectMapper
  objectMapper.registerModule(DefaultScalaModule)

  def printResults(f: File, outputDir: File, metadata: ExtractedMetadata): Unit = {
    val wrapper = MetadataWrapper(f.getAbsolutePath, metadata)

    Resource.using(new FileOutputStream(new File(outputDir, f.getName + ".json"))) { os =>
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

      opt[File]('o', "outputDirectory") required () action {
        (o, c) => c.copy(outputDir = Some(o))
      } text "Output directory"

      arg[File]("<pdf>...") unbounded () action {
        (f, c) => c.copy(pdfInputs = c.pdfInputs :+ f)
      } text "PDFs you'd like to process"

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val modelFile = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      val bibModelFile = config.bibModelFile.map(_.toPath).getOrElse(Parser.getDefaultBibModel)
      val gazetteerFile = config.gazetteerFile.map(_.toPath).getOrElse(Parser.getDefaultGazetteer)

      val parserFuture = Future {
        new Parser(modelFile, gazetteerFile, bibModelFile)
      }

      val files = config.pdfInputs.par.flatMap { f =>
        if (f.isFile) {
          Seq(f)
        } else if (f.isDirectory) {
          def listFiles(startFile: File): Seq[File] =
            startFile.listFiles.flatMap {
              case dir if dir.isDirectory => listFiles(dir)
              case file if file.isFile && file.getName.endsWith(".pdf") => Seq(file)
              case _ => Seq.empty
            }
          listFiles(f)
        } else {
          logger.warn(s"Input $f is neither file nor directory. I'm ignoring it.")
          Seq.empty
        }
      }

      val parser = Await.result(parserFuture, 15 minutes)

      files.foreach { file =>
        logger.info(s"Processing $file")
        val start = System.currentTimeMillis()
        Resource.using(new FileInputStream(file)) { is =>
          val metadata = parser.doParse(is)
          printResults(file, config.outputDir.get, metadata)
        }
        val end = System.currentTimeMillis()
        val elapsed = (end - start) / 1000
        if (elapsed > 0)
          logger.info(s"Finished processing $file in $elapsed seconds")
        else
          logger.info(s"Finished processing $file")
      }
    }
  }
}
