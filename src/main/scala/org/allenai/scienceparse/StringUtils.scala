package org.allenai.scienceparse

import org.allenai.common.{ StringUtils => CommonStringUtils }

object StringUtils {
  import CommonStringUtils.StringImplicits

  def normalize(s: String) = s.replaceFancyUnicodeChars.removeUnprintable.normalize.replace('ı', 'i')
}
