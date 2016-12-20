package org.allenai.scienceparse

import java.util.concurrent.{TimeUnit, Executors}

import org.allenai.common.Logging
import org.allenai.common.ParIterator._
import java.net.URL
import org.allenai.scienceparse.LabeledData.Reference

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext

object LabeledDataEvaluation extends Logging {
  import StringUtils._

  case class PR(precision: Double, recall: Double) {
    def p = precision
    def r = recall
  }

  object PR {
    def average(prs: Iterable[PR]) = {
      val (totalCount, totalSumP, totalSumR) = prs.foldLeft((0, 0.0, 0.0)) {
        case ((count, sumP, sumR), pr) =>
          (count + 1, sumP + pr.precision, sumR + pr.recall)
      }
      PR(totalSumP / totalCount, totalSumR / totalCount)
    }

    def fromSets[T](gold: Set[T], result: Set[T]): PR = {
      if(gold.isEmpty && result.isEmpty) {
        PR(1.0, 1.0)
      } else if(gold.isEmpty && result.nonEmpty) {
        PR(0.0, 1.0)
      } else if(gold.nonEmpty && result.isEmpty) {
        PR(0.0, 0.0)
      } else {
        val numberCorrect = (result & gold).size
        PR(
          numberCorrect.toDouble / result.size,
          numberCorrect.toDouble / gold.size
        )
      }
    }
  }

  private def normalizeAuthor(s: String) =
    normalize(s).replace('.', ' ').replaceAll("\\s+", " ")

  def main(args: Array[String]): Unit = {
    val labeledDataFromGrobidServer = {
      val grobidServerUrl = new URL(args(0))
      new LabeledDataFromGrobidServer(grobidServerUrl)
    }

    val executor = Executors.newFixedThreadPool(32)
    try {
      implicit val ec = ExecutionContext.fromExecutor(executor)

      // read the data
      logger.info("Reading in data ...")
      val goldData = LabeledDataFromResources.get

      val extractions = goldData.parMap { gold =>
        val grobid = labeledDataFromGrobidServer.get(gold.inputStream)
        val sp = LabeledDataFromScienceParse.get(gold.inputStream)
        (gold, sp, grobid)
      }.toSeq

      // write header
      // buffer output so that console formatting doesn't get messed up
      val output = scala.collection.mutable.ArrayBuffer.empty[String]
      output += f"""${"EVALUATION RESULTS"}%-30s${"PRECISION"}%28s${"RECALL"}%28s${"SAMPLE"}%10s"""
      output += f"""${""}%-30s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%6s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%6s${"SIZE"}%10s"""
      output += "-----------------------------------------+--------+------------------+--------+-----------------"
      def outputLine(metricName: String, spPr: PR, grobidPr: PR, docCount: Int) = {
        val diffP = spPr.p - grobidPr.p
        val diffR = spPr.r - grobidPr.r
        f"$metricName%-30s${spPr.p}%10.3f | ${grobidPr.p}%6.3f | $diffP%+5.3f${spPr.r}%10.3f | ${grobidPr.r}%6.3f | $diffR%+5.3f$docCount%10d"
      }

      // calculate title metrics
      {
        logger.info("Calculating titlesNormalized ...")
        val titleMetricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          gold.title.map(normalize).map { normalizedGoldTitle =>
            def score(scoredTitleOption: Option[String]) = scoredTitleOption match {
              case Some(scoredTitle) if normalize(scoredTitle) == normalizedGoldTitle => 1
              case _ => 0
            }

            (score(sp.title), score(grobid.title))
          }
        }

        val count = titleMetricsPerDocument.size
        val spScore = titleMetricsPerDocument.map(_._1).sum / count.toDouble
        val grobidScore = titleMetricsPerDocument.map(_._2).sum / count.toDouble
        output += outputLine("titlesNormalized", PR(spScore, spScore), PR(grobidScore, grobidScore), count)
      }

      // infrastructure for metrics that all have the same format
      // Returns (SP PR, Grobid PR, document count)
      def evaluateMetric[T](extract: LabeledData => Option[Iterable[T]]): (PR, PR, Int) = {
        /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get
          * three, that’s prec=1.0 but rec=0.6. Just add index # to name for simplicity. This is redundant
          * when everything is already unique, so you can basically always apply it.
          */
        def multiSet[T2](refs: Iterable[T2]) =
          refs.groupBy(identity).values.flatMap(_.zipWithIndex).toSet

        val metricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          extract(gold).map { goldEntries =>
            val goldEntriesSet = multiSet(goldEntries)

            def score(scoredOption: Option[Iterable[T]]) = scoredOption match {
              case None => PR(0.0, 0.0)
              case Some(scored) => PR.fromSets(goldEntriesSet, multiSet(scored))
            }

            (score(extract(sp)), score(extract(grobid)))
          }
        }

        (
          PR.average(metricsPerDocument.map(_._1)),
          PR.average(metricsPerDocument.map(_._2)),
          metricsPerDocument.size
        )
      }

