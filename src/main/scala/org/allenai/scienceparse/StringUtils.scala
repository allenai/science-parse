package org.allenai.scienceparse

import org.allenai.common.{ StringUtils => CommonStringUtils }

object StringUtils {
  import CommonStringUtils.StringImplicits

  def normalize(s: String) = s.normalize.replaceFancyUnicodeChars.removeUnprintable.replace('ı', 'i')

  def makeSingleLine(s: String) = s.replaceAll("\\n", "\\\\n").replaceAll("\\r", "\\\\r")
}
