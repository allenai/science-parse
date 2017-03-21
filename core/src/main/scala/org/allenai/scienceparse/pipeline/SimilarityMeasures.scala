package org.allenai.scienceparse.pipeline

import org.allenai.scienceparse.LabeledData.Reference
import org.allenai.scienceparse.StringUtils

/** This contains a bunch of helper functions stolen from the pipeline code. We need it here to
  * anticipate how well the pipeline will work with the output from science-parse. */
object SimilarityMeasures {
  def jaccardSim[T](s1: Set[T], s2: Set[T]): Double = {
    s1.intersect(s2).size.toDouble / s1.union(s2).size
  }

  def containmentJaccardSim[T](s1: Set[T], s2: Set[T]): Double = {
    s1.intersect(s2).size.toDouble / math.min(s1.size, s2.size)
  }

  def identical(left: String, right: String) =
    if (left == right) Some(1.0) else None

  def prePostfix(left: String, right: String, transform: Int => Double = x => x / (x + 0.5)) = {
    if (left.length > right.length && (left.startsWith(right) || left.endsWith(right))) {
      Some(transform(right.split(" ").length))
    } else {
      None
    }
  }

  def pickFromOptions[T](members: Option[T]*): Option[T] =
    members.toSeq.find(_.isDefined).getOrElse(None)

  def twoWayPrePostfix(left: String, right: String, transform: Int => Double = x => x / (x + 0.5)) =
    pickFromOptions(prePostfix(left, right, transform), prePostfix(right, left, transform))

  /** Smooth interpolation between containment Jaccard and plain Jaccard,
    * based on character n-grams.
    * Short strings must match exactly, but longer strings are considered a match
    * if one is a substring of the other.
    *
    * The final score is (J + F * JC) / (1 + F) in which
    * J is the plain Jaccard
    * JC is the containment Jaccard
    * F = s ** (m - 1)
    * m is the minimum length of the two strings
    * s, l are parameters
    *
    * @param left String to compare
    * @param right Other string to compare
    * @param ngramLength Longer values will give a larger penalty to single-character typos
    * @param s Determines how rapidly F rises with string length
    * @param l The string length (in characters) for which which the two Jaccard scores have equal weights
    * @return
    */
  def characterNgramSimilarity(
    left: String,
    right: String,
    ngramLength: Int = 3,
    s: Double = 1.2,
    l: Int = 10
  ): Option[Double] = {
    if (left == right) {
      Some(1.0)
    } else {
      val ngramsLeft = left.sliding(ngramLength).toSet
      val ngramsRight = right.sliding(ngramLength).toSet
      val minSize = math.min(ngramsLeft.size, ngramsRight.size)
      val directSim = jaccardSim(ngramsLeft, ngramsRight)
      val containmentSim = containmentJaccardSim(ngramsLeft, ngramsRight)
      val containmentWeight = math.min(math.pow(s, minSize - l), 100000.0)
      Some((directSim + containmentWeight * containmentSim) / (1.0 + containmentWeight))
    }
  }

  def titleNgramSimilarity(
    left: TitleAuthors,
    right: TitleAuthors,
    s: Double = 1.2,
    l: Int = 10
  ): Option[Double] = {
    if (left == right) {
      Some(1.0)
    } else {
      val ngramsLeft = left.normalizedTitleNgrams
      val ngramsRight = right.normalizedTitleNgrams
      val minSize = math.min(ngramsLeft.size, ngramsRight.size)
      val directSim = jaccardSim(ngramsLeft, ngramsRight)
      val containmentSim = containmentJaccardSim(ngramsLeft, ngramsRight)
      val containmentWeight = math.min(math.pow(s, minSize - l), 100000.0)
      Some((directSim + containmentWeight * containmentSim) / (1.0 + containmentWeight))
    }
  }
}

case class AuthorNameMatch(first: String, last: String, full: String)

case class TitleAuthors(title: String, names: Seq[AuthorNameMatch], year: Option[Int] = None) {
  def lastNames: Seq[String] = names.map(_.last)

  def fullNames: Seq[String] = names.map(_.full)

  // Note: There is a slight inversion of control here. This logic would be more properly contained within
  // BibEntryToPaperMatcher and TitleAuthorsMatchScheme, but is here for performance reasons.
  lazy val normalizedTitleNgrams: Set[String] = Normalizers.alphaNumericNormalize(title).sliding(3).toSet
  lazy val normalizedAuthors: Set[String] = names.map(x => Normalizers.alphaNumericNormalize(x.last)).toSet
  // Does not include empty strings.
  lazy val normalizedAuthorsAllNames: Set[String] = {
    val allNames = names.flatMap(name => Seq(name.first, name.last, name.full))
    val normalized = allNames.map(Normalizers.alphaNumericNormalize)
    normalized.filter(_.nonEmpty).toSet
  }
}

object TitleAuthors {
  def fromReference(ref: Reference) = TitleAuthors(
    ref.title.getOrElse(""),
    ref.authors.map { a =>
      val (first, last) = StringUtils.splitName(a)
      AuthorNameMatch(first, last, a)
    },
    ref.year
  )
}
