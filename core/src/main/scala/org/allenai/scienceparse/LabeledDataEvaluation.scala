package org.allenai.scienceparse

import java.io.File
import java.util.concurrent.{Executors, TimeUnit}

import org.allenai.common.Logging
import org.allenai.common.ParIterator._
import java.net.URL

import opennlp.tools.tokenize.{TokenizerME, TokenizerModel}
import org.allenai.scienceparse.LabeledData.Reference
import org.allenai.scienceparse.pipeline.{SimilarityMeasures, TitleAuthors, Bucketizers => PipelineBucketizers, Normalizers => PipelineNormalizers}
import org.slf4j.LoggerFactory
import scopt.OptionParser

import scala.collection.immutable.Set
import scala.collection.mutable
import scala.concurrent.ExecutionContext

object LabeledDataEvaluation extends Logging {
  import StringUtils._

  val tokenizerModel =
    new TokenizerModel(this.getClass.getResourceAsStream("/opennlp/tools/tokenize/en-token.bin"))

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

  private def normalizeTitle(s: String) =
    normalize(s).trim.replaceAll("^\\p{P}", "").replaceAll("[\\p{P}&&[^)]]$", "")

  def main(args: Array[String]): Unit = {
    case class Config(
      modelFile: Option[File] = None,
      gazetteerFile: Option[File] = None,
      bibModelFile: Option[File] = None,
      grobidServerUrl: Option[String] = None,
      goldData: Iterator[LabeledPaper] = LabeledPapersFromResources.get
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      opt[File]('m', "model") action { (m, c) =>
        c.copy(modelFile = Some(m))
      } text "Specifies the model file to evaluate. Defaults to the production model."

      opt[File]('g', "gazetteer") action { (g, c) =>
        c.copy(gazetteerFile = Some(g))
      } text "Specifies the gazetteer file. Defaults to the production one. Take care not to use a gazetteer that you also used to train the model."

      opt[File]('b', "bibModel") action { (b, c) =>
        c.copy(bibModelFile = Some(b))
      } text "Specified the bibliography model file to evaluate. Defaults to the production model."

      opt[String]("grobidServerUrl") action { (url, c) =>
        c.copy(grobidServerUrl = Some(url))
      } text "URL for the Grobid Server. Defaults to http://localhost:8080."

      opt[Unit]("compareAgainstGold") action { (_, c) =>
        c.copy(goldData = LabeledPapersFromResources.get)
      } text "Compare against the gold data we have from Isaac. This is the default."

      opt[Unit]("compareAgainstPMC") action { (_, c) =>
        c.copy(goldData = LabeledPapersFromPMC.getCleaned.take(1000))
      } text "Compare against 1000 documents from PMC"

      opt[Unit]("compareAgainstPMC100") action { (_, c) =>
        c.copy(goldData = LabeledPapersFromPMC.getCleaned.take(100))
      } text "Compare against 100 documents from PMC"

      opt[Unit]("compareAgainstPMC10000") action { (_, c) =>
        c.copy(goldData = LabeledPapersFromPMC.getCleaned.take(10000))
      } text "Compare against 10000 documents from PMC"

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val labeledDataFromGrobidServer = {
        val grobidServerUrl = new URL(config.grobidServerUrl.getOrElse("http://localhost:8080"))
        new LabeledPapersFromGrobidServer(grobidServerUrl)
      }

      val executor = Executors.newFixedThreadPool(32)
      try {
        implicit val ec = ExecutionContext.fromExecutor(executor)

        // read the data
        logger.info("Reading in data ...")
        val goldData = config.goldData

        val parser = new Parser(
          config.modelFile.getOrElse(Parser.getDefaultProductionModel.toFile),
          config.gazetteerFile.getOrElse(Parser.getDefaultGazetteer.toFile),
          config.bibModelFile.getOrElse(Parser.getDefaultBibModel.toFile))

        val extractions = goldData.parMap { gold =>
          val grobid = labeledDataFromGrobidServer.get(gold.inputStream).labels
          val sp = LabeledPapersFromScienceParse.get(gold.inputStream, parser).labels
          (gold.labels, sp, grobid)
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

        val errorLogger = LoggerFactory.getLogger(internalLogger.getName + ".evalErrors")

        // calculate title metrics
        {
          logger.info("Calculating titlesNormalized ...")
          val titleMetricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
            gold.title.map(normalize).map { normalizedGoldTitle =>
              def score(scoredTitleOption: Option[String]) = scoredTitleOption match {
                case Some(scoredTitle) if normalize(scoredTitle) == normalizedGoldTitle => 1
                case _ => 0
              }

              val spScore = score(sp.title)
              val grobidScore = score(grobid.title)
              errorLogger.info(f"Score for titlesNormalized on ${gold.id}: SP: $spScore Grobid: $grobidScore Diff: ${spScore - grobidScore}%+d")
              (spScore, grobidScore)
            }
          }

          val count = titleMetricsPerDocument.size
          val spScore = titleMetricsPerDocument.map(_._1).sum / count.toDouble
          val grobidScore = titleMetricsPerDocument.map(_._2).sum / count.toDouble
          output += outputLine("titlesNormalized", PR(spScore, spScore), PR(grobidScore, grobidScore), count)
        }

        // infrastructure for metrics that all have the same format
        // Returns (SP PR, Grobid PR, document count)
        def evaluateMetric[T](metricName: String)(extract: LabeledData => Option[Iterable[T]]): (PR, PR, Int) = {
          /** Use multi-set to count repetitions -- if Etzioni is cited five times in gold, and we get
            * three, that’s prec=1.0 but rec=0.6. Just add index # to name for simplicity. This is redundant
            * when everything is already unique, so you can basically always apply it.
            */
          def multiSet[T2](refs: Iterable[T2]) =
            refs.groupBy(identity).values.flatMap(_.zipWithIndex).toSet

          val metricsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
            extract(gold).map { goldEntries =>
              // We only use multiset when we have to, to keep the error logs a little cleaner.
              val goldEntriesSet = goldEntries.toSet
              val goldEntriesMultiSet = multiSet(goldEntries)
              val haveToUseMultiSet = goldEntriesSet.size != goldEntriesMultiSet.size

              def logPRErrors(goldSet: Set[Any], resultSet: Set[Any]): Unit = {
                val missingEntries = (goldSet -- resultSet).toSeq.map(_.toString).sorted
                if(missingEntries.isEmpty) {
                  errorLogger.info(s"Missing for $metricName on ${gold.id}: None!")
                } else {
                  missingEntries.foreach { missingEntry =>
                    errorLogger.info(s"Missing for $metricName on ${gold.id}: ${makeSingleLine(missingEntry)}")
                  }
                }

                val excessEntries = (resultSet -- goldSet).toSeq.map(_.toString).sorted
                if(excessEntries.isEmpty) {
                  errorLogger.info(s"Excess  for $metricName on ${gold.id}: None!")
                } else {
                  excessEntries.foreach { excessEntry =>
                    errorLogger.info(s"Excess  for $metricName on ${gold.id}: ${makeSingleLine(excessEntry)}")
                  }
                }
              }

              def score(scoredOption: Option[Iterable[T]], logging: Boolean = false) =
                scoredOption match {
                  case None => PR(0.0, 0.0)
                  case Some(scored) =>
                    val scoredSet = scored.toSet
                    lazy val scoredMultiSet = multiSet(scored)

                    val (g: Set[Any], s: Set[Any]) = if(haveToUseMultiSet || scoredSet.size != scoredMultiSet.size) {
                      (goldEntriesMultiSet, scoredMultiSet)
                    } else {
                      (goldEntriesSet, scoredSet)
                    }

                    if(logging) logPRErrors(g, s)
                    PR.fromSets(g, s)
                }

              val spScore = score(extract(sp), logging = true)
              val grobidScore = score(extract(grobid))
              errorLogger.info(f"P for $metricName on ${gold.id}: SP: ${spScore.p}%1.3f Grobid: ${grobidScore.p}%1.3f Diff: ${spScore.p - grobidScore.p}%+1.3f")
              errorLogger.info(f"R for $metricName on ${gold.id}: SP: ${spScore.r}%1.3f Grobid: ${grobidScore.r}%1.3f Diff: ${spScore.r - grobidScore.r}%+1.3f")

              (spScore, grobidScore)
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
          val (sp, grobid, count) = evaluateMetric("authorFullNameNormalized") { labeledData =>
            labeledData.authors.map { as =>
              as.map(a => normalizeAuthor(a.name))
            }
          }
          output += outputLine("authorFullNameNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating authorLastNameNormalized ...")
          val (sp, grobid, count) = evaluateMetric("authorLastNameNormalized") { labeledData =>
            labeledData.authors.map { as =>
              as.map(a => normalize(getLastName(a.name)))
            }
          }
          output += outputLine("authorLastNameNormalized", sp, grobid, count)
        }

        // calculate bib metrics
        {
          logger.info("Calculating bibCount ...")

          val bibCountsPerDocument = extractions.flatMap { case (gold, sp, grobid) =>
            gold.references.flatMap { refs =>
              val goldCount = refs.size
              if(goldCount == 0) {
                None
              } else {
                val spCount = sp.references.map(_.size).getOrElse(0)
                val grobidCount = grobid.references.map(_.size).getOrElse(0)

                val spScore = spCount / goldCount.toDouble
                val grobidScore = grobidCount / goldCount.toDouble
                errorLogger.info(f"Score for bibCount on ${gold.id}: SP: $spScore%1.3f Grobid: $grobidScore%1.3f Diff: ${spScore - grobidScore}%+1.3f")

                Some((spScore, grobidScore))
              }
            }
          }

          val count = bibCountsPerDocument.size
          val spScore = bibCountsPerDocument.map(_._1).sum / count.toDouble
          val grobidScore = bibCountsPerDocument.map(_._2).sum / count.toDouble
          output += f"${"bibCount"}%-30s$spScore%10.3f | $grobidScore%6.3f |       $spScore%10.3f | $grobidScore%6.3f |       $count%10d"
        }

        {
          logger.info("Calculating bibAllNormalized ...")
          val (sp, grobid, count) = evaluateMetric("bibAllNormalized") { labeledData =>
            def normalizeReference(ref: Reference) = Reference(
              None, // ignoring label, because Grobid doesn't have that
              ref.title.map(normalizeTitle),
              ref.authors.map(normalizeAuthor),
              ref.venue.map(normalize),
              ref.year,
              None, // ignoring volume, because we didn't take that into consideration before
              None // ignoring pageRange, because we didn't take that into consideration before
            )

            labeledData.references.map { refs =>
              refs.map(normalizeReference)
            }
          }
          output += outputLine("bibAllNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating bibPipeline ...")
          val (sp, grobid, count) = {
            val metricsPerDocument = extractions.flatMap { case (gold, spExtraction, grobidExtraction) =>
              def buckets(ref: LabeledData.Reference) = {
                val title = PipelineNormalizers.truncateWords(ref.title.getOrElse(""))
                val titleBuckets =
                  PipelineBucketizers.titleNgrams(title, upto = 1, allowTruncated = false).toSet
                val lastNames = ref.authors.map(getLastName)
                val authorBuckets =
                  PipelineBucketizers.ngrams(lastNames.mkString(" "), 3, None)
                (authorBuckets ++ titleBuckets).toIterable
              }

              gold.references.map { goldRefs =>
                // This gnarly bit of code produces a map Bucket -> Seq[Refs].
                val bucketToGoldRef =
                  goldRefs.flatMap { r => buckets(r).map((_, r)) }.groupBy(_._1).mapValues(_.map(_._2))

                val Seq(spScore, grobidScore) = Seq(spExtraction, grobidExtraction).map { resultToScore =>
                  val loggingEnabled = resultToScore == spExtraction

                  def refAsString(ref: LabeledData.Reference) = {
                    val builder = new StringBuilder(128)
                    builder += '\"'
                    builder ++= ref.title.getOrElse("MISSING TITLE")
                    builder += '\"'
                    builder += ' '

                    ref.year.foreach { y =>
                      builder += '('
                      builder ++= y.toString
                      builder += ')'
                      builder += ' '
                    }

                    builder ++= "by ("
                    builder ++= ref.authors.mkString(", ")
                    builder += ')'

                    builder.toString()
                  }

                  resultToScore.references match {
                    case None =>
                      if(loggingEnabled) {
                        goldRefs.foreach { goldRef =>
                          errorLogger.info(s"Missing    for bibPipeline on ${gold.id}: ${refAsString(goldRef)}")
                        }
                      }
                      PR(0.0, 0.0)

                    case Some(docRefs) if goldRefs.isEmpty && docRefs.isEmpty =>
                      PR(1.0, 1.0)

                    case Some(docRefs) if goldRefs.isEmpty && docRefs.nonEmpty =>
                      if(loggingEnabled) {
                        docRefs.foreach { docRef =>
                          errorLogger.info(s"Excess     for bibPipeline on ${gold.id}: ${refAsString(docRef)}")
                        }
                      }
                      PR(0.0, 1.0)

                    case Some(docRefs) if goldRefs.nonEmpty && docRefs.isEmpty =>
                      if(loggingEnabled) {
                        goldRefs.foreach { goldRef =>
                          errorLogger.info(s"Missing    for bibPipeline on ${gold.id}: ${refAsString(goldRef)}")
                        }
                      }
                      PR(0.0, 0.0)

                    case Some(docRefs) if docRefs.nonEmpty =>
                      val unmatchedGoldRefs = mutable.Set(goldRefs:_*)

                      val scoresPerRef = docRefs.map { docRef =>
                        val potentialGoldMatches =
                          buckets(docRef).flatMap(bucket => bucketToGoldRef.getOrElse(bucket, Seq.empty)).toSet
                        val globalScoreThreshold = 0.94
                        val authorCheckThreshold = 0.8

                        // Just like the pipeline, we now score all the potential matches, and pick
                        // the highest one.
                        val docTitleAuthors = TitleAuthors.fromReference(docRef)
                        val scoreRefPairsForThisRef = potentialGoldMatches.toSeq.flatMap { potentialMatchingRef =>
                          val potentialMatchingTitleAuthors =
                            TitleAuthors.fromReference(potentialMatchingRef)

                          // This code is stolen almost verbatim from the pipeline project.
                          val matchingScoreOption =
                            SimilarityMeasures.titleNgramSimilarity(docTitleAuthors, potentialMatchingTitleAuthors) match {
                              case Some(titleMatchScore) if titleMatchScore > authorCheckThreshold =>
                                val authorMatchScore =
                                  if (
                                    docTitleAuthors.year.isDefined &&
                                    docTitleAuthors.year == potentialMatchingTitleAuthors.year
                                  ) {
                                    val lAuthors = docTitleAuthors.normalizedAuthors
                                    val rAuthors = potentialMatchingTitleAuthors.normalizedAuthors
                                    // subtract 0.01 because this should never be as good as a perfect title match
                                    ((lAuthors intersect rAuthors).size.toDouble / (lAuthors union rAuthors).size) - 0.01
                                  } else {
                                    0.0
                                  }
                                Some(math.max(titleMatchScore, authorMatchScore))
                              case s => s
                            }

                          matchingScoreOption.filter(_ > globalScoreThreshold).map((_, potentialMatchingRef))
                        }

                        val bestScoreRefPair = scoreRefPairsForThisRef.sortBy(-_._1).headOption
                        bestScoreRefPair match {
                          case None =>
                            if(loggingEnabled)
                              errorLogger.info(s"Excess     for pipPipeline on ${gold.id}: ${refAsString(docRef)}")
                            0.0
                          case Some((scoreForThisRef, matchedGoldRef)) =>
                            if(scoreForThisRef < 0.999 && loggingEnabled) {
                              errorLogger.info(f"Score $scoreForThisRef%.2f for bibPipeline on ${gold.id}: got  ${refAsString(docRef)}")
                              errorLogger.info(f"Score $scoreForThisRef%.2f for bibPipeline on ${gold.id}: gold ${refAsString(matchedGoldRef)}")
                            }
                            unmatchedGoldRefs -= matchedGoldRef
                            scoreForThisRef
                        }
                      }

                      if(loggingEnabled) {
                        unmatchedGoldRefs.foreach { goldRef =>
                          errorLogger.info(s"Missing    for bibPipeline on ${gold.id}: ${refAsString(goldRef)}")
                        }
                      }

                      val scoreForThisDoc = scoresPerRef.sum

                      PR(
                        scoreForThisDoc / goldRefs.length,
                        scoreForThisDoc / docRefs.length)
                  }
                }

                errorLogger.info(f"P for bibPipeline on ${gold.id}: SP: ${spScore.p}%1.3f Grobid: ${grobidScore.p}%1.3f Diff: ${spScore.p - grobidScore.p}%+1.3f")
                errorLogger.info(f"R for bibPipeline on ${gold.id}: SP: ${spScore.r}%1.3f Grobid: ${grobidScore.r}%1.3f Diff: ${spScore.r - grobidScore.r}%+1.3f")

                (spScore, grobidScore)
              }
            }

            (
              PR.average(metricsPerDocument.map(_._1)),
              PR.average(metricsPerDocument.map(_._2)),
              metricsPerDocument.size
            )
          }
          output += outputLine("bibPipeline", sp, grobid, count)
        }

        {
          logger.info("Calculating bibAllButVenuesNormalized ...")
          val (sp, grobid, count) = evaluateMetric("bibAllButVenuesNormalized") { labeledData =>
            def normalizeReference(ref: Reference) = Reference(
              None, // ignoring label, because Grobid doesn't have that
              ref.title.map(normalizeTitle),
              ref.authors.map(normalizeAuthor),
              None, // ignoring venue
              ref.year,
              None, // ignoring volume, because we didn't take that into consideration before
              None // ignoring pageRange, because we didn't take that into consideration before
            )

            labeledData.references.map { refs =>
              refs.map(normalizeReference)
            }
          }
          output += outputLine("bibAllButVenuesNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating bibTitlesNormalized ...")
          val (sp, grobid, count) = evaluateMetric("bibTitlesNormalized") { labeledData =>
            labeledData.references.map { refs =>
              refs.flatMap(_.title).map(normalizeTitle)
            }
          }
          output += outputLine("bibTitlesNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating bibAuthorsNormalized ...")
          val (sp, grobid, count) = evaluateMetric("bibAuthorsNormalized") { labeledData =>
            labeledData.references.map { refs =>
              refs.flatMap(_.authors).map(normalizeAuthor)
            }
          }
          output += outputLine("bibAuthorsNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating bibVenuesNormalized ...")
          val (sp, grobid, count) = evaluateMetric("bibVenuesNormalized") { labeledData =>
            labeledData.references.map { refs =>
              refs.flatMap(_.venue).map(normalize)
            }
          }
          output += outputLine("bibVenuesNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating bibYears ...")
          val (sp, grobid, count) = evaluateMetric("bibYears") { labeledData =>
            labeledData.references.map { refs =>
              refs.flatMap(_.year)
            }
          }
          output += outputLine("bibYears", sp, grobid, count)
        }

        // calculate abstract metrics
        {
          logger.info("Calculating abstractNormalized ...")
          val (sp, grobid, count) = evaluateMetric("abstractNormalized") { labeledData =>
            labeledData.abstractText.map(a => Iterable(normalize(a)))
          }

          output += outputLine("abstractNormalized", sp, grobid, count)
        }

        // calculate sections metrics
        {
          logger.info("Calculating sectionsNormalized ...")
          val (sp, grobid, count) = evaluateMetric("sectionsNormalized") { labeledData =>
            labeledData.sections.map(_.map { s =>
              val heading = s.heading.map(h => normalize(h) + " : ").getOrElse("")
              val body = normalize(s.text)
              heading + body
            })
          }

          output += outputLine("sectionsNormalized", sp, grobid, count)
        }

        {
          logger.info("Calculating sectionTokensNormalized ...")
          val tokenizer = new TokenizerME(tokenizerModel)
          val (sp, grobid, count) = evaluateMetric("sectionsTokensNormalized") { labeledData =>
            labeledData.sections.map(_.flatMap { s =>
              val heading = s.heading.map(h => normalize(h) + " : ").getOrElse("")
              val body = normalize(s.text)
              tokenizer.tokenize(heading + body)
            })
          }

          output += outputLine("sectionsTokensNormalized", sp, grobid, count)
        }

        println(output.map(line => s"${Console.BOLD}${Console.BLUE}$line${Console.RESET}").mkString("\n"))
      } finally {
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.MINUTES)
      }
    }
  }
}
