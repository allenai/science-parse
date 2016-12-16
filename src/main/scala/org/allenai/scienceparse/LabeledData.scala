package org.allenai.scienceparse

import java.io._
import java.net.{SocketTimeoutException, URL}
import java.nio.file.Files
import java.security.{DigestInputStream, MessageDigest}
import java.util.zip.ZipFile

import org.allenai.common.{Logging, Resource}
import org.allenai.common.ParIterator._
import org.allenai.datastore.Datastores
import org.apache.commons.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import scala.io.{Codec, Source}
import scala.util.{Success, Failure, Try, Random}
import scala.util.control.NonFatal
import scala.xml.{Node, XML}
import scala.concurrent.ExecutionContext.Implicits.global
import org.allenai.common.StringUtils._

import scala.collection.JavaConverters._
import scalaj.http.{HttpResponse, MultiPart, Http}

trait LabeledData {
  import LabeledData._

  /** ID to identify this labeled document. Must be unique. */
  def id: String

  // input
  def inputStream: InputStream
  def bytes: Array[Byte] = Resource.using(inputStream) { IOUtils.toByteArray }
  def pdDoc: PDDocument = Resource.using(inputStream) { PDDocument.load }

  // expected output
  // These are all Options. If they are not set, we don't have that field labeled for this paper.
  def title: Option[String]
  def authors: Option[Seq[Author]]
  def venue: Option[String]
  def year: Option[Int]
  def abstractText: Option[String]
  def sections: Option[Seq[Section]]
  def references: Option[Seq[Reference]]
  def mentions: Option[Seq[Mention]]

  // This is not the same as toString, because it contains newline and is generally intended to be
  // read by humans.
  def readableString = {
    val builder = new StringBuilder

    builder ++= id
    builder += '\n'

    builder ++= s"Title: $title\n"

    authors match {
      case None => builder ++= "No authors\n"
      case Some(as) =>
        builder ++= "Authors:\n"
        as.foreach { a =>
          builder ++= s"  ${a.name}\n"
          a.email.foreach { e => builder ++= s"    $e\n" }
          a.affiliations.foreach { a => builder ++= s"    $a\n" }
        }
    }

    builder ++= s"Year: $year\n"
    builder ++= s"Abstract: $abstractText\n"

    references match {
      case None => builder ++= "No references\n"
      case Some(rs) =>
        builder ++= "References:\n"
        rs.foreach { r =>
          builder ++= s"  Label: ${r.label}\n"
          builder ++= s"    Title: ${r.title}\n"
          builder ++= s"    Authors: ${r.authors.mkString(", ")}\n"
          builder ++= s"    Venue: ${r.venue}\n"
          builder ++= s"    Year: ${r.year}\n"
          builder ++= s"    Volume: ${r.volume}\n"
          builder ++= s"    Page range: ${r.pageRange}\n"
        }
    }

    // TODO: sections and mentions

    builder.toString
  }
}

object LabeledData {
  case class Author(
    name: String,
    email: Option[String] = None,
    affiliations: Seq[String] = Seq.empty
  )

  case class Section(heading: Option[String], text: String)

  case class Reference(
    label: Option[String],  // This is the "1" in "[1]", or "Etzioni2016"
    title: Option[String],
    authors: Seq[String],
    venue: Option[String],
    year: Option[Int],
    volume: Option[String],
    pageRange: Option[(String, String)]
  )

  case class Range(start: Int, end: Int)

  case class Mention(
    reference: Reference,
    text: String,
    inContext: Option[(Section, Range)]
  )

  def dump(labeledData: Iterator[LabeledData]): Unit = {
    // We don't time the first one, because it might load models.
    println(labeledData.next().readableString)

    val startTime = System.currentTimeMillis()
    labeledData.map(_.readableString).foreach(println)
    val endTime = System.currentTimeMillis()

    println(s"Completed in ${endTime - startTime} milliseconds")
  }
}

class EmptyLabeledData(val id: String, input: => InputStream) extends LabeledData {
  import LabeledData._

  override def inputStream = input

  override def title: Option[String] = None
  override def authors: Option[Seq[Author]] = None
  override def abstractText: Option[String] = None
  override def year: Option[Int] = None
  override def venue: Option[String] = None
  override def sections: Option[Seq[Section]] = None
  override def references: Option[Seq[Reference]] = None
  override def mentions: Option[Seq[Mention]] = None
}

object LabeledDataFromPMC extends Datastores with Logging {
  import LabeledData._

