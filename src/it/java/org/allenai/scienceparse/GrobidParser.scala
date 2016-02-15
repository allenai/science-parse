package org.allenai.scienceparse

import java.nio.file.Path
import java.util.Calendar

import org.allenai.common.StringUtils
import org.allenai.common.StringUtils.StringExtras
import org.jsoup.Jsoup
import org.jsoup.nodes.{ TextNode, Element }
import org.jsoup.select.Elements
import scala.collection.JavaConverters._

object GrobidParser {
  def addDot(x: String) = if (x.length == 1) s"$x." else x

  def author(e: Element): String =  {
    val first = List(e.findText("persName>forename[type=first]"))
    val mids = e.select("persName>forename[type=middle]").asScala.map(_.text).toList
    val last = List(e.findText("persName>surname"))
    (first ++ mids ++ last).filter(!_.isEmpty).map(a => addDot(a.trimNonAlphabetic)).mkString(" ")
  }

  def extractTitle(doc: Element): String = {
    doc.findText("teiHeader>fileDesc>titleStmt>title").titleCase()
  }

  def toTitle(s: String) = {
    s.trimChars(",.").find(c => Character.isAlphabetic(c)) match {
      case None => ""
      case Some(_) => s
    }
  }

  def extractYear(str: String): Int = "\\d{4}".r.findFirstIn(str) match {
    case Some(y) => y.toInt
    case None => 0
  }

  def extractBibEntriesWithId(doc: Element) =
    for {
      bib <- doc.select("listBibl>biblStruct").asScala
    } yield {
      val title = toTitle(bib.findText("analytic>title[type=main]")) match {
        case "" => bib.findText("monogr>title")
        case s => s
      }
      val authors = bib.select("analytic>author").asScala.map(author).toList match {
        case List() => bib.select("monogr>author").asScala.map(author).toList
        case l => l
      }
      val venue = bib.findText("monogr>title")
      val yr = extractYear(bib.findAttributeValue("monogr>imprint>date[type=published]", "when"))
      new BibRecord(title, authors.asJava, venue, null, null, yr)
    }

  def ifNonEmpty(s: String) = if (s.nonEmpty) Some(s) else None

  case class Section(id: Option[String], header: Option[String], text: String)

  private def extractSectionInfo(div: Element) = {
    val bodyPlusHeaderText = div.text

    val head = div.select("head").asScala.headOption
    val (id, headerText, bodyTextOffset) = head match {
      case Some(h) =>
        val hText = h.text
        (
          ifNonEmpty(h.attr("n")),
          Some(hText),
          hText.size + bodyPlusHeaderText.drop(hText.size).takeWhile(_ <= ' ').size
          )
      case None =>
        (None, None, 0)
    }
    val section = Section(id = id, text = bodyPlusHeaderText.drop(bodyTextOffset), header = head.map(_.text))
    (div, bodyPlusHeaderText, bodyTextOffset, section)
  }

  def extractReferenceMentions(doc: Element): List[CitationRecord] = {
    val sectionInfo = doc.select("text>div").asScala.map(extractSectionInfo)
    val bibMentions =
      for {
        ref <- doc.select("ref[type=bibr").asScala
        ((div, fullText, offset, _), sectionNumber) <- sectionInfo.zipWithIndex.find {
          case ((div, fullText, offset, _), i) =>
            ref.parents.contains(div)
        }
      } yield {
        val id = ref.attr("target").dropWhile(_ == '#')
        val begin = ref.textOffset(div) - offset
        val end = begin + ref.text.length
        Parser.extractContext(0, fullText, begin, end)
      }
    bibMentions.toList
  }

  def parseGrobidXml(grobidExtraction: Path): ExtractedMetadata = {
    val doc = Jsoup.parse(grobidExtraction.toFile, "UTF-8")
    val year = extractYear(doc.findAttributeValue("teiHeader>fileDesc>sourceDesc>biblStruct>monogr>imprint>date[type=published]", "when"))
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    val em = new ExtractedMetadata(extractTitle(doc), doc.select("teiHeader>fileDesc>sourceDesc>biblStruct>analytic>author").asScala.map(author).asJava, calendar.getTime)
    em.year = year
    em.references = extractBibEntriesWithId(doc).asJava
    em.referenceMentions = extractReferenceMentions(doc).asJava
    em.abstractText = doc.select("teiHeader>profileDesc>abstract").asScala.headOption.map(_.text).getOrElse("")
    em
  }

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
