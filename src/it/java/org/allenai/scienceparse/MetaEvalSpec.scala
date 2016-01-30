package org.allenai.scienceparse

import org.allenai.common.{Logging, Resource}
import org.allenai.common.testkit.UnitSpec
import org.allenai.common.StringUtils._
import org.allenai.datastore.Datastores

import scala.xml.XML
import scala.collection.JavaConverters._
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

    def normalizeBR(bibRecord: BibRecord) = new BibRecord(
      normalize(bibRecord.title),
      bibRecord.author.asScala.map(normalize).asJava,
      normalize(bibRecord.venue),
      bibRecord.citeRegEx,
      bibRecord.shortCiteRegEx,
      bibRecord.year
      )

    def calculatePR[T](goldData: Set[T], extractedData: Set[T]) = {
      if (goldData.isEmpty) {
        (if (extractedData.isEmpty) 1.0 else 0.0, 1.0)
      } else if (extractedData.isEmpty) {
        (0.0, 0.0)
      } else {
        val precision = extractedData.count(goldData.contains).toDouble / extractedData.size
        val recall = goldData.count(extractedData.contains).toDouble / goldData.size
        (precision, recall)
      }
    }

    def genericEvaluator[T](extract: ExtractedMetadata => Set[T], normalizer: T => T = identity) =
      (metadata: ExtractedMetadata, gold: Set[T]) => {
        calculatePR(gold.map(normalizer), extract(metadata).map(normalizer))
      }

    def fullNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.toSet

    def lastNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.map(_.split("\\s+").last).toSet

    def titleExtractor(metadata: ExtractedMetadata) = Set(metadata.title) - null

    def bibliographyEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String], normalizer: BibRecord => BibRecord) =
      calculatePR(goldData.map { ref =>
        val Array(title, year, venue, authors) = ref.split("\\|", -1)
        normalizer(new BibRecord(title, authors.split(":").toList.asJava, venue, null, null, year.toInt))
      }, extractedMetadata.references.asScala.toSet.map(normalizer))

    def bibliographyUnnormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      bibliographyEvaluator(extractedMetadata, goldData, identity)

    def bibliographyNormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      bibliographyEvaluator(extractedMetadata, goldData, normalizeBR)

    def abstractEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String], normalizer: String => String) = {
      if (extractedMetadata.abstractText == null) {
        (0.0, 0.0)
      } else {
        val extracted = normalizer(extractedMetadata.abstractText).split(" ")
        val gold = normalizer(goldData.head).split(" ")
        if (extracted.head == gold.head && extracted.last == gold.last) {
          (1.0, 1.0)
        } else {
          (0.0, 0.0)
        }
      }
    }

    def abstractUnnormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      abstractEvaluator(extractedMetadata, goldData, identity)

    def abstractNormalizedEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String]) =
      abstractEvaluator(extractedMetadata, goldData, normalize)

    def bibAuthorsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.title).toSet

    def bibAuthorsEvaluator(extractedMetadata: ExtractedMetadata, goldData: Set[String], normalizer: String => String) = {
      val extractedAuthors = extractedMetadata.references.asScala.map(_.author.asScala.toSet).toList
      val goldAuthors =
      val prs = calculatePR()
    }

    def bibTitlesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.title).toSet

    def bibVenuesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.venue).toSet

    def bibYearsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.venue).toSet

    case class Metric(
      name: String,
      goldFile: String,
      // get P/R values for each individual paper. values will be averaged later across all papers
      evaluator: (ExtractedMetadata, Set[String]) => (Double, Double))
    val metrics = Seq(
      Metric("authorFullName",           "/golddata/dblp/authorFullName.tsv",  genericEvaluator(fullNameExtractor)),
      Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv",  genericEvaluator(fullNameExtractor, normalize)),
      Metric("authorLastName",           "/golddata/dblp/authorLastName.tsv",  genericEvaluator(lastNameExtractor)),
      Metric("authorLastNameNormalized", "/golddata/dblp/authorLastName.tsv",  genericEvaluator(fullNameExtractor, normalize)),
      Metric("title",                    "/golddata/dblp/title.tsv",           genericEvaluator(titleExtractor)),
      Metric("titleNormalized",          "/golddata/dblp/title.tsv",           genericEvaluator(titleExtractor, normalize)),
      Metric("abstract",                 "/golddata/isaac/abstracts.tsv",      abstractUnnormalizedEvaluator),
      Metric("abstractNormalized",       "/golddata/isaac/abstracts.tsv",      abstractUnnormalizedEvaluator),
      Metric("bibliography",             "/golddata/isaac/bibliographies.tsv", bibliographyUnnormalizedEvaluator), // obtained from scholar-project/pipeline/src/main/resources/ground-truths/bibliographies.json
      Metric("bibliographyNormalized",   "/golddata/isaac/bibliographies.tsv", bibliographyNormalizedEvaluator),
      Metric("bib-authors",              "/golddata/isaac/bib-authors.tsv",    stringEvaluator),
      Metric("bib-authors-normalized",   "/golddata/isaac/bib-authors.tsv",    stringNormalizedEvaluator),
      Metric("bib-titles",               "/golddata/isaac/bib-titles.tsv",     genericEvaluator(bibTitlesExtractor)),
      Metric("bib-titles-normalized",    "/golddata/isaac/bib-titles.tsv",     genericEvaluator(bibTitlesExtractor, normalize)),
      Metric("bib-venues",               "/golddata/isaac/bib-venues.tsv",     genericEvaluator(bibVenuesExtractor)),
      Metric("bib-venues-normalized",    "/golddata/isaac/bib-venues.tsv",     genericEvaluator(bibVenuesExtractor, normalize)),
      Metric("bib-years",                "/golddata/isaac/bib-years.tsv",      genericEvaluator(bibYearsExtractor))
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
      val pdfDirectory = publicDirectory("PapersTestSet", 3)

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
        assert(errorRate < 5.0)
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
