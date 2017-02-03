package org.allenai.scienceparse

import java.io._
import java.util.NoSuchElementException
import com.amazonaws.services.cloudfront.model.InvalidArgumentException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.allenai.common.{ Logging, Resource }
import scopt.OptionParser
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import language.postfixOps
import scala.util.control.NonFatal
import org.allenai.common.ParIterator._

object RunSP extends Logging {
  case class MetadataWrapper(name: String, metadata: ExtractedMetadata)

  val objectMapper = new ObjectMapper() with ScalaObjectMapper
  objectMapper.registerModule(DefaultScalaModule)

  def printResults(name: String, outputDir: File, metadata: ExtractedMetadata): Unit = {
    val wrapper = MetadataWrapper(name, metadata)

    Resource.using(new FileOutputStream(new File(outputDir, name + ".json"))) { os =>
      objectMapper.writeValue(os, wrapper)
    }
  }

  def main(args: Array[String]) = {
    case class Config(
      modelFile: Option[File] = None,
      bibModelFile: Option[File] = None,
      gazetteerFile: Option[File] = None,
      paperDirectory: Option[File] = None,
      pdfInputs: Seq[String] = Seq(),
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

      opt[File]('p', "paperDirectory") action { (p, c) =>
        c.copy(paperDirectory = Some(p))
      } text "Specifies a directory with papers in them. If this is not specified, or a paper can't be found in the directory, we fall back to getting the paper from the bucket."

      arg[String]("<pdf|directory|sha|textfile>...") unbounded () action {
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

      val paperSource = {
        val bucketSource = new RetryPaperSource(ScholarBucketPaperSource.getInstance())
        config.paperDirectory match {
          case None => bucketSource
          case Some(dir) =>
            new FallbackPaperSource(
              new DirectoryPaperSource(dir),
              bucketSource
            )
        }
      }

      val shaRegex = "^[0-9a-f]{40}$"r
      def stringToInputStreams(s: String): Iterator[(String, InputStream)] = {
        val file = new File(s)

        if(s.endsWith(".pdf")) {
          Iterator((s, new FileInputStream(file)))
        } else if(s.endsWith(".txt")) {
          val lines = new Iterator[String] {
            private val input = new BufferedReader(
              new InputStreamReader(
                new FileInputStream(file),
                "UTF-8"))
            def getNextLine: String = {
              val result = input.readLine()
              if (result == null)
                input.close()
              result
            }
            private var nextLine = getNextLine

            override def hasNext: Boolean = nextLine != null
            override def next(): String = {
              val result = nextLine
              nextLine = if(nextLine == null) null else getNextLine
              if(result == null)
                throw new NoSuchElementException
              else
                result
            }
          }
          lines.flatMap(stringToInputStreams)
        } else if(file.isDirectory) {
          def listFiles(startFile: File): Iterator[File] =
            startFile.listFiles.iterator.flatMap {
              case dir if dir.isDirectory => listFiles(dir)
              case f if f.isFile && f.getName.endsWith(".pdf") => Iterator(f)
              case _ => Iterator.empty
            }
          listFiles(file).map(f => (f.getName, new FileInputStream(f)))
        } else if(shaRegex.findFirstIn(s).isDefined) {
          try {
            Iterator((s, paperSource.getPdf(s)))
          } catch {
            case NonFatal(e) =>
              logger.info(s"Locating $s failed with ${e.toString}. Ignoring.")
              Iterator.empty
          }
        } else {
          logger.warn(s"Input $s is not something I understand. I'm ignoring it.")
          Iterator.empty
        }
      }

      val files = config.pdfInputs.iterator.flatMap(stringToInputStreams)

      files.parForeach { case (name, is) =>
        val parser = Await.result(parserFuture, 15 minutes)

        logger.info(s"Starting $name")
        try {
          val metadata = parser.doParseWithTimeout(is, 60000)
          printResults(name, config.outputDir.get, metadata)
        } catch {
          case NonFatal(e) =>
            logger.info(s"Parsing $name failed with ${e.toString}")
        }
        logger.info(s"Finished $name")
      }
    }
  }
}