  private val xmlExtension = ".nxml"

  private val set2version = Map(
    "00" -> 1,
    "01" -> 1,
    "02" -> 1,
    "03" -> 1,
    "04" -> 1,
    "05" -> 1,
    "06" -> 1,
    "07" -> 1,
    "08" -> 1,
    "09" -> 1,
    "0a" -> 1,
    "0b" -> 1,
    "0c" -> 1,
    "0d" -> 1,
    "0e" -> 1,
    "0f" -> 1,
    "10" -> 1,
    "11" -> 1,
    "12" -> 1,
    "13" -> 1,
    "14" -> 1,
    "15" -> 1,
    "16" -> 1,
    "17" -> 1,
    "18" -> 1,
    "19" -> 1,
    "1a" -> 1,
    "1b" -> 1,
    "1c" -> 1,
    "1d" -> 1,
    "1e" -> 1,
    "1f" -> 1,
    "20" -> 1,
    "21" -> 1,
    "22" -> 1,
    "23" -> 1,
    "24" -> 1,
    "25" -> 1,
    "26" -> 1,
    "27" -> 1,
    "28" -> 1,
    "29" -> 1,
    "2a" -> 1,
    "2b" -> 1,
    "2c" -> 1,
    "2d" -> 1,
    "2e" -> 1,
    "2f" -> 1
  )

