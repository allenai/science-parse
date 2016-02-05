package org.allenai.scienceparse

import org.allenai.common.{Logging, Resource}
import org.allenai.common.testkit.UnitSpec
import org.allenai.common.StringUtils._
import org.allenai.datastore.Datastores

import scala.xml.XML
import scala.collection.JavaConverters._
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.io.{Codec, Source}
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

    /** Just count the number of bib entries we're getting */
    def bibCounter(goldData: Set[BibRecord], extractedData: Set[BibRecord]) =
      (1.0, extractedData.size.toDouble / goldData.size) // since we're not doing matching, just assume 100% precision

    /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get three, that’s prec=1.0
      * but rec=0.6. Just add index # to name for simplicity. This is redundant when everything is already unique, so
      * you can basically always apply it
      */
    def multiSet(refs: List[String]) = refs.groupBy(identity).values.flatMap(_.zipWithIndex.map { case (ref, i) =>
      ref + i.toString
    }).toSet

    /**
      * This generates an evaluator for string metadata
      * @param extract Given automatically ExtractedMetadata from a paper, how do we get the field we want to compare
      *                against gold data?
      * @param extractGold Given the set of tab-delimited gold data, how do we get the field we want to compare
      *                    extracted metadata against?
      * @param normalizer A function that normalizes extracted strings in some way for a more fair comparison of quality
      * @param disallow A set of junk/null values that will be filtered out from the comparison
      * @param prCalculator Function that calculates precision and recall of extraction against gold
      * @return A function that evaluates the quality of extracted metadata, compared to manually labeled gold metadata
      */
    def stringEvaluator(extract: ExtractedMetadata => List[String], extractGold: List[String] => List[String] = identity,
                        normalizer: String => String = identity, disallow: Set[String] = Set(""),
                        prCalculator: (Set[String], Set[String]) => (Double, Double) = calculatePR) =
      (metadata: ExtractedMetadata, gold: List[String]) => {
        // function to clean up both gold and extracted data before we pass it in
        val clean = (x: List[String]) => multiSet(x.map(normalizer).filter(!disallow.contains(_)))
        prCalculator(clean(extractGold(gold)), clean(extract(metadata)))
    }

    def genericEvaluator[T](extract: ExtractedMetadata => List[T], extractGold: List[String] => List[T],
                                normalizer: T => T,
                                prCalculator: (Set[T], Set[T]) => (Double, Double)) =
      (metadata: ExtractedMetadata, gold: List[String]) => {
        prCalculator(extractGold(gold).map(normalizer).toSet, extract(metadata).map(normalizer).toSet)
    }

    def fullNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.toList

    def lastNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.map(_.split("\\s+").last).toList

    def titleExtractor(metadata: ExtractedMetadata) = (Set(metadata.title) - null).toList

    def firstNLastWord(x: String) = {
      val words = x.split("\\s+")
      words.head + " " + words.last
    }

    def abstractExtractor(metadata: ExtractedMetadata) =
      if (metadata.abstractText == null) List() else List(firstNLastWord(metadata.abstractText))

    def goldAbstractExtractor(abs: List[String]) = List(firstNLastWord(abs.head))

    def bibExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.toList

    def goldBibExtractor(refs: List[String]) = refs.map { ref =>
      val Array(title, year, venue, authors) = ref.split("\\|", -1)
      new BibRecord(title, authors.split(":").toList.asJava, venue, null, null, year.toInt)
    }

    def bibAuthorsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.flatMap(_.author.asScala.toList).toList

    def goldBibAuthorsExtractor(bibAuthors: List[String]) = bibAuthors.flatMap(_.split(":").toList)

    def bibTitlesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.title).toList

    def bibVenuesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.venue).toList

    def bibYearsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.year.toString).toList

    case class Metric(
      name: String,
      goldFile: String,
      // get P/R values for each individual paper. values will be averaged later across all papers
      evaluator: (ExtractedMetadata, List[String]) => (Double, Double))
    val metrics = Seq(
      Metric("authorFullName",           "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor)),
      Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor, normalizer = normalize)),
      Metric("authorLastName",           "/golddata/dblp/authorLastName.tsv",  stringEvaluator(lastNameExtractor)),
      Metric("authorLastNameNormalized", "/golddata/dblp/authorLastName.tsv",  stringEvaluator(lastNameExtractor, normalizer = normalize)),
      Metric("title",                    "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor)),
      Metric("titleNormalized",          "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor, normalizer = normalize)),
      Metric("abstract",                 "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor)),
      Metric("abstractNormalized",       "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor, normalize)),
      Metric("bibAll",                   "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, calculatePR)), // obtained from scholar-project/pipeline/src/main/resources/ground-truths/bibliographies.json
      Metric("bibAllNormalized",         "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, normalizeBR, calculatePR)),
      Metric("bibCounts",                "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, bibCounter)),
      Metric("bibAuthors",               "/golddata/isaac/bib-authors.tsv",    stringEvaluator(bibAuthorsExtractor, goldBibAuthorsExtractor)),
      Metric("bibAuthorsNormalized",     "/golddata/isaac/bib-authors.tsv",    stringEvaluator(bibAuthorsExtractor, goldBibAuthorsExtractor, normalize)),
      Metric("bibTitles",                "/golddata/isaac/bib-titles.tsv",     stringEvaluator(bibTitlesExtractor)),
      Metric("bibTitlesNormalized",      "/golddata/isaac/bib-titles.tsv",     stringEvaluator(bibTitlesExtractor, normalizer = normalize)),
      Metric("bibVenues",                "/golddata/isaac/bib-venues.tsv",     stringEvaluator(bibVenuesExtractor)),
      Metric("bibVenuesNormalized",      "/golddata/isaac/bib-venues.tsv",     stringEvaluator(bibVenuesExtractor, normalizer = normalize)),
      Metric("bibYears",                 "/golddata/isaac/bib-years.tsv",      stringEvaluator(bibYearsExtractor, disallow = Set("0")))
    )


    //
    // read gold data
    //

    val allGoldData = metrics.flatMap { metric =>
      Resource.using(Source.fromInputStream(getClass.getResourceAsStream(metric.goldFile))(Codec.UTF8)) { source =>
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
      val parser = new Parser()
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

    println(f"""${"EVALUATION RESULTS"}%-30s\t${"PRECISION"}%10s\t${"RECALL"}%10s""")
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
      println(f"${metric.name}%-30s\t$p%10.3f\t$r%10.3f")
    }
  }
}
