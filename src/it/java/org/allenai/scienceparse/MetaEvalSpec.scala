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

    def normalizeList(l: List[String]) = l.map(normalize)

    def normalizeBR(bibRecord: BibRecord) = new BibRecord(
      normalize(bibRecord.title),
      bibRecord.author.asScala.map(normalize).asJava,
      normalize(bibRecord.venue),
      bibRecord.citeRegEx,
      bibRecord.shortCiteRegEx,
      bibRecord.year
      )

    def calculatePR[T](goldDataList: List[T], extractedData: List[T]) = {
      if (goldDataList.isEmpty) {
        (if (extractedData.isEmpty) 1.0 else 0.0, 1.0)
      } else if (extractedData.isEmpty) {
        (0.0, 0.0)
      } else {
        val goldData = goldDataList.toSet
        val precision = extractedData.count(goldData.contains).toDouble / extractedData.size
        val recall = goldData.count(extractedData.contains).toDouble / goldData.size
        (precision, recall)
      }
    }

    def genericEvaluator(extract: ExtractedMetadata => List[String], normalizer: String => String = identity) =
      (metadata: ExtractedMetadata, gold: List[String]) => {
        calculatePR(gold.map(normalizer), extract(metadata).map(normalizer))
      }

    def specializedEvaluator[T](extract: ExtractedMetadata => List[T], extractGold: List[String] => List[T],
                                normalizer: T => T,
                                prCalculator: (List[T], List[T]) => (Double, Double)) =
      (metadata: ExtractedMetadata, gold: List[String]) => {
        prCalculator(extractGold(gold).map(normalizer), extract(metadata).map(normalizer))
      }

    def fullNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.toList

    def lastNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.map(_.split("\\s+").last).toList

    def titleExtractor(metadata: ExtractedMetadata) = (Set(metadata.title) - null).toList

    def abstractExtractor(metadata: ExtractedMetadata) =
      if (metadata.abstractText == null) List() else metadata.abstractText.split("\\s+").toList

    def goldAbstractExtractor(abs: List[String]) = abs.head.split("\\s+").toList

    def abstractPR(goldAbs: List[String], extractedAbs: List[String]) = {
      if (extractedAbs.size < 2) {
        (0.0, 0.0)
      } else if (extractedAbs.head == goldAbs.head && extractedAbs.last == goldAbs.last) {
        (1.0, 1.0)
      } else {
        (0.0, 0.0)
      }
    }

    def bibExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.toList

    def goldBibExtractor(refs: List[String]) = refs.map { ref =>
      val Array(title, year, venue, authors) = ref.split("\\|", -1)
      new BibRecord(title, authors.split(":").toList.asJava, venue, null, null, year.toInt)
    }

    /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get three, that’s prec=1.0
      * but rec=0.6. Just add index # to name for simplicity */
    def multiSet(refs: List[String]) = refs.groupBy(identity).values.flatMap(_.zipWithIndex.map { case (ref, i) =>
      ref + i.toString
    }).toList

    def bibAuthorsExtractor(metadata: ExtractedMetadata) = multiSet(metadata.references.asScala.flatMap(_.author.asScala.toList).toList)

    def goldBibAuthorsExtractor(bibAuthors: List[String]) = multiSet(bibAuthors.flatMap(_.split(":").toList))

    def bibTitlesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.title).toList

    def bibVenuesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.venue).toList

    def bibYearsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.year.toString).toList

    case class Metric(
      name: String,
      goldFile: String,
      // get P/R values for each individual paper. values will be averaged later across all papers
      evaluator: (ExtractedMetadata, List[String]) => (Double, Double))
    val metrics = Seq(
      Metric("authorFullName",           "/golddata/dblp/authorFullName.tsv",  genericEvaluator(fullNameExtractor)),
      Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv",  genericEvaluator(fullNameExtractor, normalize)),
      Metric("authorLastName",           "/golddata/dblp/authorLastName.tsv",  genericEvaluator(lastNameExtractor)),
      Metric("authorLastNameNormalized", "/golddata/dblp/authorLastName.tsv",  genericEvaluator(lastNameExtractor, normalize)),
      Metric("title",                    "/golddata/dblp/title.tsv",           genericEvaluator(titleExtractor)),
      Metric("titleNormalized",          "/golddata/dblp/title.tsv",           genericEvaluator(titleExtractor, normalize)),
      Metric("abstract",                 "/golddata/isaac/abstracts.tsv",      specializedEvaluator[String](abstractExtractor, goldAbstractExtractor, identity, abstractPR)),
      Metric("abstractNormalized",       "/golddata/isaac/abstracts.tsv",      specializedEvaluator[String](abstractExtractor, goldAbstractExtractor, normalize, abstractPR)),
      Metric("bibliography",             "/golddata/isaac/bibliographies.tsv", specializedEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, calculatePR)), // obtained from scholar-project/pipeline/src/main/resources/ground-truths/bibliographies.json
      Metric("bibliographyNormalized",   "/golddata/isaac/bibliographies.tsv", specializedEvaluator[BibRecord](bibExtractor, goldBibExtractor, normalizeBR, calculatePR)),
      Metric("bibAuthors",               "/golddata/isaac/bib-authors.tsv",    specializedEvaluator[String](bibAuthorsExtractor, goldBibAuthorsExtractor, identity, calculatePR)),
      Metric("bibAuthorsNormalized",     "/golddata/isaac/bib-authors.tsv",    specializedEvaluator[String](bibAuthorsExtractor, goldBibAuthorsExtractor, normalize, calculatePR)),
      Metric("bibTitles",                "/golddata/isaac/bib-titles.tsv",     genericEvaluator(bibTitlesExtractor)),
      Metric("bibTitlesNormalized",      "/golddata/isaac/bib-titles.tsv",     genericEvaluator(bibTitlesExtractor, normalize)),
      Metric("bibVenues",                "/golddata/isaac/bib-venues.tsv",     genericEvaluator(bibVenuesExtractor)),
      Metric("bibVenuesNormalized",      "/golddata/isaac/bib-venues.tsv",     genericEvaluator(bibVenuesExtractor, normalize)),
      Metric("bibYears",                 "/golddata/isaac/bib-years.tsv",      genericEvaluator(bibYearsExtractor))
    )


    //
    // read gold data
    //

    val allGoldData = metrics.flatMap { metric =>
      Resource.using(Source.fromInputStream(getClass.getResourceAsStream(metric.goldFile))) { source =>
        source.getLines().take(maxDocumentCount).map { line =>
          val fields = line.trim.split("\t").map(_.trim)
          (metric, fields.head, fields.tail.toList)
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
      logger.info(f"${metric.name}%-30s\t$p%.3f\t$r%.3f")
    }
  }
}
