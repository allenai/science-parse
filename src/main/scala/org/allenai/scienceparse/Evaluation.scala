package org.allenai.scienceparse

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import org.allenai.common.{ Logging, Resource }
import org.allenai.datastore.Datastores
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import scopt.OptionParser

import scala.collection.{ GenMap, GenTraversableOnce }
import scala.collection.JavaConverters._
import scala.io.{ Codec, Source }
import scala.util.{ Failure, Try, Success }

object Evaluation extends Datastores with Logging {
  def normalize(s: String) = {
    StringUtils.normalize(PDFToCRFInput.tokenize(s).asScala.mkString(" "))
  }

  /** This class saves the original representation of an item, so that even if said item is mangled
    * into an unrecognizable form for the sake of comparison, its original readable form is still
    * available.
    */
  private case class ItemWithOriginal[T](item: T, original: String) {
    override def equals(o: Any) = o.equals(item)
    override def hashCode = item.hashCode
    def map(f: T => T) = ItemWithOriginal(f(item), original)
  }

  private object ItemWithOriginal {
    def create[T](item: T): ItemWithOriginal[T] = ItemWithOriginal(item, item.toString) // by default, just save original
    def toList(items: List[String]) = items.map(ItemWithOriginal.create)
  }

  //
  // Some helpers
  //

  private implicit class BufferToItemToCompareList[T](x: scala.collection.mutable.Buffer[T]) {
    def toItems = x.toList.toItems
  }

  private implicit class ListToItemToCompareList[T](x: List[T]) {
    def toItems = x.filter(_ != null).map(ItemWithOriginal.create)
  }

  private implicit class UtilListToItemToCompareList[T](x: java.util.List[T]) {
    def mapItems[U](f: T => U) = x.asScala.map(f).toItems
    def flatMapItems[U, That](f: T => GenTraversableOnce[U]) = x.asScala.flatMap(f).toItems
    def toItems = x.asScala.toItems
    def toList = x.asScala.toList
  }

  private def logEvaluationErrors[T](
    logger: Logger,
    metric: Metric,
    paperId: String,
    errorType: String,
    items: Set[ItemWithOriginal[T]]
  ): Unit = {
    items.foreach { item =>
      logger.info(s"${metric.name}\t$errorType\t$paperId\t${item.item}\t${item.original}")
    }
  }

  //
  // define metrics
  //

  private def normalizeBR(bibRecord: BibRecord) = {
    def nullMap[In, Out](itemOrNull: In, f: (In => Out)): Out =
      Option(itemOrNull).map(f).getOrElse(null.asInstanceOf[Out])

    new BibRecord(
        nullMap(bibRecord.title, normalize),
        bibRecord.author.asScala.map(normalize).asJava,
        nullMap(bibRecord.venue, normalize),
        bibRecord.citeRegEx,
        bibRecord.shortCiteRegEx,
        bibRecord.year)
  }

  private def normalizeBRstripVenues(bibRecord: BibRecord) = {
    val normalized = normalizeBR(bibRecord)

    new BibRecord(
      normalized.title,
      normalized.author,
      null,
      normalized.citeRegEx,
      normalized.shortCiteRegEx,
      normalized.year)
  }

  private def strictNormalize(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]", "")

  // Strip everything except for text and numbers out so that minor differences in
  // whitespace/mathematical equations won't affect results much
  private def mentionNormalize(s: String) = s.split("\\|").map(strictNormalize).mkString("|")

  private def calculatePR[T](
    metric: Metric,
    paperId: String,
    goldData: Set[ItemWithOriginal[T]],
    extractedData: Set[ItemWithOriginal[T]],
    logger: Option[Logger] = None
  ) = {
    if (goldData.isEmpty) {
      (if (extractedData.isEmpty) 1.0 else 0.0, 1.0)
    } else if (extractedData.isEmpty) {
      (0.0, 0.0)
    } else {
      logger.foreach { l =>
        logEvaluationErrors(l, metric, paperId, "recall", goldData.diff(extractedData))
        logEvaluationErrors(l, metric, paperId, "precision", extractedData.diff(goldData))
      }
      val precision = extractedData.count(goldData.contains).toDouble / extractedData.size
      val recall = goldData.count(extractedData.contains).toDouble / goldData.size
      (precision, recall)
    }
  }

  /** Just count the number of bib entries we're getting */
  private def bibCounter(
    metric: Metric,
    paperId: String,
    goldData: Set[ItemWithOriginal[BibRecord]],
    extractedData: Set[ItemWithOriginal[BibRecord]],
    logger: Option[Logger] = None
  ) = (1.0, extractedData.size.toDouble / goldData.size) // since we're not doing matching, just assume 100% precision

