package org.allenai.scienceparse

import java.io.{ DataInputStream, File }
import java.nio.file.Files
import java.util

import com.gs.collections.api.map.primitive.ObjectDoubleMap
import org.allenai.common.Resource
import org.allenai.scienceparse.pdfapi.PDFExtractor
import scopt.OptionParser

import scala.collection.JavaConverters._

object PrintFeaturizedCRFInput extends App {
  case class Config(
    paperDir: Option[File] = None,
    modelFile: Option[File] = None,
    paperId: String = null
  )

  val parser = new OptionParser[Config](this.getClass.getSimpleName) {
    opt[File]('d', "paperDir") action { (d, c) =>
      c.copy(paperDir = Some(d))
    } text "The directory that contains the papers"

    opt[File]('m', "model") action { (m, c) =>
      c.copy(modelFile = Some(m))
    } text "A model to load LM feature values from"

    arg[String]("<paperId>") required () action { (p, c) =>
      c.copy(paperId = p)
    } text "The ID of the paper whose CRF input you want to see"
  }

  parser.parse(args, Config()).foreach { config =>
    val paperSource = config.paperDir.map(new DirectoryPaperSource(_)).getOrElse {
      PaperSource.getDefault
    }

    val predExtractor = {
      val modelPath = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      Resource.using(new DataInputStream(Files.newInputStream(modelPath))) { dis =>
        Parser.loadModelComponents(dis).predExtractor
      }
    }

    val seq = Resource.using(paperSource.getPdf(config.paperId)) { is =>
      val ext = new PDFExtractor
      val doc = ext.extractFromInputStream(is)
      PDFToCRFInput.getSequence(doc)
    }

    val paddedSeq = PDFToCRFInput.padSequence(seq).asScala.toSeq

    val lines = stringsFromFeaturizedSeq(predExtractor.nodePredicates(paddedSeq.asJava))

    lines.asScala.foreach(println)
  }

  def stringsFromFeaturizedSeq(
    featurizedJava: util.List[ObjectDoubleMap[String]],
    prefix: String = ""
  ) = {
    // do a complicated dance to map from GS collections to Scala collections
    val featurized = featurizedJava.asScala.map { gsMap =>
      gsMap.keySet().asScala.map { key => key -> gsMap.get(key) }.toMap
    }.toSeq

    // token feature is special
    val tokenFeaturePrefix = "%t="

    // figure out binary features
    val feature2values = featurized.flatten.foldLeft(Map.empty[String, Set[Double]]) {
      case (acc, (key, value)) => acc.updated(key, acc.getOrElse(key, Set[Double]()) + value)
    }
    val binaryFeatures = feature2values.
      filter(_._2 subsetOf Set(0.0, 1.0)).
      keys.
      filterNot(_.startsWith(tokenFeaturePrefix)).
      toSet

    // figure out an order for non-binary features
    val orderedNonBinaryFeatures = featurized.
      flatMap(_.keys).
      filterNot(binaryFeatures).
      filterNot(_.startsWith(tokenFeaturePrefix)).
      groupBy(identity).
      mapValues(_.size).
      toSeq.sortBy { case (feature, count) => (-count, feature) }.
      map(_._1)

    // write header
    val header = (tokenFeaturePrefix +: orderedNonBinaryFeatures).mkString("\t")

    // write entries
    val body = featurized.zipWithIndex.map {
      case (features, index) =>
        (
          // token feature
          Seq(
            features.filter(_._1.startsWith(tokenFeaturePrefix)).map { case (key, value) => s"$key=$value" }.mkString("/")
          ) ++

            // non-binary features
            orderedNonBinaryFeatures.map { f => features.get(f).map(d => f"$d%.3f").getOrElse("") } ++

            // binary features
            (features.keySet & binaryFeatures).toSeq.sorted
        ).mkString("\t")
    }

    val result = header +: body

    if (prefix.isEmpty) {
      result.asJava
    } else {
      result.zipWithIndex.map { case (line, i) => f"$prefix\t$i%04d\t$line" }.asJava
    }
  }
}