  private val xmlLoader = {
    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    factory.setValidating(false)
    factory.setNamespaceAware(false)
    factory.setFeature("http://xml.org/sax/features/validation", false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val parser = factory.newSAXParser()
    XML.withSAXParser(parser)
  }

  def apply = get

  /** Returns an iterator of only those documents for which we have title and author for the
    * paper itself, and for all references */
  def getCleaned = get.filter { labeledData =>
    labeledData.title.isDefined &&
      labeledData.authors.nonEmpty &&
      labeledData.references.exists { refs =>
        refs.forall { ref =>
          ref.title.exists(_.nonEmpty) && ref.authors.nonEmpty
        }
      }
  }

  private def pdfNameForXmlName(xmlName: String) =
    xmlName.dropRight(xmlExtension.length) + ".pdf"

  def get: Iterator[LabeledData] = set2version.iterator.flatMap { case (set, version) =>
    val zipFilePath = publicFile(s"PMCData$set.zip", version)
    Resource.using(new ZipFile(zipFilePath.toFile)) { case zipFile =>
      def allNames = zipFile.entries().asScala.filterNot(_.isDirectory).map(_.getName)
      val pdfNames = allNames.filter(_.endsWith(".pdf")).toSet

      allNames.filter { name =>
        name.endsWith(xmlExtension) && pdfNames.contains(pdfNameForXmlName(name))
      }.map { name =>
        (zipFilePath, name)
      }.toArray
    }
  }.map { case (zipFilePath, xmlEntryName) =>
    require(xmlEntryName.endsWith(xmlExtension))

    def getEntryAsInputStream(entryName: String): InputStream = {
      val zipFile = new ZipFile(zipFilePath.toFile)
      val inputStream = zipFile.getInputStream(zipFile.getEntry(entryName))
      new FilterInputStream(inputStream) {
        override def close(): Unit = {
          super.close()
          inputStream.close()
          zipFile.close()
        }
      }
    }

    new LabeledData {
      // input
      override def inputStream: InputStream = getEntryAsInputStream(pdfNameForXmlName(xmlEntryName))

      override lazy val pdDoc: PDDocument = super.pdDoc // Overriding this to make it into a lazy val

      override val id = s"PMC:$xmlEntryName"

      private def parseInt(n: Node): Option[Int] = {
        try {
          Some(n.text.toInt)
        } catch {
          case e: NumberFormatException =>
            logger.warn(s"Could not parse '${n.text}' as int")
            None
        }
      }

      // expected output
      private lazy val xml = Resource.using(getEntryAsInputStream(xmlEntryName)) { xmlLoader.load }

      private lazy val articleMeta = xml \ "front" \ "article-meta"
      override lazy val title: Option[String] =
        (articleMeta \ "title-group" \ "article-title").headOption.map(_.text)

      override lazy val authors: Option[Seq[Author]] = Some {
        val affiliationId2affiliation = (for {
          affiliationElem <- articleMeta \ "aff"
          id = affiliationElem \@ "id"
          pcdat <- affiliationElem.child.filter(_.label == "#PCDATA")
          text = pcdat.text.trim
          if text.nonEmpty
        } yield {
          (id, text)
        }).toMap

        (articleMeta \ "contrib-group" \ "contrib") filter (_ \@ "contrib-type" == "author") map { e =>
          val surname = (e \ "name" \ "surname").text
          val givenNames = (e \ "name" \ "given-names").text
          val email = (e \ "email").headOption.map(_.text)
          val affiliationIds = (e \ "xref") filter (_ \@ "ref-type" == "aff") map (_ \@ "rid")
          val affiliations = affiliationIds flatMap affiliationId2affiliation.get

          Author(s"$givenNames $surname".trim, email, affiliations)
        }
      }

      override lazy val venue: Option[String] =
        (xml \ "front" \ "journal-meta" \ "journal-title-group" \ "journal-title").headOption.map(_.text)

      override lazy val year: Option[Int] = Iterable(
        ("pub-type", "ppub"),
        ("pub-type", "collection"),
        ("pub-type", "epub"),
        ("pub-type", "pmc-release"),
        ("publication-format", "print"),
        ("publication-format", "electronic")
      ).flatMap { case (attrName, attrValue) =>
        (articleMeta \ "pub-date") filter (_ \@ attrName == attrValue)
      }.flatMap(_ \ "year").flatMap(parseInt).headOption

      private def parseSection(e: Node): Seq[Section] = {
        (e \ "sec") map { s =>
          val title = (s \ "title").headOption.map(_.text)
          val body = (s \ "p").map(_.text.replace('\n', ' ')).mkString("\n")
          Section(title, body)
        }
      }

      override lazy val abstractText: Option[String] = (articleMeta \ "abstract").headOption.map { a =>
        val sections = parseSection(a)
        if(sections.isEmpty) {
          (a \\ "p").map(_.text.replace('\n', ' ')).mkString("\n")
        } else {
          sections.map(s => s"${s.heading.getOrElse("")}\n${s.text}".trim).mkString("\n\n")
        }
      }

      override lazy val sections: Option[Seq[Section]] = (xml \ "body").headOption.map(parseSection)

      override lazy val references: Option[Seq[Reference]] = Some {
        (xml \ "back" \ "ref-list" \ "ref") map { ref =>
          val label = (ref \ "label").headOption.map(_.text)

          val citation =
            Seq("citation", "element-citation", "mixed-citation").flatMap(ref \ _)

          val title = Seq("article-title", "chapter-title").flatMap(citation \ _).headOption.map(_.text)
          val authors = Seq(
            citation \ "person-group" filter (_ \@ "person-group-type" != "editor"),
            citation
          ).flatMap (_ \ "name") map { e =>
            val surname = (e \ "surname").text
            val givenNames = (e \ "given-names").text
            s"$givenNames $surname".trim
          }
          val venue = (citation \ "source").headOption.map(_.text)
          val year = (citation \ "year").flatMap(parseInt).headOption
          val volume = (citation \ "volume").headOption.map(_.text)
          val firstPage = (citation \ "fpage").headOption.map(_.text)
          val lastPage = (citation \ "lpage").headOption.map(_.text)
          val pageRange = (firstPage, lastPage) match {
            case (Some(first), Some(last)) => Some((first, last))
            case _ => None
          }
          Reference(label, title, authors, venue, year, volume, pageRange)
        }
      }

      override lazy val mentions: Option[Seq[Mention]] = None // TODO
    }
  }

  def main(args: Array[String]): Unit = LabeledData.dump(LabeledDataFromPMC.get)
}

object LabeledDataFromResources extends Datastores {
  import LabeledData._

  def apply = get