  /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get
    * three, that’s prec=1.0 but rec=0.6. Just add index # to name for simplicity. This is redundant
    * when everything is already unique, so you can basically always apply it.
    */
  private def multiSet(refs: List[ItemWithOriginal[String]]) =
    refs.groupBy(identity).values.flatMap(_.zipWithIndex.map {
      case (ref, i) =>
        ref.map(_ + i.toString)
    }).toSet

  /** This generates an evaluator for string metadata
    *
    * @param extract      Given automatically ExtractedMetadata from a paper, how do we get the
    *                     field we want to compare against gold data?
    * @param extractGold  Given the set of tab-delimited gold data, how do we get the field we want
    *                     to compare extracted metadata against?
    * @param normalizer   A function that normalizes extracted strings in some way for a more fair
    *                     comparison of quality
    * @param disallow     A set of junk/null values that will be filtered out from the comparison
    * @param prCalculator Function that calculates precision and recall of extraction against gold
    * @return A function that evaluates the quality of extracted metadata, compared to manually
    *         labeled gold metadata
    */
  private def stringEvaluator(
    extract: ExtractedMetadata => List[ItemWithOriginal[String]],
    extractGold: List[String] => List[ItemWithOriginal[String]] = ItemWithOriginal.toList,
    normalizer: String => String = identity, disallow: Set[String] = Set(""),
    prCalculator: (Metric, String, Set[ItemWithOriginal[String]], Set[ItemWithOriginal[String]], Option[Logger]) => (Double, Double) = calculatePR
  ) = (
    metric: Metric,
    paperId: String,
    metadata: ExtractedMetadata,
    gold: List[String],
    logger: Option[Logger]
  ) => {
    // function to clean up both gold and extracted data before we pass it in
    def clean(x: List[ItemWithOriginal[String]]) = {
      val normalizedItems = x.map(_.map(normalizer))
      val filteredItems = normalizedItems.filterNot(i => disallow.contains(i.item))
      multiSet(filteredItems)
    }
    prCalculator(metric, paperId, clean(extractGold(gold)), clean(extract(metadata)), logger)
  }

  private def genericEvaluator[T](
    extract: ExtractedMetadata => List[ItemWithOriginal[T]],
    extractGold: List[String] => List[ItemWithOriginal[T]],
    normalizer: T => T,
    prCalculator: (Metric, String, Set[ItemWithOriginal[T]], Set[ItemWithOriginal[T]], Option[Logger]) => (Double, Double)
  ) = (
    metric: Metric,
    paperId: String,
    metadata: ExtractedMetadata,
    gold: List[String],
    logger: Option[Logger]
  ) => {
    prCalculator(
      metric,
      paperId,
      extractGold(gold).map(_.map(normalizer)).toSet,
      extract(metadata).map(_.map(normalizer)).toSet,
      logger
    )
  }

  private def fullNameExtractor(metadata: ExtractedMetadata) = metadata.authors.toItems

  private val suffixes = Set("Jr.")

  private def getLastName(name: String) =
    name.split("\\s+").filterNot(suffixes.contains).last

  private def lastNameExtractor(metadata: ExtractedMetadata) =
    metadata.authors.mapItems(getLastName)

  private def lastNameGoldExtractor(names: List[String]) = names.map { fullName =>
    ItemWithOriginal(getLastName(fullName), fullName)
  }

  private def titleExtractor(metadata: ExtractedMetadata) =
    (Set(metadata.title) - null).toList.toItems

  private def firstNLastWord(x: String) = {
    val words = x.split("\\s+")
    words.head + " " + words.last
  }

  private def abstractExtractor(metadata: ExtractedMetadata) =
    if (metadata.abstractText == null)
      List()
    else
      List(ItemWithOriginal(firstNLastWord(metadata.abstractText), metadata.abstractText))

  private def goldAbstractExtractor(abs: List[String]) =
    List(ItemWithOriginal(firstNLastWord(abs.head), abs.head))

  private def bibExtractor(metadata: ExtractedMetadata) = metadata.references.toItems

  private def goldBibExtractor(refs: List[String]) = refs.map { ref =>
    val Array(title, year, venue, authors) = ref.split("\\|", -1)
    ItemWithOriginal(
      new BibRecord(
        title,
        authors.split(":").toList.asJava,
        venue,
        null,
        null,
        year.toInt
      ),
      ref
    )
  }

