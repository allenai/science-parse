package org.allenai.scienceparse.pipeline

import java.text.Normalizer

/** This contains a bunch of helper functions stolen from the pipeline code. We need it here to
  * anticipate how well the pipeline will work with the output from science-parse. */
object Normalizers {
  def removeDiacritics(s: String): String =
    "\\p{InCombiningDiacriticalMarks}+".r
      .replaceAllIn(Normalizer.normalize(s, Normalizer.Form.NFD), "")

  def removePunctuation(s: String): String =
    s.replaceAll("\\p{P}", " ")

  def removeNonAphanumeric(s: String): String =
    s.replaceAll("[^A-Za-z0-9]", " ")

  def implodeSpaces(s: String) = " +".r.replaceAllIn(s.trim, " ")

  def removeSpaces(s: String) = " +".r.replaceAllIn(s, "")

  def normalize(s: String): String =
    implodeSpaces(removePunctuation(removeDiacritics(s.toLowerCase)))

  def alphaNumericNormalize(s: String): String =
    implodeSpaces(removeNonAphanumeric(removeDiacritics(s.toLowerCase)))

  def alphaNumericNormalizeNoSpaces(s: String): String =
    removeSpaces(removeNonAphanumeric(removeDiacritics(s.toLowerCase)))

  def strictNormalize(s: String): String = s.toLowerCase.replaceAll("[^a-z]", "")

  def soundexWord(word: String): String = {
    val s = strictNormalize(word)
    if (s.isEmpty) return ""
    s.head + (s.substring(1)
      .replaceAll("[hw]", "")
      .replaceAll("[bfpv]", "1")
      .replaceAll("[cgjkqsxz]", "2")
      .replaceAll("[dt]", "3")
      .replaceAll("l", "4")
      .replaceAll("[mn]", "5")
      .replaceAll("r", "6")
      .replaceAll("(\\d)+", "$1")
      .replaceAll("[aeiouy]", "")
      + "000").take(3)
  }

  def soundex(s: String): String = s.split(" ").map(soundexWord).mkString(" ")

  def truncateWords(s: String): String = s.split(" ").map(strictNormalize(_).take(3)).mkString(" ")
}
