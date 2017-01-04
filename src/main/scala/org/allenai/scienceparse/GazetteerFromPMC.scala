package org.allenai.scienceparse

import java.util.concurrent.atomic.AtomicInteger

import org.allenai.common.ParIterator._
import scala.concurrent.ExecutionContext.Implicits.global
import spray.json._

object GazetteerFromPMC extends App {
  case class GazetteerEntry(id: String, title: String, authors: Seq[String], year: Int)
  import DefaultJsonProtocol._
  implicit val gazetteerEntryFormat = jsonFormat4(GazetteerEntry.apply)

  // We use the first 1k of this for testing, so let's drop 10k just to be sure.
  val labeledDataNotUsedForTesting = LabeledDataFromPMC.get.drop(10000)

  val noneCount = new AtomicInteger()

  labeledDataNotUsedForTesting.parMap { ld =>
    (ld.title, ld.authors, ld.year) match {
      case (Some(title), Some(authors), Some(year)) =>
        Some(GazetteerEntry("skipped", title.replaceAll("\\s+", " "), authors.map(_.name), year))
      case _ =>
        noneCount.incrementAndGet()
        None
    }
  }.flatten.take(1000000).foreach { entry =>
    println(entry.toJson)
  }
}