  private def bibAuthorsExtractor(metadata: ExtractedMetadata) =
    metadata.references.flatMapItems(_.author.toList)

  private def goldBibAuthorsExtractor(bibAuthors: List[String]) =
    bibAuthors.flatMap(_.split(":").toList).toItems

  private def bibTitlesExtractor(metadata: ExtractedMetadata) =
    metadata.references.mapItems(_.title).filter(_ != null)

  private def bibVenuesExtractor(metadata: ExtractedMetadata) =
    metadata.references.mapItems(_.venue).filter(_ != null)

  private def bibYearsExtractor(metadata: ExtractedMetadata) =
    metadata.references.flatMapItems { r =>
      val year = r.year
      if(year == 0) None else Some(year.toString)
    }

  private def bibMentionsExtractor(metadata: ExtractedMetadata) =
    metadata.referenceMentions.toList.map { r =>
      val context = r.context
      val mention = context.substring(r.startOffset, r.endOffset)
      ItemWithOriginal(s"""$context|${mention.replaceAll("[()]", "")}""", s"$context|$mention")
    }

  case class Metric(
    name: String,
    goldFile: String,
    // get P/R values for each individual paper. values will be averaged later across all papers
    evaluator: (Metric, String, ExtractedMetadata, List[String], Option[Logger]) => (Double, Double)
  )