  def get: Iterator[LabeledData] = {
    val pdfDirectory = publicDirectory("PapersTestSet", 3)

    def readResourceFile(filename: String): Map[String, Seq[String]] =
      Resource.using(Source.fromInputStream(getClass.getResourceAsStream(filename))(Codec.UTF8)) { source =>
        source.getLines().map { line =>
          val fields = line.trim.split("\t").map(_.trim)
          val paperId = fields.head.toLowerCase
          val values = fields.tail.toSeq
          paperId -> values
        }.toMap
      }

    val allData = Seq(
      readResourceFile("/golddata/dblp/authorFullName.tsv"),
      readResourceFile("/golddata/dblp/title.tsv"),
      readResourceFile("/golddata/isaac/abstracts.tsv"),
      readResourceFile("/golddata/isaac/bibliographies.tsv")
    )

    val Seq(
      paperId2authors,
      paperId2titles,
      paperId2abstracts,
      paperId2bibliographies
    ) = allData

    val allPaperIds = allData.map(_.keySet).reduce(_ ++ _)

    allPaperIds.iterator.map { paperId =>
      new LabeledData {
        override val id: String = s"Resources:$paperId"

        override def inputStream: InputStream =
          Files.newInputStream(pdfDirectory.resolve(s"$paperId.pdf"))


        override val title: Option[String] = paperId2titles.get(paperId).flatMap(_.headOption)

        override val authors: Option[Seq[Author]] =
          paperId2authors.get(paperId).map { authorNames =>
            authorNames.map { authorName => Author(authorName) }
          }

        override val year: Option[Int] = None

        override val venue: Option[String] = None

        override val abstractText: Option[String] =
          paperId2abstracts.get(paperId).flatMap(_.headOption)

        override val sections: Option[Seq[Section]] = None

        override val references: Option[Seq[Reference]] = paperId2bibliographies.get(paperId).map { bibStrings =>
          bibStrings.map { bibString =>
            val Array(title, year, venue, authors) = bibString.split("\\|", -1)
            Reference(
              None,
              Some(title),
              authors.split(":").toSeq,
              Some(venue),
              Some(year.toInt),
              None,
              None)
          }
        }

        override val mentions: Option[Seq[Mention]] = None  // TODO: we might be able to get mentions from the data that Isaac created back in the day
      }
    }
  }

  def main(args: Array[String]): Unit = LabeledData.dump(LabeledDataFromResources.get)
}

object LabeledDataFromScienceParse extends Logging {
  import LabeledData._

  private val sha1HexLength = 40
  private def toHex(bytes: Array[Byte]): String = {
    val sb = new scala.collection.mutable.StringBuilder(sha1HexLength)
    bytes.foreach { byte => sb.append(f"$byte%02x") }
    sb.toString
  }

  def get(input: => InputStream, parser: Parser = Parser.getInstance()) = {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    val bytes = Resource.using(new DigestInputStream(input, digest))(IOUtils.toByteArray)
    val id = toHex(digest.digest())
    val labeledPaperId = s"SP:$id"

    try {
      val output = Resource.using(new ByteArrayInputStream(bytes))(parser.doParse)

      new LabeledData {
        override def inputStream: InputStream = input

        override val id: String = labeledPaperId

        override val title: Option[String] = Option(output.title)

        override val authors: Option[Seq[Author]] =  Option(output.authors).map { as =>
          as.asScala.map { a =>
            Author(a)
          }
        }

        override val year: Option[Int] = if(output.year == 0) None else Some(output.year)

        override val venue: Option[String] = None

        override val abstractText: Option[String] = Option(output.abstractText)

        override val sections: Option[Seq[Section]] = Option(output.sections).map { ss =>
          ss.asScala.map { s =>
            Section(Option(s.heading), s.text)
          }
        }

        override val references: Option[Seq[Reference]] = Option(output.references).map { rs =>
          rs.asScala.map { r =>
            Reference(
              None,
              Option(r.title),
              Option(r.author).map(_.asScala.toSeq).getOrElse(Seq.empty),
              Option(r.venue),
              if(r.year == 0) None else Some(r.year),
              None,
              None
            )
          }
        }

        override def mentions: Option[Seq[Mention]] = ???
      }
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Error while science-parsing: $e")
        new EmptyLabeledData(labeledPaperId, input)
    }
  }

  def main(args: Array[String]): Unit = {
    val fromResources = LabeledDataFromResources.get
    val fromSp =
      fromResources.parMap(
        labeledDataFromResources => get(labeledDataFromResources.inputStream)
      )
    LabeledData.dump(fromSp)
  }
}

class LabeledDataFromGrobidServer(grobidServerUrl: URL) extends Logging {
  import LabeledData._

  private val cachedGrobidServer = new CachedGrobidServer(grobidServerUrl)

