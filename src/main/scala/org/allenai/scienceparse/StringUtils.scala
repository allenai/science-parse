package org.allenai.scienceparse

import org.allenai.common.{StringUtils => CommonStringUtils}

object StringUtils {

  import CommonStringUtils.StringImplicits

  // this is just here to make it easier to call from Java
  def replaceFancyChars(s: String) = s.replaceFancyUnicodeChars
}
