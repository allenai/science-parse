package org.allenai.scienceparse

import org.allenai.common.{ StringUtils => CommonStringUtils }

object StringUtils {
  import CommonStringUtils.StringImplicits

  def normalize(s: String) = s.normalize.replaceFancyUnicodeChars.removeUnprintable.replace('ı', 'i')

  def makeSingleLine(s: String) = s.replaceAll("\\n", "\\\\n").replaceAll("\\r", "\\\\r")


  /** Splits a name into first and last names */
  def splitName(name: String) = {
    val suffixes = Set("Jr.", "Sr.", "II", "III")
    val lastNamePrefixes = Set("van", "da", "von")

    val parts = name.split("\\s", -1)

    if(parts.length <= 1) {
      ("", name)
    } else {
      var lastNameIndex = parts.length - 1
      def skipToNonemptyPart() =
        while(lastNameIndex > 0 && parts(lastNameIndex).isEmpty)
          lastNameIndex -= 1
      def skipToRightAfterNonemptyPart() =
        while(lastNameIndex > 1 && parts(lastNameIndex - 1).isEmpty)
          lastNameIndex -= 1

      // move to the first non-empty part
      skipToNonemptyPart()

      // deal with suffixes
      if(lastNameIndex > 0 && suffixes.contains(parts(lastNameIndex)))
        lastNameIndex -= 1
      skipToNonemptyPart()

      // deal with last name prefixes
      skipToRightAfterNonemptyPart()
      if(lastNameIndex > 1 && lastNamePrefixes.contains(parts(lastNameIndex - 1)))
        lastNameIndex -= 1
      skipToRightAfterNonemptyPart()

      (parts.take(lastNameIndex).mkString(" "), parts.drop(lastNameIndex).mkString(" "))
    }
  }

  def getFirstName(name: String) = splitName(name)._1
  def getLastName(name: String) = splitName(name)._2
}