  // to get a new version of Isaac's gold data into this format, run
  // src/it/resources/golddata/isaac/import_bib_gold.py inside the right scholar directory
  // format: OFF
  val metrics = Seq(
    Metric("authorFullName",           "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor)),
    Metric("authorFullNameNormalized", "/golddata/dblp/authorFullName.tsv",  stringEvaluator(fullNameExtractor, normalizer = normalize)),
    Metric("authorLastName",           "/golddata/dblp/authorFullName.tsv",  stringEvaluator(lastNameExtractor, lastNameGoldExtractor)),
    Metric("authorLastNameNormalized", "/golddata/dblp/authorFullName.tsv",  stringEvaluator(lastNameExtractor, lastNameGoldExtractor, normalizer = normalize)),
    Metric("title",                    "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor)),
    Metric("titleNormalized",          "/golddata/dblp/title.tsv",           stringEvaluator(titleExtractor, normalizer = normalize)),
    Metric("abstract",                 "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor)),
    Metric("abstractNormalized",       "/golddata/isaac/abstracts.tsv",      stringEvaluator(abstractExtractor, goldAbstractExtractor, normalize)),
    Metric("bibAll",                   "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, identity, calculatePR)), // gold from scholar
    Metric("bibAllNormalized",         "/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, normalizeBR, calculatePR)),
    Metric("bibAllButVenuesNormalized","/golddata/isaac/bibliographies.tsv", genericEvaluator[BibRecord](bibExtractor, goldBibExtractor, normalizeBRstripVenues, calculatePR)),
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
  // format: ON

  lazy val allGoldData = metrics.flatMap { metric =>
    Resource.using(Source.fromInputStream(getClass.getResourceAsStream(metric.goldFile))(Codec.UTF8)) { source =>
      source.getLines().map { line =>
        val fields = line.trim.split("\t").map(_.trim)
        (metric, fields.head, fields.tail.toList)
      }.toList
    }
  }
  // allGoldData is a Seq[(Metric, DocId, Set[Label])]

  lazy val goldDocIds = allGoldData.map(_._2).toSet

  case class EvaluationStats(precision: Double, recall: Double, evaluationSetSize: Int) {
    def p = precision
    def r = recall
  }

  object EvaluationStats {
    val empty = EvaluationStats(0, 0, 0)
  }

  case class EvaluationResult(
    scienceParse: Map[Metric, EvaluationStats],
    grobid: Map[Metric, EvaluationStats]
  )

  def main(args: Array[String]): Unit = {
    case class Config(
      modelFile: Option[File] = None,
      gazetteerFile: Option[File] = None
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      opt[File]('m', "model") action { (m, c) =>
        c.copy(modelFile = Some(m))
      } text "Specifies the model file to evaluate. Defaults to the production model"

      opt[File]('g', "gazetteer") action { (g, c) =>
        c.copy(gazetteerFile = Some(g))
      } text "Specifies the gazetteer file. Defaults to the production one. Take care not to use a gazetteer that you also used to train the model."

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val modelFile = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      val gazetteerFile = config.gazetteerFile.map(_.toPath).getOrElse(Parser.getDefaultGazetteer)

      val parser = new Parser(modelFile, gazetteerFile)
      val results = evaluate(parser)
      printResults(results)
    }
  }

  def evaluate(parser: Parser): EvaluationResult = {

    //
    // read gold data
    //

    val allGoldData = metrics.flatMap { metric =>
      Resource.using(Source.fromInputStream(getClass.getResourceAsStream(metric.goldFile))(Codec.UTF8)) { source =>
        source.getLines().map { line =>
          val fields = line.trim.split("\t").map(_.trim)
          (metric, fields.head, fields.tail.toList)
        }.toList
      }
    }

    //
    // download the documents and run extraction
    //

    val grobidExtractions = {
      val grobidExtractionsDirectory = publicDirectory("GrobidExtractions", 1)
      goldDocIds.par.map { docid =>
        val grobidExtraction = grobidExtractionsDirectory.resolve(s"$docid.xml")
        docid -> Success(GrobidParser.parseGrobidXml(grobidExtraction))
      }.toMap
    }

    val scienceParseExtractions = {
      val pdfDirectory = publicDirectory("PapersTestSet", 3)

      val documentCount = goldDocIds.size
      logger.info(s"Running on $documentCount documents")

      val totalDocumentsDone = new AtomicInteger()
      val startTime = System.currentTimeMillis()

      val result = goldDocIds.par.map { docid =>
        val pdf = pdfDirectory.resolve(s"$docid.pdf")
        val result = Resource.using(Files.newInputStream(pdf)) { is =>
          docid -> Try(parser.doParse(is))
        }

        val documentsDone = totalDocumentsDone.incrementAndGet()
        if (documentsDone % 50 == 0) {
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

      // error report
      val failures = result.values.collect { case Failure(e) => e }
      val errorRate = 100.0 * failures.size / documentCount
      logger.info(f"Failed ${failures.size} times ($errorRate%.2f%%)")
      if (failures.nonEmpty) {
        logger.info("Top errors:")
        failures.
          groupBy(_.getClass.getName).
          mapValues(_.size).
          toArray.
          sortBy(-_._2).
          take(10).
          foreach {
            case (error, count) =>
              logger.info(s"$count\t$error")
          }
      }

      result
    }

    //
    // calculate precision and recall for all metrics
    //

    def getPR(
      extractions: GenMap[String, Try[ExtractedMetadata]],
      logger: Option[Logger] = None
    ) = {
      val prResults = allGoldData.map {
        case (metric, docId, goldData) =>
          extractions(docId) match {
            case Failure(_) => (metric, (0.0, 0.0))
            case Success(extractedMetadata) =>
              (metric, metric.evaluator(metric, docId, extractedMetadata, goldData, logger))
          }
      }
      prResults.groupBy(_._1).mapValues { prs =>
        val (ps, rs) = prs.map(_._2).unzip
        EvaluationStats(ps.sum / ps.size, rs.sum / rs.size, ps.size)
      }
    }

    val evaluationErrorLogger =
      LoggerFactory.getLogger(s"${this.getClass.getCanonicalName}.evaluationErrors")
    EvaluationResult(
      getPR(scienceParseExtractions, Some(evaluationErrorLogger)),
      getPR(grobidExtractions)
    )
  }

  def printResults(results: EvaluationResult): Unit = {
    // buffer output so that console formatting doesn't get messed up
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    output += f"""${"EVALUATION RESULTS"}%-30s${"PRECISION"}%28s${"RECALL"}%28s${"SAMPLE"}%10s"""
    output += f"""${""}%-30s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%6s${"SP"}%10s | ${"Grobid"}%6s | ${"diff"}%6s${"SIZE"}%10s"""
    output += "-----------------------------------------+--------+------------------+--------+-----------------"
    (results.scienceParse.keySet ++ results.grobid.keySet).toList.sortBy(_.name).foreach { metric =>
      val EvaluationStats(spP, spR, spSize) =
        results.scienceParse.getOrElse(metric, EvaluationStats.empty)
      val EvaluationStats(grobidP, grobidR, grobidSize) =
        results.grobid.getOrElse(metric, EvaluationStats.empty)
      assert(spSize == 0 || grobidSize == 0 || spSize == grobidSize)
      val size = Seq(spSize, grobidSize).max

      val pDiff = spP - grobidP
      val rDiff = spR - grobidR
      output += f"${metric.name}%-30s$spP%10.3f | $grobidP%6.3f | $pDiff%+5.3f$spR%10.3f | $grobidR%6.3f | $rDiff%+5.3f$size%10d"
    }
    println(output.map(line => s"${Console.BOLD}${Console.BLUE}$line${Console.RESET}").mkString("\n"))
  }
}
