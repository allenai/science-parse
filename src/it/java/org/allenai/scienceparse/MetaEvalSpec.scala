package org.allenai.scienceparse

import java.io.{File, PrintWriter}
import java.time.LocalDate
import java.util.{Calendar, Date}

import org.allenai.common.{StringUtils, Logging, Resource}
import org.allenai.common.testkit.UnitSpec
import org.allenai.common.StringUtils._
import org.allenai.scienceparse.GrobidParser.JsoupElementsImplicits
import org.allenai.datastore.Datastores

import org.jsoup.Jsoup
import org.jsoup.nodes.{ TextNode, Element }
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.GenMap
import scala.collection.parallel.ParMap
import scala.io.{Codec, Source}
import scala.util.{Success, Failure, Try}
import scala.collection.JavaConverters._


/** This class saves the original representation of an item, so that even if said item is mangled into an unrecognizable
  * form for the sake of comparison, its original readable form is still available.
  */
case class ItemToCompare[T](item: T, original: String) {
  override def equals(o: Any) = o.equals(item)
  override def hashCode = item.hashCode
  def map(f: T => T) = new ItemToCompare(f(item), original)
}

object ItemToCompare {
  def create[T](item: T): ItemToCompare[T] = new ItemToCompare(item, item.toString) // by default, just save original
  def toList(items: List[String]) = items.map(ItemToCompare.create)
}

