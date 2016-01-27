package org.allenai.scienceparse

import org.allenai.common.{Logging, Resource}
import org.allenai.common.testkit.UnitSpec
import org.allenai.common.StringUtils._
import org.allenai.datastore.Datastores

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.io.Source
import scala.util.{Success, Failure, Try}
import scala.collection.JavaConverters._

class MetaEvalSpec extends UnitSpec with Datastores with Logging {
  "MetaEval" should "produce good P/R numbers" in {
    val maxDocumentCount = 1000 // set this to something low for testing, set it high before committing

    //
    // define metrics
    //

    def normalize(s: String) = s.replaceFancyUnicodeChars.removeUnprintable.normalize

    def calculatePR(goldData: Set[String], extractedData: Set[String]) = {
      if(extractedData.isEmpty) {
        (0.0, 0.0)
      } else {
        val precision = extractedData.count(goldData.contains).toDouble / extractedData.size
        val recall = goldData.count(extractedData.contains).toDouble / goldData.size
        (precision, recall)
      }
    }

    def fullNameEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) = {
      val extractedFullNames = extractedMetadata.getAuthors.asScala.toSet
      calculatePR(goldData, extractedFullNames)
    }

    def fullNameNormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) = {
      val extractedFullNames = extractedMetadata.getAuthors.asScala.map(normalize).toSet
      calculatePR(goldData.map(normalize), extractedFullNames)
    }

    def lastNameEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) = {
      val extractedLastNames =
        extractedMetadata.getAuthors.asScala.map(_.split("\\s+").last).toSet
      calculatePR(goldData, extractedLastNames)
    }

    def lastNameNormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) = {
      val extractedLastNames =
        extractedMetadata.getAuthors.asScala.map(normalize(_).split("\\s+").last).toSet
      calculatePR(goldData.map(normalize), extractedLastNames)
    }

    def titleEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      calculatePR(goldData, Set(extractedMetadata.getTitle) - null)

    def titleNormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      calculatePR(goldData.map(normalize), (Set(extractedMetadata.getTitle) - null).map(normalize))

    case class Metric(
      name: String,
      goldFile: String,
      evaluator: (ExtractedMetadata, Set[String]) => (Double, Double))
    val metrics = Seq(
      Metric("authorFullName", "/golddata/dblp/authorFullName.tsv", fullNameEvaluator),
      Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv", fullNameNormalizedEvaluator),
      Metric("authorLastName", "/golddata/dblp/authorLastName.tsv", lastNameEvaluator),
      Metric("authorLastNameNormalized", "/golddata/dblp/authorLastName.tsv", lastNameNormalizedEvaluator),
      Metric("title", "/golddata/dblp/title.tsv", titleEvaluator),
      Metric("titleNormalized", "/golddata/dblp/title.tsv", titleNormalizedEvaluator)
    )


    //
    // read gold data
    //

    val allGoldData = metrics.flatMap { metric =>
      Resource.using(Source.fromInputStream(getClass.getResourceAsStream(metric.goldFile))) { source =>
        source.getLines().take(maxDocumentCount).map { line =>
          val fields = line.trim.split("\t").map(_.trim)
          (metric, fields.head, fields.tail.toSet)
        }.toList
      }
    }
    // allGoldData is now a Seq[(Metric, DocId, Set[Label])]
    val docIds = allGoldData.map(_._2).toSet

    //
    // download the documents and run extraction
    //

    val extractions = {
      val parser = Resource.using2(
        Files.newInputStream(publicFile("integrationTestModel.dat", 1)),
        getClass.getResourceAsStream("/referencesGroundTruth.json")
      ) { case (modelIs, gazetteerIs) =>
        new Parser(modelIs, gazetteerIs)
      }
      val pdfDirectory = publicDirectory("PapersTestSet", 1)

      val documentCount = docIds.size
      logger.info(s"Running on $documentCount documents")

      val totalDocumentsDone = new AtomicInteger()
      val startTime = System.currentTimeMillis()

      val result = docIds.par.map { docid =>
        val pdf = pdfDirectory.resolve(s"$docid.pdf")
        val result = Resource.using(Files.newInputStream(pdf)) { is =>
          docid -> Try(parser.doParse(is))
        }

        val documentsDone = totalDocumentsDone.incrementAndGet()
        if(documentsDone % 50 == 0) {
          val timeSpent = System.currentTimeMillis() - startTime
          val speed = 1000.0 * documentsDone / timeSpent
          val completion = 100.0 * documentsDone / documentCount
          logger.info(f"Finished $documentsDone documents ($completion%.0f%%, $speed%.2f dps) ...")
        }

        result
      }.toMap

      val finishTime = System.currentTimeMillis()

      // final report
      val dps = 1000.0 * documentCount / (finishTime - startTime)
      logger.info(f"Finished $documentCount documents at $dps%.2f documents per second")
      assert(dps > 1.0)

      // error report
      val failures = result.values.collect { case Failure(e) => e }
      val errorRate = 100.0 * failures.size / documentCount
      logger.info(f"Failed ${failures.size} times ($errorRate%.2f%%)")
      assert(errorRate < 5.0)
      if(failures.nonEmpty) {
        logger.info("Top errors:")
        failures.
          groupBy(_.getClass.getName).
          mapValues(_.size).
          toArray.
          sortBy(-_._2).
          take(10).
          foreach { case (error, count) =>
            logger.info(s"$count\t$error")
          }
      }

      result
    }


    //
    // calculate precision and recall for all metrics
    //

    logger.info("Evaluation results:")
    val prResults = allGoldData.map { case (metric, docid, goldData) =>
      extractions(docid) match {
        case Failure(_) => (metric, (0.0, 0.0))
        case Success(extractedMetadata) => (metric, metric.evaluator(extractedMetadata, goldData))
      }
    }
    prResults.groupBy(_._1).mapValues { prs =>
      val (ps, rs) = prs.map(_._2).unzip
      (ps.sum / ps.size, rs.sum / rs.size)
    }.toArray.sortBy(_._1.name).foreach { case (metric, (p, r)) =>
      logger.info(f"${metric.name}\t$p%.3f\t$r%.3f")
    }
  }
}
