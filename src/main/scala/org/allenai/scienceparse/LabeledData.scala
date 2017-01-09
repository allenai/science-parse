package org.allenai.scienceparse

import java.io._
import java.net.URL
import java.nio.file.Files
import java.security.{DigestInputStream, MessageDigest}
import java.util.zip.ZipFile

import org.allenai.common.{Logging, Resource}
import org.allenai.common.ParIterator._
import org.allenai.datastore.Datastores
import org.apache.commons.io.{FilenameUtils, IOUtils}
import org.apache.pdfbox.pdmodel.PDDocument
import scala.collection.immutable.SortedMap
import scala.io.{Codec, Source}
import scala.util.control.NonFatal
import scala.xml.factory.XMLLoader
import scala.xml.{Elem, Node, XML}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

import scala.collection.JavaConverters._

trait LabeledData {
  import LabeledData._

  /** ID to identify this labeled document. Must be unique. */
  def id: String

  // input
  def inputStream: InputStream
  lazy val bytes: Array[Byte] = Resource.using(inputStream) { IOUtils.toByteArray }
  lazy val paperId: String = Utilities.shaForBytes(bytes)
  lazy val pdDoc: PDDocument = Resource.using(inputStream) { PDDocument.load }

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

  def fromExtractedMetadata(input: => InputStream, labeledDataId: String, em: ExtractedMetadata) =
    new LabeledData {
      override def inputStream: InputStream = input

      override val id: String = labeledDataId

      override val title: Option[String] = Option(em.title)

      override val authors: Option[Seq[Author]] =  Option(em.authors).map { as =>
        as.asScala.map { a =>
          Author(a)
        }
      }

      override val year: Option[Int] = if(em.year == 0) None else Some(em.year)

      override val venue: Option[String] = None

      override val abstractText: Option[String] = Option(em.abstractText)

      override val sections: Option[Seq[Section]] = Option(em.sections).map { ss =>
        ss.asScala.map { s =>
          Section(Option(s.heading), s.text)
        }
      }

      override val references: Option[Seq[Reference]] = Option(em.references).map { rs =>
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

  private val set2version =
    SortedMap((0x00 to 0x74).map(i => f"$i%02x" -> 2): _*) ++
    SortedMap((0x75 to 0x7f).map(i => f"$i%02x" -> 1): _*)

  private val xmlLoader = new ThreadLocal[XMLLoader[Elem]] {
    // XML loader factories are not thread safe, so we have to have one per thread
    override protected def initialValue = {
      val factory = javax.xml.parsers.SAXParserFactory.newInstance()
      factory.setValidating(false)
      factory.setNamespaceAware(false)
      factory.setFeature("http://xml.org/sax/features/validation", false)
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      val parser = factory.newSAXParser()
      XML.withSAXParser(parser)
    }
  }

  def apply = get

  /** Returns an iterator of only those documents for which we have title and author for the
    * paper itself, and for all references */
  def getCleaned = get.flatMap { labeledData =>
    val keep = labeledData.title.isDefined &&
      labeledData.authors.nonEmpty &&
      labeledData.references.exists { refs =>
        refs.forall { ref =>
          ref.title.exists(_.nonEmpty) && ref.authors.nonEmpty
        }
      }
    if(keep) Some(labeledData) else None
  }

  private def pdfNameForXmlName(xmlName: String) =
    xmlName.dropRight(xmlExtension.length) + ".pdf"

  private val maxZipFilesInParallel = 2
  private val shaRegex = "^[0-9a-f]{40}$"r
  def get: Iterator[LabeledData] = set2version.iterator.parMap({ case (set, version) =>
    val zipFilePath = publicFile(s"PMCData$set.zip", version)
    Resource.using(new ZipFile(zipFilePath.toFile)) { zipFile =>
      zipFile.entries().asScala.filter { entry =>
        !entry.isDirectory && entry.getName.endsWith(xmlExtension)
      }.flatMap { xmlEntry =>
        val pdfEntryOption = Option(zipFile.getEntry(pdfNameForXmlName(xmlEntry.getName)))
        pdfEntryOption.map((xmlEntry, _))
      }.map { case (xmlEntry, pdfEntry) =>
        // Because calculating the paper id on the fly takes a long time, we parse it out of the
        // comments in the zip file. If that works, we take it. Otherwise, we fall back to
        // calculating it on the fly.
        val precalculatedPaperId =
          Option(pdfEntry.getComment).flatMap(shaRegex.findFirstMatchIn).map(_.matched)

        val xml = Resource.using(zipFile.getInputStream(xmlEntry))(xmlLoader.get.load)
        val articleMeta = xml \ "front" \ "article-meta"

        new LabeledData {
          // input
          override def inputStream: InputStream = {
            val zipFile = new ZipFile(zipFilePath.toFile)
            val inputStream = zipFile.getInputStream(zipFile.getEntry(pdfEntry.getName))
            new FilterInputStream(inputStream) {
              override def close(): Unit = {
                super.close()
                inputStream.close()
                zipFile.close()
              }
            }
          }

          override val id = s"PMC:${pdfEntry.getName}"

          override lazy val paperId =
            precalculatedPaperId.getOrElse(
              Utilities.shaForBytes(
                Resource.using(inputStream)(IOUtils.toByteArray)))

          private def parseYear(n: Node): Option[Int] = {
            try {
              val i = n.text.trim.dropWhile(_ == '(').takeWhile(_.isDigit).toInt
              if (i >= 1800 && i <= 2100) Some(i) else None
            } catch {
              case e: NumberFormatException =>
                logger.warn(s"Could not parse '${n.text}' as year")
                None
            }
          }

          // expected output
          override val title: Option[String] =
            (articleMeta \ "title-group" \ "article-title").headOption.map(_.text)

          override val authors: Option[Seq[Author]] = Some {
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

          override val venue: Option[String] =
            (xml \ "front" \ "journal-meta" \ "journal-title-group" \ "journal-title").headOption.map(_.text)

          override val year: Option[Int] = Iterable(
            ("pub-type", "ppub"),
            ("pub-type", "collection"),
            ("pub-type", "epub"),
            ("pub-type", "pmc-release"),
            ("publication-format", "print"),
            ("publication-format", "electronic")
          ).flatMap { case (attrName, attrValue) =>
            (articleMeta \ "pub-date") filter (_ \@ attrName == attrValue)
          }.flatMap(_ \ "year").flatMap(parseYear).headOption

          private def parseSection(e: Node): Seq[Section] = {
            (e \ "sec") map { s =>
              val title = (s \ "title").headOption.map(_.text)
              val body = (s \ "p").map(_.text.replace('\n', ' ')).mkString("\n")
              Section(title, body)
            }
          }

          override val abstractText: Option[String] = (articleMeta \ "abstract").headOption.map { a =>
            val sections = parseSection(a)
            if (sections.isEmpty) {
              (a \\ "p").map(_.text.replace('\n', ' ')).mkString("\n")
            } else {
              sections.map(s => s"${s.heading.getOrElse("")}\n${s.text}".trim).mkString("\n\n")
            }
          }

          override val sections: Option[Seq[Section]] = (xml \ "body").headOption.map(parseSection)

          override val references: Option[Seq[Reference]] = Some {
            (xml \ "back" \ "ref-list" \ "ref") map { ref =>
              val label = (ref \ "label").headOption.map(_.text)

              val citation =
                Seq("citation", "element-citation", "mixed-citation").flatMap(ref \ _)

              val title = Seq("article-title", "chapter-title").flatMap(citation \ _).headOption.map(_.text)
              val authors = Seq(
                citation \ "person-group" filter (_ \@ "person-group-type" != "editor"),
                citation
              ).flatMap(_ \ "name") map { e =>
                val surname = (e \ "surname").text
                val givenNames = (e \ "given-names").text
                s"$givenNames $surname".trim
              }
              val venue = (citation \ "source").headOption.map(_.text)
              val year = (citation \ "year").flatMap(parseYear).headOption
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

          override val mentions: Option[Seq[Mention]] = None // TODO
        }
      }.toArray
    }
  }, maxZipFilesInParallel).flatten

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

    allPaperIds.iterator.map { pid =>
      new LabeledData {
        override lazy val paperId = pid

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
  def get(input: => InputStream, parser: Parser = Parser.getInstance()) = {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    val bytes = Resource.using(new DigestInputStream(input, digest))(IOUtils.toByteArray)
    val id = Utilities.toHex(digest.digest())
    val labeledPaperId = s"SP:$id"

    try {
      val output = Resource.using(new ByteArrayInputStream(bytes))(parser.doParse)
      LabeledData.fromExtractedMetadata(input, labeledPaperId, output)
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
  private val cachedGrobidServer = new CachedGrobidServer(grobidServerUrl)

  def get(input: => InputStream) = {
    val bytes = IOUtils.toByteArray(input)
    val pid = Utilities.shaForBytes(bytes)
    val labeledDataId = s"Grobid:$grobidServerUrl/$pid"

    try {
      val em = Resource.using(cachedGrobidServer.getExtractions(bytes)) { is =>
        GrobidParser.parseGrobidXml(is, grobidServerUrl.toString)
      }
      LabeledData.fromExtractedMetadata(
        input,
        labeledDataId,
        em
      )
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Error '${e.getMessage}' from Grobid for paper $pid")
        new EmptyLabeledData(labeledDataId, input)
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

object LabeledDataFromOldGrobid extends Datastores {
  def get: Iterator[LabeledData] = {
    val grobidExtractions = publicDirectory("GrobidExtractions", 1)
    val pdfDirectory = publicDirectory("PapersTestSet", 3)
    Files.newDirectoryStream(grobidExtractions, "*.xml").iterator().asScala.map { file =>
      val paperId = FilenameUtils.getBaseName(file.toString)
      val em = GrobidParser.parseGrobidXml(file)
      LabeledData.fromExtractedMetadata(
        Files.newInputStream(pdfDirectory.resolve(s"$paperId.pdf")),
        s"OldGrobid:$paperId",
        em
      )
    }
  }
}
