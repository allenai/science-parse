package org.allenai.scienceparse

import org.allenai.common.StringUtils.StringExtras
import org.jsoup.Jsoup
import org.jsoup.nodes.{ TextNode, Element }
import org.jsoup.select.Elements
import scala.collection.JavaConverters._

object GrobidParser {
  implicit class JsoupElementsImplicits(e: Element) {

    def findText(path: String): String =
      e.select(path).asScala.headOption.map(_.text).getOrElse("")

    def findAttributeValue(path: String, attrName: String): String =
      e.select(path).asScala.headOption.map(_.attr(attrName)).getOrElse("")

    // The number of text characters in the ancestor that preceed the given element
    def textOffset(ancestor: Element): Int = {
      if (ancestor == e.parent) {
        val ancestorText = ancestor.text
        val elementText = e.text
        val index = ancestorText.indexOf(elementText)
        ancestorText.indexOf(elementText, index + 1) match {
          case -1 => // The common and easy case: Text only occurs once in the parent.
            index
          case _ => // Our text occurs multiple times in the parent.  Bogus!
            // Count how many times it occurs previous to our element
            def countOccurencesIn(base: String) = {
              var count = 0
              var index = base.indexOf(elementText)
              while (index > 0) {
                count += 1
                index = base.indexOf(elementText, index + 1)
              }
              count
            }
            val precedingSiblingText =
              ancestor.childNodes.asScala.takeWhile(_ != e).map {
                case t: TextNode => t.getWholeText.trim()
                case e: Element => e.text
                case _ => ""
              }
            val precedingCount = precedingSiblingText.map(countOccurencesIn).sum
            // Now get the next occurrence of our text
            def nthIndexOf(base: String, n: Int) = {
              var i = 0
              var index = base.indexOf(elementText)
              while (i < n) {
                index = base.indexOf(elementText, index + 1)
                i += 1
              }
              index
            }
            nthIndexOf(ancestorText, precedingCount)
        }
      } else if (e.parent == null) {
        sys.error("Must specify an ancestor element to find text offset")
      } else {
        e.parent.textOffset(ancestor) + e.textOffset(e.parent)
      }
    }
  }

  implicit class StringImplicits2(val str: String) extends AnyVal with StringExtras {
    /** @return Given full name such as "Doe, John A.", returns the last name assuming
      * that it's the word before the comma.
      */
    def lastNameFromFull(): String = str.trim.takeWhile(_ != ',')


  }
}
