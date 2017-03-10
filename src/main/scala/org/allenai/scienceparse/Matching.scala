package org.allenai.scienceparse

import java.io._

import com.gs.collections.impl.set.mutable.UnifiedSet
import org.allenai.common.{Resource, Logging}
import org.allenai.datastore.Datastores
import org.apache.commons.lang3.StringEscapeUtils
import scopt.OptionParser

import scala.collection.JavaConverters._


/** A command line utility that makes labeled streams of characters from PDF documents */
object Matching extends App with Datastores with Logging {
  // The Files are all Option[File] defaulting to None. Properly, they should be set to the
  // defaults from the datastore, but if we do that here, they will download several gigabytes
  // of files during startup, even if they are unused later.
  case class Config(
    output: Option[File] = None,
    groundTruth: Option[File] = None,
    maxHeaderWords: Int = Training.defaultConfig.maxHeaderWords,
    minYear: Int = Training.defaultConfig.minYear,
    maxPaperCount: Int = Training.defaultConfig.maxPaperCount,
    paperDir: Option[File] = None,
    excludeIdsFile: Option[File] = None
  )

  val parser = new OptionParser[Config](this.getClass.getSimpleName) {
    opt[File]('o', "output") action { (o, c) =>
      c.copy(output = Some(o))
    } text "The output file"

    opt[File]('t', "groundTruth") action { (t, c) =>
      c.copy(groundTruth = Some(t))
    } text "The ground truth file"

    opt[Int]("maxHeaderWords") action { (m, c) =>
      c.copy(maxHeaderWords = m)
    } text "Specifies the maximum number of words to use for the header if we don't have any other information about where the header ends"

    opt[Int]('c', "maxPaperCount") action { (p, c) =>
      c.copy(maxPaperCount = p)
    } text "The maximum number of labeled documents to consider"

    opt[File]('d', "paperDir") action { (d, c) =>
      c.copy(paperDir = Some(d))
    } text "The directory that contains the papers for which we have labeling information"
  }

  parser.parse(args, Config()).foreach { config =>
    val pgt =
      config.groundTruth.map(ParserGroundTruth.fromFile).getOrElse(Training.defaultGroundTruth)

    val paperSource = config.paperDir.map(new DirectoryPaperSource(_)).getOrElse {
      new RetryPaperSource(ScholarBucketPaperSource.getInstance(), 5)
    }

    val labeled = Parser.labelFromGroundTruth(
      pgt,
      paperSource,
      config.maxHeaderWords,
      config.maxPaperCount,
      config.minYear,
      true,
      UnifiedSet.newSet(Training.readExcludedIds(config.excludeIdsFile).toIterable.asJava))

    Resource.using(new PrintWriter(
      new BufferedWriter(
        new OutputStreamWriter(
          config.output.map(
            new FileOutputStream(_)
          ).getOrElse(System.out), "UTF-8")))) { output =>

      for {
        (doc, docNumber) <- labeled.labeledData.asScala.zipWithIndex
        tokenLabelPair <- doc.asScala
        pdfToken = tokenLabelPair.getOne.getPdfToken
        if pdfToken != null
        label = tokenLabelPair.getTwo
        (character, index) <- pdfToken.token.zipWithIndex
      } {
        val fontName = {
          val rawFontName = pdfToken.fontMetrics.name
          val lastUnderscore = rawFontName.lastIndexOf('_')
          if (lastUnderscore < 1) rawFontName
          else {
            val secondLastUnderscore = rawFontName.lastIndexOf('_', lastUnderscore - 1)
            if (secondLastUnderscore < 0) rawFontName
            else {
              rawFontName.substring(0, secondLastUnderscore)
            }
          }
        }

        // coordinates
        val top = pdfToken.bounds.get(1)
        val bottom = pdfToken.bounds.get(3)
        val width = pdfToken.bounds.get(2) - pdfToken.bounds.get(0)
        val widthPerCharacter = width / pdfToken.token.length
        val left = pdfToken.bounds.get(0) + widthPerCharacter * index
        val right = left + widthPerCharacter

        output.print(docNumber)
        output.print('\t')
        output.print(StringEscapeUtils.escapeCsv(character.toString))
        output.print('\t')
        output.print(character.asInstanceOf[Int])
        output.print('\t')
        output.print(fontName)
        output.print('\t')
        output.print(pdfToken.fontMetrics.ptSize)
        output.print('\t')
        output.print(pdfToken.fontMetrics.spaceWidth)
        output.print('\t')
        output.print(top)
        output.print('\t')
        output.print(bottom)
        output.print('\t')
        output.print(left)
        output.print('\t')
        output.print(right)
        output.print('\t')
        output.print(label.last)
        output.println()
      }
    }
  }
}