  def get(input: => InputStream) = {
    val (paperId, tryXml) = cachedGrobidServer.getIdAndExtractions(input)

    val labeledDataId = s"Grobid:$grobidServerUrl/$paperId"

    tryXml match {
      case Failure(e) =>
        logger.warn(s"Error '${e.getMessage}' from Grobid for paper $paperId")
        new EmptyLabeledData(labeledDataId, input)
      case Success(xml) =>
        new LabeledData {
          override def inputStream: InputStream = input

          override val id: String = labeledDataId

          private val fileDesc = xml \ "teiHeader" \ "fileDesc"

          override val title: Option[String] =
            (fileDesc \ "titleStmt" \ "title").headOption.map(_.text.trim.titleCase)

          override val authors: Option[Seq[Author]] = Some(
            (fileDesc \ "sourceDesc" \ "biblStruct" \ "analytic" \ "author") map { authorNode =>
              val name = authorNameFromNode(authorNode)
              val affiliations = (authorNode \ "affiliation").map { affiliationNode =>
                (affiliationNode \ "orgName").map(_.text).mkString(", ")
              }

              // TODO: email

              Author(name, None, affiliations)
            }
          )

          override val year: Option[Int] = None // TODO

          override val venue: Option[String] = None // TODO

          override val abstractText: Option[String] =
            (xml \ "teiHeader" \ "profileDesc" \ "abstract").headOption.map(_.text)

          override val sections: Option[Seq[Section]] = Some {
            (xml \ "text" \ "body" \ "div").map { div =>
              val bodyPlusHeaderText = div.text

              val head = (div \ "head").headOption.map(_.text)
              val body = head match {
                case Some(h) => bodyPlusHeaderText.drop(h.length)
                case None => bodyPlusHeaderText
              }

              Section(head, body)
            }
          }

          override val references: Option[Seq[Reference]] =
            (xml \ "text" \ "back" \ "div" \ "listBibl").headOption.map { listBiblNode =>
              (listBiblNode \ "biblStruct").map { biblStructNode =>
                val year = (biblStructNode \ "monogr" \ "imprint" \ "date" \ "@when").
                  headOption.
                  map { node =>
                    node.text.takeWhile(c => c >= '0' && c <= '9').toInt
                  }

                val biblScopeNodes = biblStructNode \ "monogr" \ "imprint" \ "biblScope"
                val volume = (biblScopeNodes find (_ \@ "unit" == "volume")).map(_.text)
                val pageRange = (biblScopeNodes find (_ \@ "unit" == "page")).flatMap { node =>
                  val from = (node \ "@from").headOption.map(_.text)
                  val to = (node \ "@to").headOption.map(_.text)
                  (from, to) match {
                    case (Some(a), Some(b)) => Some((a, b))
                    case _ => None
                  }
                }

                def sanitizeTitleText(title: String) = {
                  val trimmed = title.trimChars(",.")
                  trimmed.find(c => Character.isAlphabetic(c)) match {
                    case None => None
                    case Some(_) => Some(trimmed)
                  }
                }


                (biblStructNode \ "analytic").headOption.map { analyticNode =>
                  // This is the case where we have an article (in the "analytic" section) inside a
                  // collection (in the "monogr" section.

                  val title = (analyticNode \ "title").headOption.map(_.text)
                  val authors = (analyticNode \ "author").map(authorNameFromNode)
                  val venue = (biblStructNode \ "monogr" \ "title").headOption.map(_.text)

                  Reference(
                    None,
                    title.flatMap(sanitizeTitleText),
                    authors,
                    venue,
                    year,
                    volume,
                    pageRange
                  )
                } getOrElse {
                  // This is the case where we have no article as part of a collection, just a whole
                  // work, like a book.

                  val title = (biblStructNode \ "monogr" \ "title").headOption.map(_.text)
                  val authors = (biblStructNode \ "monogr" \ "author").map(authorNameFromNode)

                  Reference(
                    None,
                    title.flatMap(sanitizeTitleText),
                    authors,
                    None,
                    year,
                    volume,
                    pageRange
                  )
                }
              }
            }

          override def mentions: Option[Seq[Mention]] = ??? // TODO

          private def authorNameFromNode(authorNode: Node) = {
            val nameNodes =
              (authorNode \ "persName" \ "forename") ++ (authorNode \ "persName" \ "surname")

            def addDot(x: String) = if (x.length == 1) s"$x." else x
            nameNodes.map(_.text).filter(_.nonEmpty).map(a => addDot(a.trimNonAlphabetic)).mkString(" ")
          }
        }
    }
  }
}

object LabeledDataFromGrobidServer {
  def main(args: Array[String]): Unit = {
    val url = new URL(args(0))
    val labeledDataFromGrobidServer = new LabeledDataFromGrobidServer(url)

    val fromResources = LabeledDataFromResources.get
    val fromGrobid = fromResources.parMap { labeledDataFromResources =>
      labeledDataFromGrobidServer.get(labeledDataFromResources.inputStream)
    }
    LabeledData.dump(fromGrobid)
  }
}
