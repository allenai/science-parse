package org.allenai.scienceparse.pipeline

import scala.io.Source

/** This contains a bunch of helper functions stolen from the pipeline code. We need it here to
  * anticipate how well the pipeline will work with the output from science-parse. */
object Bucketizers {
  import Normalizers._

  /** This file contains 225 high-frequency n-grams from title prefixes.
    * High means the S2 * Dblp bucket size is > 1M. (Early Sept. 2015)
    * n is 2, 3, 4, 5.
    */
  val highFreqTitleNgramStream = this.getClass.getResourceAsStream("/org/allenai/scienceparse/pipeline/highfreq.tsv")

  val highFreqNameNgramStream = this.getClass.getResourceAsStream("/org/allenai/scienceparse/pipeline/highfreqNames.tsv")

  def loadHighFreqs(is: java.io.InputStream): Map[String, Int] =
    Source.fromInputStream(is).getLines.map { l =>
      val Array(t, f) = l.split("\t")
      t -> f.toInt
    }.toMap

  lazy val highFreqTitleNgrams = loadHighFreqs(highFreqTitleNgramStream)

  lazy val highFreqNameNgrams = loadHighFreqs(highFreqTitleNgramStream) // This looks like a typo, but I copied it this way from the pipeline.

  val defaultTitleCutoffThreshold = 1000000

  val defaultNameCutoffThreshold = 100000

  val concatChar = "_"

  def toBucket(words: Iterable[String]) = words.mkString(concatChar)

  def toBucket(s: String) = s.split(" ").mkString(concatChar)

  val defaultTitleNgramLength = 3

  val defaultNameNgramLength = 2

  val defaultAllowTruncated = true

  val defaultUpto = 1

  def cutoffFilter(b: String, cutoffOption: Option[Int], highFreqs: Map[String, Int]): Boolean =
    cutoffOption.isEmpty || !highFreqs.contains(b) || highFreqs(b) < cutoffOption.get

  /** Return the array of tokens for the given input.
    * Limit number of tokens to maxCount
    */
  def words(text: String, maxCount: Int = 40): Array[String] = {
    val words = alphaNumericNormalize(text).split(' ').filter(_.nonEmpty)
    words.take(maxCount)
  }

  /** Returns a list of ngrams.
    * If cutoff is specified, continue to add more words until the result has frequency
    * lower than the cutoff value.
    * If allowTruncated is set to true, accept ngrams that have length less than n.
    * For example, if the text is "local backbones" and n = 3, we will generate
    * the ngram "local_backbones".
    */
  def ngrams(
    text: String,
    n: Int,
    cutoffOption: Option[Int],
    allowTruncated: Boolean = defaultAllowTruncated,
    highFreqs: Map[String, Int] = highFreqTitleNgrams,
    upto: Int = defaultUpto
  ): Iterator[String] = ngramAux(words(text), n, cutoffOption, allowTruncated, highFreqs, upto)

  def tailNgrams(
    text: String,
    n: Int,
    cutoffOption: Option[Int],
    allowTruncated: Boolean = defaultAllowTruncated,
    highFreqs: Map[String, Int] = highFreqTitleNgrams,
    upto: Int = defaultUpto
  ) = ngramAux(words(text).reverse, n, cutoffOption, allowTruncated, highFreqs, upto)

  def ngramAux(
    chunks: Array[String],
    n: Int,
    cutoffOption: Option[Int],
    allowTruncated: Boolean,
    highFreqs: Map[String, Int],
    upto: Int
  ): Iterator[String] = {
    chunks.sliding(n)
      .filter(x => (allowTruncated && x.nonEmpty) || x.length == n)
      .map(x => toBucket(x.toIterable))
      .filter(cutoffFilter(_, cutoffOption, highFreqs))
      .take(upto)
  }

  def titleNgrams(title: String, upto: Int, allowTruncated: Boolean = defaultAllowTruncated) = {
    ngrams(
      title,
      n = defaultTitleNgramLength,
      cutoffOption = Some(defaultTitleCutoffThreshold),
      upto = upto,
      allowTruncated = allowTruncated
    )
  }

  def titleTailNgrams(title: String, upto: Int = 1, allowTruncated: Boolean = defaultAllowTruncated) = {
    tailNgrams(
      title,
      n = defaultTitleNgramLength,
      cutoffOption = Some(defaultTitleCutoffThreshold),
      upto = upto,
      allowTruncated = allowTruncated
    )
  }

  def nameNgrams(name: String) = ngrams(
    name,
    n = defaultNameNgramLength,
    allowTruncated = false,
    cutoffOption = Some(defaultNameCutoffThreshold),
    highFreqs = highFreqNameNgrams,
    upto = 3
  )

  /** This is used in V1. */
  def simple3TitlePrefix(text: String): List[String] =
    ngrams(text, n = 3, cutoffOption = None, allowTruncated = true, highFreqTitleNgrams, upto = 1).toList
}
