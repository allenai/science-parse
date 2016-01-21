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
  val extractor = new ExtractReferences(getClass.getResource("/referencesGroundTruth.json").getPath)

  Resource.using(
    Source.fromInputStream(getClass.getResourceAsStream("/tagged_references.txt"))) {
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
    return TestResult(ref, extracted, 1, 1)
  }

  def runTest(name: String, test: (Reference, Seq[BibRecord]) => TestResult): TestResults = {
    val results = new ArrayBuffer[TestResult]
    for (ref <- refs) {
      val text = Seq("Bibliography", ref.source).asJava
      val records = extractor.findReferences(text).getOne.asScala
      if (records.size == 0) {
        results.append(TestResult(ref, records, 0, 0, Seq("Missing")))
        println(s"Missed extraction: ${ref.source}")
      } else if (records.size > 1) {
        results.append(TestResult(ref, records, 0, 0, Seq("Too many extractions")))
      } else {
        results.append(test(ref, records))
      }
    }

    val precision = results.map(_.precision).sum / results.size
    val recall = results.map(_.recall).sum / results.size

    println(s"${name} precision: ${precision} recall: ${recall}")

    return TestResults(precision, recall, results)
  }

  "cora-ie references" should "be extracted" in {
    assert(runTest("segmentation", segmentationTest _).recall >= 0.1)
  }
}
