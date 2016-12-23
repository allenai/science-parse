package org.allenai.scienceparse

object GazetteerFromPMC extends App {
  // We use the first 1k of this for testing, so let's drop 10k just to be sure.
  val labeledDataNotUsedForTesting = LabeledDataFromPMC.getCleaned.drop(10000)

  labeledDataNotUsedForTesting.flatMap { ld =>
    (ld.title, ld.authors, ld.year) match {
      case (Some(title), Some(authors), Some(year)) => Some((ld.paperId, title, authors.map(_.name), year))
      case _ => None
    }
  }.take(1000000).foreach { case (paperId, title, authors, year) =>
    def escape(s: String) = s.replace("\"", "\\\"")
    val authorsString = "[\"" + authors.map(escape).mkString("\",\"") + "\"]"
    println(s"""{"id":"$paperId","title":"${escape(title)}","authors":$authorsString,"year":$year},""")
  }
}
