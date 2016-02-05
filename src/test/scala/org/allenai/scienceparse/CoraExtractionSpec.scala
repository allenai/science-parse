package org.allenai.scienceparse

import org.allenai.common.Resource
import org.allenai.common.testkit.UnitSpec

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

import org.scalatest._
import Matchers._

class CoraExtractionSpec extends UnitSpec {

  case class Reference(
    source: String,
    authors: Seq[String],
    title: String,
    date: String
  )

  case class TestResult(
    reference: Reference,
    extracted: Seq[BibRecord],
    precision: Float,
    recall: Float,
    msg: Seq[String] = Seq()
  )

  case class TestResults(
    precision: Float,
    recall: Float,
    results: Seq[TestResult]
  )

  val refs = new ArrayBuffer[Reference]()
  val extractor = new ExtractReferences(Parser.getDefaultGazetteer.toString)

  Resource.using(
    Source.fromInputStream(getClass.getResourceAsStream("/tagged_references.txt"))
  ) {
      source =>
        for (
          ref <- source.getLines
        ) {
          val authorMatch = "<author>(.*)</author>".r.findFirstMatchIn(ref)
          val authors = authorMatch
            .toSeq
            .flatMap(_.group(1).split(",|and|&"))
            .map(_.trim)

          val title = "<title>(.*)</title>".r.findFirstMatchIn(ref).map(_.group(1).trim)
          val date = "<date>(.*)</date>".r.findFirstMatchIn(ref).map(_.group(1).trim)
          val raw = ref.replaceAll("<[^>]+>", "").replaceAll("</[^>]+>", "").trim
          refs.append(Reference(raw, authors, title.getOrElse(""), date.getOrElse("")))
        }
    }

  // Successful as long as we got exactly one record.
  def segmentationTest(ref: Reference, extracted: Seq[BibRecord]): TestResult = {
    TestResult(ref, extracted, 1, 1)
  }

  def runTest(name: String, test: (Reference, Seq[BibRecord]) => TestResult): TestResults = {
    def testRecord(ref: Reference): TestResult = {
      val text = Seq("Bibliography", ref.source).asJava
      val records = extractor.findReferences(text).getOne.asScala
      if (records.size == 0) {
        println(s"Missed extraction: ${ref.source}")
        TestResult(ref, records, 0, 0, Seq("Missing"))
      } else if (records.size > 1) {
        TestResult(ref, records, 0, 0, Seq("Too many extractions"))
      } else {
        test(ref, records)
      }
    }

    val results: Seq[TestResult] = refs.map(testRecord _)

    val precision = results.map(_.precision).sum / results.size
    val recall = results.map(_.recall).sum / results.size

    println(s"$name precision: $precision recall: $recall")

    TestResults(precision, recall, results)
  }

  "cora-ie references" should "be extracted" in {
    assert(runTest("segmentation", segmentationTest _).recall >= 0.1)
  }
}