      // calculate author metrics
      {
        logger.info("Calculating authorFullNameNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.authors.map { as =>
            as.map(a => normalizeAuthor(a.name))
          }
        }
        output += outputLine("authorFullNameNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating authorLastNameNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.authors.map { as =>
            def getLastName(name: String) = {
              val suffixes = Set("Jr.")
              name.split("\\s+").filterNot(suffixes.contains).lastOption.getOrElse(name)
            }

            as.map(a => normalize(getLastName(a.name)))
          }
        }
        output += outputLine("authorLastNameNormalized", sp, grobid, count)
      }

      // calculate bib metrics
      {
        logger.info("Calculating bibCount ...")

        val bibCountsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          gold.references.map { refs =>
            val goldCount = refs.size
            val spCount = sp.references.map(_.size).getOrElse(0)
            val grobidCount = grobid.references.map(_.size).getOrElse(0)

            (spCount / goldCount.toDouble, grobidCount / goldCount.toDouble)
          }
        }

        val count = bibCountsPerDocument.size
        val spScore = bibCountsPerDocument.map(_._1).sum / count.toDouble
        val grobidScore = bibCountsPerDocument.map(_._2).sum / count.toDouble
        output += outputLine("bibCount", PR(spScore, spScore), PR(grobidScore, grobidScore), count)
      }

      {
        logger.info("Calculating bibAllNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          def normalizeReference(ref: Reference) = Reference(
            None, // ignoring label, because Grobid doesn't have that
            ref.title.map(normalize),
            ref.authors.map(normalizeAuthor),
            ref.venue.map(normalize),
            ref.year,
            None, // ignoring volume, because we didn't take that into consideration before
            None  // ignoring pageRange, because we didn't take that into consideration before
          )

          labeledData.references.map { refs =>
            refs.map(normalizeReference)
          }
        }
        output += outputLine("bibAllNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating bibAllButVenuesNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          def normalizeReference(ref: Reference) = Reference(
            None, // ignoring label, because Grobid doesn't have that
            ref.title.map(normalize),
            ref.authors.map(normalizeAuthor),
            None, // ignoring venue
            ref.year,
            None, // ignoring volume, because we didn't take that into consideration before
            None  // ignoring pageRange, because we didn't take that into consideration before
          )

          labeledData.references.map { refs =>
            refs.map(normalizeReference)
          }
        }
        output += outputLine("bibAllButVenuesNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating bibTitlesNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.references.map { refs =>
            refs.flatMap(_.title).map(normalize)
          }
        }
        output += outputLine("bibTitlesNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating bibAuthorsNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.references.map { refs =>
            refs.flatMap(_.authors).map(normalizeAuthor)
          }
        }
        output += outputLine("bibAuthorsNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating bibVenuesNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.references.map { refs =>
            refs.flatMap(_.venue).map(normalize)
          }
        }
        output += outputLine("bibVenuesNormalized", sp, grobid, count)
      }

      {
        logger.info("Calculating bibYears ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.references.map { refs =>
            refs.flatMap(_.year)
          }
        }
        output += outputLine("bibYears", sp, grobid, count)
      }

      // calculate abstract metrics
      {
        logger.info("Calculating abstractNormalized ...")
        val (sp, grobid, count) = evaluateMetric { labeledData =>
          labeledData.abstractText.map(a => Iterable(normalize(a)))
        }

        output += outputLine("abstractNormalized", sp, grobid, count)
      }

      println(output.map(line => s"${Console.BOLD}${Console.BLUE}$line${Console.RESET}").mkString("\n"))
    } finally {
      executor.shutdown()
      executor.awaitTermination(10, TimeUnit.MINUTES)
    }
  }
}
