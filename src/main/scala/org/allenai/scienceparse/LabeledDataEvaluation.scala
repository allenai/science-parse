package org.allenai.scienceparse

import java.util.concurrent.{TimeUnit, Executors}

import org.allenai.common.ParIterator._
import java.net.URL
import org.allenai.scienceparse.LabeledData.{Reference, Author}

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext

object LabeledDataEvaluation {
  import StringUtils._

  case class PR(precision: Double, recall: Double)

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
      val goldData = LabeledDataFromResources.get
      
      val extractions = goldData.parMap { gold =>
        val grobid = labeledDataFromGrobidServer.get(gold.inputStream)
        val sp = LabeledDataFromScienceParse.get(gold.inputStream)
        (gold, sp, grobid)
      }.toSeq

      // calculate title metrics
      {
        val titleMetricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          gold.title.map(normalize).map { normalizedGoldTitle =>
            def score(scoredTitleOption: Option[String]) = scoredTitleOption match {
              case Some(scoredTitle) if normalize(scoredTitle) == normalizedGoldTitle => 1
              case _ => 0
            }

            (score(sp.title), score(grobid.title))
          }
        }

        val titleCount = titleMetricsPerDocument.size
        val titleSpScore = titleMetricsPerDocument.map(_._1).sum / titleCount.toDouble
        val titleGrobidScore = titleMetricsPerDocument.map(_._2).sum / titleCount.toDouble

        println(s"SP      titlesNormalized: $titleSpScore")
        println(s"Grobid  titlesNormalized: $titleGrobidScore")
      }

      // calculate author metrics
      {
        val authorMetricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          gold.authors.map { goldAuthors =>
            val normalizedGoldAuthors = goldAuthors.map(a => normalizeAuthor(a.name)).toSet
            def score(scoredAuthorsOption: Option[Seq[Author]]) = scoredAuthorsOption match {
              case None => PR(0.0, 0.0)
              case Some(scoredAuthors) =>
                val normalizedScoredAuthors = scoredAuthors.map(a => normalizeAuthor(a.name)).toSet
                PR.fromSets(normalizedGoldAuthors, normalizedScoredAuthors)
            }

            (score(sp.authors), score(grobid.authors))
          }
        }

        val prForSp = PR.average(authorMetricsPerDocument.map(_._1))
        val prForGrobid = PR.average(authorMetricsPerDocument.map(_._2))

        println(s"SP      authorFullNameNormalized: ${prForSp.precision} / ${prForSp.recall}")
        println(s"Grobid  authorFullNameNormalized: ${prForGrobid.precision} / ${prForGrobid.recall}")
      }

      // calculate bib metrics
      {
        val bibMetricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
          gold.references.map { goldReferences =>
            def normalizeReference(ref: Reference) = Reference(
              None, // ignoring label, because Grobid doesn't have that
              ref.title.map(normalize),
              ref.authors.map(normalizeAuthor),
              None, // ignoring venue
              ref.year,
              None, // ignoring volume, because we didn't take that into consideration before
              None  // ignoring pageRange, because we didn't take that into consideration before
            )

            val normalizedGoldBibEntries = goldReferences.map(normalizeReference).toSet
            def score(scoredBibEntriesOption: Option[Seq[Reference]]) = scoredBibEntriesOption match {
              case None => PR(0.0, 0.0)
              case Some(scoredBibEntries) =>
                val normalizedScoredBibEntries = scoredBibEntries.map(normalizeReference).toSet
                PR.fromSets(normalizedGoldBibEntries, normalizedScoredBibEntries)
            }

            (score(sp.references), score(grobid.references))
          }
        }

        val prForSp = PR.average(bibMetricsPerDocument.map(_._1))
        val prForGrobid = PR.average(bibMetricsPerDocument.map(_._2))

        println(s"SP      bibAllButVenuesNormalized: ${prForSp.precision} / ${prForSp.recall}")
        println(s"Grobid  bibAllButVenuesNormalized: ${prForGrobid.precision} / ${prForGrobid.recall}")
      }
    } finally {
      executor.shutdown()
      executor.awaitTermination(10, TimeUnit.MINUTES)
    }
  }
}