class MetaEvalSpec extends UnitSpec with Datastores with Logging {
  "MetaEval" should "produce good P/R numbers" in {
    val maxDocumentCount = 1000 // set this to something low for testing, set it high before committing
    val evaluateGrobid = true // get numbers for Grobid instead

    val errorWriter = new PrintWriter(new File("MetaEvalErrors.tsv" ))
    errorWriter.println("Metric\tError type\tPaper ID\tItem\tOriginal")

    // Information about what we're evaluating
    case class EvaluationInfo(metric: Metric, paperId: String) {
      def error[T](errorType: String, i: ItemToCompare[T]) =
        errorWriter.println(s"${metric.name}\t$errorType\t$paperId\t${i.item}\t${i.original}")
      def errors[T](errorType: String, items: Set[ItemToCompare[T]]) = items.foreach(error(errorType, _))
    }

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

    def strictNormalize(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]", "")

    // Strip everything except for text and numbers out so that minor differences in whitespace/mathematical equations
    // won't affect results much
    def mentionNormalize(s: String) = s.split("\\|").map(strictNormalize).mkString("|")

    def calculatePR[T](eval: EvaluationInfo, goldData: Set[ItemToCompare[T]], extractedData: Set[ItemToCompare[T]]) = {
      if (goldData.isEmpty) {
        (if (extractedData.isEmpty) 1.0 else 0.0, 1.0)
      } else if (extractedData.isEmpty) {
        (0.0, 0.0)
      } else {
        eval.errors("recall",    goldData.diff(extractedData))
        eval.errors("precision", extractedData.diff(goldData))
        val precision = extractedData.count(goldData.contains).toDouble / extractedData.size
        val recall = goldData.count(extractedData.contains).toDouble / goldData.size
        (precision, recall)
      }
    }

    /** Just count the number of bib entries we're getting */
    def bibCounter(eval: EvaluationInfo, goldData: Set[ItemToCompare[BibRecord]], extractedData: Set[ItemToCompare[BibRecord]]) =
      (1.0, extractedData.size.toDouble / goldData.size) // since we're not doing matching, just assume 100% precision

    /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get three, that’s prec=1.0
      * but rec=0.6. Just add index # to name for simplicity. This is redundant when everything is already unique, so
      * you can basically always apply it
      */
    def multiSet(refs: List[ItemToCompare[String]]) = refs.groupBy(identity).values.flatMap(_.zipWithIndex.map { case (ref, i) =>
      ref.map(_ + i.toString)
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
    def stringEvaluator(extract: ExtractedMetadata => List[ItemToCompare[String]],
                        extractGold: List[String] => List[ItemToCompare[String]] = ItemToCompare.toList,
                        normalizer: String => String = identity, disallow: Set[String] = Set(""),
                        prCalculator: (EvaluationInfo, Set[ItemToCompare[String]], Set[ItemToCompare[String]]) => (Double, Double) = calculatePR) =
      (eval: EvaluationInfo, metadata: ExtractedMetadata, gold: List[String]) => {
        // function to clean up both gold and extracted data before we pass it in
        val clean = (x: List[ItemToCompare[String]]) => {
          val normalizedItems = x.map(_.map(normalizer))
          val filteredItems = normalizedItems.filterNot(i => disallow.contains(i.item))
          multiSet(filteredItems)
        }
        prCalculator(eval, clean(extractGold(gold)), clean(extract(metadata)))
      }

    def genericEvaluator[T](extract: ExtractedMetadata => List[ItemToCompare[T]], extractGold: List[String] => List[ItemToCompare[T]],
                                normalizer: T => T,
                                prCalculator: (EvaluationInfo, Set[ItemToCompare[T]], Set[ItemToCompare[T]]) => (Double, Double)) =
      (eval: EvaluationInfo, metadata: ExtractedMetadata, gold: List[String]) => {
        prCalculator(eval, extractGold(gold).map(_.map(normalizer)).toSet, extract(metadata).map(_.map(normalizer)).toSet)
      }

    def fullNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.toList.map(ItemToCompare.create)

    def lastNameExtractor(metadata: ExtractedMetadata) = metadata.authors.asScala.map(_.split("\\s+").last).toList.map(ItemToCompare.create)

    def titleExtractor(metadata: ExtractedMetadata) = (Set(metadata.title) - null).toList.map(ItemToCompare.create)

    def firstNLastWord(x: String) = {
      val words = x.split("\\s+")
      words.head + " " + words.last
    }

    def abstractExtractor(metadata: ExtractedMetadata) =
      if (metadata.abstractText == null) List() else List(ItemToCompare(firstNLastWord(metadata.abstractText), metadata.abstractText))

    def goldAbstractExtractor(abs: List[String]) = List(ItemToCompare(firstNLastWord(abs.head), abs.head))

    def bibExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.toList.map(ItemToCompare.create)

    def goldBibExtractor(refs: List[String]) = refs.map { ref =>
      val Array(title, year, venue, authors) = ref.split("\\|", -1)
      ItemToCompare(new BibRecord(title, authors.split(":").toList.asJava, venue, null, null, year.toInt), ref)
    }

    def bibAuthorsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.flatMap(_.author.asScala.toList).toList.map(ItemToCompare.create)

    def goldBibAuthorsExtractor(bibAuthors: List[String]) = bibAuthors.flatMap(_.split(":").toList).map(ItemToCompare.create)

    def bibTitlesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.title).toList.map(ItemToCompare.create)

    def bibVenuesExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.venue).toList.map(ItemToCompare.create)

    def bibYearsExtractor(metadata: ExtractedMetadata) = metadata.references.asScala.map(_.year.toString).toList.map(ItemToCompare.create)

    def bibMentionsExtractor(metadata: ExtractedMetadata) = metadata.referenceMentions.asScala.map { r =>
      val context = r.context
      val mention = context.substring(r.startOffset, r.endOffset)
      ItemToCompare(s"""$context|${mention.replaceAll("[()]", "")}""", s"$context|$mention")
    }.toList

    case class Metric(
      name: String,
      goldFile: String,
      // get P/R values for each individual paper. values will be averaged later across all papers
      evaluator: (EvaluationInfo, ExtractedMetadata, List[String]) => (Double, Double))
    // to get a new version of Isaac's gold data into this format, run src/it/resources/golddata/isaac/import_bib_gold.py
    // inside the right scholar directory
    val metrics = Seq(
      Metric("authorFullName",           "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor)),
      Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor, normalizer = normalize)),
      Metric("authorLastName",           "/golddata/dblp/authorLastName.tsv",  stringEvaluator(lastNameExtractor)),
      Metric("authorLastNameNormalized", "/golddata/dblp/authorLastName.tsv",  stringEvaluator(lastNameExtractor, normalizer = normalize)),
      Metric("title",                    "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor)),
      Metric("titleNormalized",          "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor, normalizer = normalize)),
      Metric("abstract",                 "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor)),
      Metric("abstractNormalized",       "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor, normalize)),
      Metric("bibAll",                   "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, calculatePR)), // gold from scholar
      Metric("bibAllNormalized",         "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, normalizeBR, calculatePR)),
      Metric("bibCounts",                "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, bibCounter)),
      Metric("bibAuthors",               "/golddata/isaac/bib-authors.tsv",    stringEvaluator(bibAuthorsExtractor, goldBibAuthorsExtractor)),
      Metric("bibAuthorsNormalized",     "/golddata/isaac/bib-authors.tsv",    stringEvaluator(bibAuthorsExtractor, goldBibAuthorsExtractor, normalize)),
      Metric("bibTitles",                "/golddata/isaac/bib-titles.tsv",     stringEvaluator(bibTitlesExtractor)),
      Metric("bibTitlesNormalized",      "/golddata/isaac/bib-titles.tsv",     stringEvaluator(bibTitlesExtractor, normalizer = normalize)),
      Metric("bibVenues",                "/golddata/isaac/bib-venues.tsv",     stringEvaluator(bibVenuesExtractor)),
      Metric("bibVenuesNormalized",      "/golddata/isaac/bib-venues.tsv",     stringEvaluator(bibVenuesExtractor, normalizer = normalize)),
      Metric("bibYears",                 "/golddata/isaac/bib-years.tsv",      stringEvaluator(bibYearsExtractor, disallow = Set("0"))),
      Metric("bibMentions",              "/golddata/isaac/mentions.tsv",       stringEvaluator(bibMentionsExtractor)),
      Metric("bibMentionsNormalized",    "/golddata/isaac/mentions.tsv",       stringEvaluator(bibMentionsExtractor, normalizer = mentionNormalize))
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

    val grobidExtractions = {
      val grobidExtractionsDirectory = publicDirectory("GrobidExtractions", 1)
      docIds.par.map { docid =>
        val grobidExtraction = grobidExtractionsDirectory.resolve(s"$docid.xml")
        docid -> Success(GrobidParser.parseGrobidXml(grobidExtraction))
      }.toMap
    }

    val scienceParseExtractions = {
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

    def getPR(extractions: GenMap[String, Try[ExtractedMetadata]]) = {
      val prResults = allGoldData.map { case (metric, docId, goldData) =>
        extractions(docId) match {
          case Failure(_) => (metric, (0.0, 0.0))
          case Success(extractedMetadata) =>
            (metric, metric.evaluator(EvaluationInfo(metric, docId), extractedMetadata, goldData))
        }
    }
    prResults.groupBy(_._1).mapValues { prs =>
      val (ps, rs) = prs.map(_._2).unzip
      (ps.sum / ps.size, rs.sum / rs.size)
      }.toList.sortBy(_._1.name)
    }

    val spPR = getPR(scienceParseExtractions)
    val grobidPR = getPR(grobidExtractions)
    // buffer output so that console formatting doesn't get messed up
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    output += f"""${Console.BOLD}${Console.BLUE}${"EVALUATION RESULTS"}%-30s${"PRECISION"}%27s${"RECALL"}%27s"""
    output += f"""${""}%-30s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%5s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%5s"""
    output += "-----------------------------------------+--------+-----------------+--------+------"
    spPR.zip(grobidPR).foreach { case ((metric, (spP, spR)), (_, (grobidP, grobidR))) =>
      val pDiff = (spP - grobidP) * 100 / grobidP
      val rDiff = (spR - grobidR) * 100 / grobidR
      output += f"${metric.name}%-30s$spP%10.3f | $grobidP%6.3f | $pDiff%+4.0f%%$spR%10.3f | $grobidR%6.3f | $rDiff%+4.0f%%"
    }
    println(output.mkString("\n"))

    errorWriter.close()
  }
}
