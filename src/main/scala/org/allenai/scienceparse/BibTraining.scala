package org.allenai.scienceparse

import java.io.{ File, FileInputStream }

import com.gs.collections.impl.set.mutable.UnifiedSet
import org.allenai.common.{ Logging, Resource }
import org.allenai.datastore.Datastores
import org.allenai.scienceparse.Parser.ParseOpts
import scopt.OptionParser

import scala.collection.JavaConverters._
import scala.io.Source

object BibTraining extends App with Datastores with Logging {
  // The Files are all Option[File] defaulting to None. Properly, they should be set to the
  // defaults from the datastore, but if we do that here, they will download several gigabytes
  // of files during startup, even if they are unused later.
  case class Config(
    output: File = null,
    groundTruth: Option[File] = None,
    maxIterations: Int = 150,
    backgroundSampleDocs: Int = 4000,
    backgroundDirectory: Option[File] = None,
    gazetteerFile: Option[File] = None,
    trainFraction: Double = 0.9,
    minExpectedFeatureCount: Int = 1
  )

  val parser = new OptionParser[Config](this.getClass.getSimpleName) {
    head("Options that are not specified default to the settings that were used to make the production model.")

    opt[File]('o', "output") required () action { (o, c) =>
      c.copy(output = o)
    } text "The output file"

    opt[File]('t', "groundTruth") action { (t, c) =>
      c.copy(groundTruth = Some(t))
    } text "The ground truth file"

    opt[Int]("maxIterations") action { (i, c) =>
      c.copy(maxIterations = i)
    } text "Maximum number of iterations during training"

    opt[Int]("backgroundSampleDocs") action { (d, c) =>
      c.copy(backgroundSampleDocs = d)
    } text "The number of documents to use to build the background language model"

    opt[File]("backgroundDirectory") action { (d, c) =>
      c.copy(backgroundDirectory = Some(d))
    } text "The directory in which the background documents are found"

    opt[File]('g', "gazetteerFile") action { (f, c) =>
      c.copy(gazetteerFile = Some(f))
    } text "The gazetteer file"

    opt[Double]("trainFraction") action { (f, c) =>
      c.copy(trainFraction = f)
    } text "The fraction of the ground truth to use for training"

    opt[Int]("minExpectedFeatureCount") action { (n, c) =>
      c.copy(minExpectedFeatureCount = n)
    } text "The minimum number of times we should see a feature before accepting it."

    help("help") text "Prints help text"
  }

  parser.parse(args, Config()).foreach { config =>
    val groundTruthFile =
      config.groundTruth.getOrElse(publicFile("productionBibGroundTruth.txt", 1).toFile)

    val opts = new ParseOpts
    opts.modelFile = config.output.toString
    opts.iterations = config.maxIterations
    opts.threads = Runtime.getRuntime.availableProcessors() * 2
    opts.backgroundSamples = config.backgroundSampleDocs

    val backgroundDirectory =
      config.backgroundDirectory.getOrElse(publicDirectory("productionBackgroundDocs", 1).toFile)
    opts.backgroundDirectory = backgroundDirectory.toString

    val gazetteerFile = config.gazetteerFile.getOrElse(Parser.getDefaultGazetteer.toFile)
    opts.gazetteerFile = gazetteerFile.toString

    opts.trainFraction = config.trainFraction
    opts.minExpectedFeatureCount = config.minExpectedFeatureCount

    Parser.trainBibliographyCRF(groundTruthFile, opts)

    logger.info(s"New model at ${opts.modelFile}")
  }
}
