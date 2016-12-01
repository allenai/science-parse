package org.allenai.scienceparse

import java.io.{FilterInputStream, InputStream}
import java.nio.file.Files
import java.util.zip.ZipFile

import org.allenai.common.{Logging, Resource}
import org.allenai.datastore.Datastores
import org.apache.commons.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import scala.io.{Codec, Source}
import scala.xml.{Node, XML}

import scala.collection.JavaConverters._

trait LabeledData {
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
}

object LabeledData {
  def dump(labeledData: Iterator[LabeledData]): Unit = {
    labeledData.foreach { data =>
      println(data.id)

      println(s"Title: ${data.title}")

      data.authors match {
        case None => println("No authors")
        case Some(as) =>
          println("Authors:")
          as.foreach { a =>
            println(s"  ${a.name}")
            a.email.foreach { e => println(s"    $e") }
            a.affiliations.foreach { a => println(s"    $a") }
          }
      }

      println(s"Title: ${data.title}")
      println(s"Year: ${data.year}")
      println(s"Abstract: ${data.abstractText}")

      data.references match {
        case None => println("No references")
        case Some(rs) =>
          println("References:")
          rs.foreach { r =>
            println(s"  Label: ${r.label}")
            println(s"    Title: ${r.title}")
            println(s"    Authors: ${r.authors.mkString(", ")}")
            println(s"    Venue: ${r.venue}")
            println(s"    Year: ${r.year}")
            println(s"    Volume: ${r.volume}")
            println(s"    Page range: ${r.pageRange}")
          }
      }

      // TODO: sections and mentions

      println()
    }
  }
}

object LabeledDataFromPMC extends Datastores with Logging {
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
    "16" -> 1
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

  def get: Iterator[LabeledData] = set2version.iterator.flatMap { case (set, version) =>
    val zipFilePath = publicFile(s"PMCData$set.zip", version)
    Resource.using(new ZipFile(zipFilePath.toFile)) { case zipFile =>
      zipFile.entries().asScala.filter { entry =>
        !entry.isDirectory && entry.getName.endsWith(xmlExtension)
      }.map { entry =>
        (zipFilePath, entry.getName)
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
      override def inputStream: InputStream =
        getEntryAsInputStream(xmlEntryName.dropRight(xmlExtension.length) + ".pdf")

      override lazy val pdDoc: PDDocument = super.pdDoc // Overriding this to make it into a lazy val

      override def id = s"PMC:$xmlEntryName"

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