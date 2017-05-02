package org.allenai.scienceparse

import java.io._
import java.net.URL
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.zip.ZipFile

import org.allenai.common.{Logging, Resource, StreamClosingIterator}
import org.allenai.common.ParIterator._
import org.allenai.datastore.Datastores
import spray.json.{DefaultJsonProtocol, JsNull, JsObject, JsValue}
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

object LabeledDataJsonProtocol extends DefaultJsonProtocol {
  implicit val authorFormat = jsonFormat3(LabeledData.Author)
  implicit val sectionFormat = jsonFormat2(LabeledData.Section)
  implicit val referenceFormat = jsonFormat7(LabeledData.Reference)
  implicit val rangeFormat = jsonFormat2(LabeledData.Range)
  implicit val mentionFormat = jsonFormat3(LabeledData.Mention)
  implicit val labeledDataFormat = jsonFormat9(LabeledData.apply)
}

class LabeledPaper(
  inputStreamFn: => InputStream,
  val labels: LabeledData,
  paperIdOption: Option[String] = None
) {
  // input
  def inputStream = inputStreamFn
  lazy val bytes: Array[Byte] = Resource.using(inputStream) { IOUtils.toByteArray }
  lazy val paperId: String = paperIdOption.getOrElse(Utilities.shaForBytes(bytes))
  lazy val pdDoc: PDDocument = Resource.using(inputStream) { PDDocument.load }

  // This is not the same as toString, because it contains newline and is generally intended to be
  // read by humans.
  def readableString = {
    val builder = new StringBuilder

    builder ++= paperId
    builder += '\n'
    builder ++= labels.id
    builder += '\n'

    builder ++= s"Title: ${labels.title}\n"

    labels.authors match {
      case None => builder ++= "No authors\n"
      case Some(as) =>
        builder ++= "Authors:\n"
        as.foreach { a =>
          builder ++= s"  ${a.name}\n"
          a.email.foreach { e => builder ++= s"    $e\n" }
          a.affiliations.foreach { a => builder ++= s"    $a\n" }
        }
    }

    builder ++= s"Year: ${labels.year}\n"
    builder ++= s"Abstract: ${labels.abstractText}\n"

    labels.references match {
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

    labels.sections match {
      case None => builder ++= "No sections\n"
      case Some(ss) =>
        builder ++= "Sections:\n"
        ss.foreach { s =>
          val heading = StringUtils.makeSingleLine(s.heading.getOrElse("NO HEADING"))
          builder ++= s"  $heading\n"
          builder ++= s"    ${StringUtils.makeSingleLine(s.text)}\n"
        }
    }

    // TODO: mentions

    builder.toString
  }
}

case class LabeledData(
  /** ID to identify this labeled document. Must be unique. */
  id: String,

  // expected output
  // These are all Options. If they are not set, we don't have that field labeled for this paper.
  title: Option[String],
  authors: Option[Seq[LabeledData.Author]],
  venue: Option[String] = None,
  year: Option[Int] = None,
  abstractText: Option[String] = None,
  sections: Option[Seq[LabeledData.Section]] = None,
  references: Option[Seq[LabeledData.Reference]] = None,
  mentions: Option[Seq[LabeledData.Mention]] = None
) {
  import scala.compat.java8.OptionConverters._
  def javaYear = year.asPrimitive
  def javaTitle = title.asJava
  def javaAuthors = authors.map(_.asJavaCollection).asJava
  def javaAuthorNames = authors.map { as =>
    as.map(_.name).asJavaCollection
  }.asJava
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

  val empty = LabeledData("empty", None, None, None, None, None, None, None, None)

  def fromExtractedMetadata(labeledDataId: String, em: ExtractedMetadata) =
    LabeledData(
      labeledDataId,
      title = Option(em.title),
      authors = Option(em.authors).map { as =>
        as.asScala.map { a =>
          Author(a)
        }
      },
      year = if(em.year == 0) None else Some(em.year),
      venue = None,
      abstractText = Option(em.abstractText),
      sections = Option(em.sections).map { ss =>
        ss.asScala.map { s =>
          Section(Option(s.heading), s.text)
        }
      },
      references = Option(em.references).map { rs =>
        rs.asScala.map { r =>
          Reference(
            None,
            Option(r.title),
            Option(r.author).map(_.asScala.toSeq).getOrElse(Seq.empty),
            Option(r.venue),
            if (r.year == 0) None else Some(r.year),
            None,
            None
          )
        }
      },
      mentions = None // TODO
    )

  def dump(labeledData: Iterator[LabeledData]): Unit = {
    import spray.json._
    import LabeledDataJsonProtocol._

    // We don't time the first one, because it might load models.
    println(labeledData.next().toJson.prettyPrint)

    val startTime = System.currentTimeMillis()
    labeledData.map(_.toJson.prettyPrint).foreach(println)
    val endTime = System.currentTimeMillis()

    println(s"Completed in ${endTime - startTime} milliseconds")
  }
}

object LabeledPapersFromPMC extends Datastores with Logging {
  import LabeledData._

  private val xmlExtension = ".nxml"

  private val set2version =
    SortedMap((0x00 to 0xff).map(i => f"$i%02x" -> 2): _*)

  private val knownBrokenMetadataIds = Set(
    "PMC:PMCData00/Br_J_Cancer_1981_Dec_44(6)_798-809/brjcancer00447-0026.pdf",
    "PMC:PMCData00/Cancer_Imaging_2014_Oct_9_14(Suppl_1)_P10/1470-7330-14-S1-P10.pdf",
    "PMC:PMCData00/J_Exp_Med_1987_Sep_1_166(3)_668-677/je1663668.pdf"
  )

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
  def getCleaned = get.flatMap { labeledPaper =>
    val keep = labeledPaper.labels.title.isDefined &&
      labeledPaper.labels.authors.nonEmpty &&
      labeledPaper.labels.references.exists { refs =>
        refs.forall { ref =>
          ref.title.exists(_.nonEmpty) && ref.authors.nonEmpty
        }
      }
    if(keep) Some(labeledPaper) else None
  }

  private def pdfNameForXmlName(xmlName: String) =
    xmlName.dropRight(xmlExtension.length) + ".pdf"

  private val maxZipFilesInParallel = 2
  private val shaRegex = "^[0-9a-f]{40}$"r
  def get = getAll.filterNot(ld => knownBrokenMetadataIds.contains(ld.labels.id))
  def getAll: Iterator[LabeledPaper] = set2version.iterator.parMap({ case (set, version) =>
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

        def inputStream: InputStream = {
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

        val id = s"PMC:${pdfEntry.getName}"

        def parseYear(n: Node): Option[Int] = {
          try {
            val i = n.text.trim.dropWhile(_ == '(').takeWhile(_.isDigit).toInt
            if (i >= 1800 && i <= 2100) Some(i) else None
          } catch {
            case e: NumberFormatException =>
              logger.warn(s"Could not parse '${n.text}' as year")
              None
          }
        }

        val title: Option[String] =
          (articleMeta \ "title-group" \ "article-title").headOption.map(_.text)

        val authors: Option[Seq[Author]] = Some {
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

        val venue: Option[String] =
          (xml \ "front" \ "journal-meta" \ "journal-title-group" \ "journal-title").headOption.map(_.text)

        val year: Option[Int] = Iterable(
          ("pub-type", "ppub"),
          ("pub-type", "collection"),
          ("pub-type", "epub"),
          ("pub-type", "pmc-release"),
          ("publication-format", "print"),
          ("publication-format", "electronic")
        ).flatMap { case (attrName, attrValue) =>
          (articleMeta \ "pub-date") filter (_ \@ attrName == attrValue)
        }.flatMap(_ \ "year").flatMap(parseYear).headOption

        def parseSection(e: Node): Seq[LabeledData.Section] = {
          val label = (e \ "label").headOption.map(_.text)
          val title = (e \ "title").headOption.map(_.text)
          val sectionTitle = (label, title) match {
            case (None, None) => None
            case (Some(l), None) => Some(l.trim)
            case (None, Some(t)) => Some(t.trim)
            case (Some(l), Some(t)) => Some(l.trim + " " + t.trim)
          }
          val body = (e \ "p").map(_.text.replace('\n', ' ')).mkString("\n")

          Section(sectionTitle, body) +: (e \ "sec").flatMap(parseSection)  // parse sections recursively
        }

        val abstractText: Option[String] = (articleMeta \ "abstract").headOption.map { a =>
          val sections = parseSection(a)
          if (sections.isEmpty) {
            (a \\ "p").map(_.text.replace('\n', ' ')).mkString("\n")
          } else {
            sections.map(s => s"${s.heading.getOrElse("")}\n${s.text}".trim).mkString("\n\n")
          }
        }

        val sections: Option[Seq[LabeledData.Section]] =
          (xml \ "body").headOption.map(parseSection)

        val references: Option[Seq[Reference]] = Some {
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

        val mentions: Option[Seq[Mention]] = None // TODO

        new LabeledPaper(inputStream, LabeledData(
          id, title, authors, venue, year, abstractText, sections, references, mentions
        ), precalculatedPaperId)
      }.toArray
    }
  }, maxZipFilesInParallel).flatten

  def main(args: Array[String]): Unit =
    LabeledData.dump(LabeledPapersFromPMC.get.take(100).toSeq.sortBy(_.paperId).map(_.labels).iterator)
}

object LabeledDataFromDBLP extends Datastores {
  def apply = get

  def get: Iterator[LabeledPaper] = getFromGroundTruth(publicFile("productionGroundTruth.json", 1))

  def getFromGroundTruth(groundTruthFile: Path) = {
    val jsonLines = StreamClosingIterator {
      Files.newInputStream(groundTruthFile)
    } {
      import spray.json._
      Source.fromInputStream(_, "UTF-8").getLines().map(_.parseJson.asJsObject)
    }

    jsonLines.map { js =>
      import DefaultJsonProtocol._

      val Seq(jsId, jsTitle, jsAuthors, jsYear) = js.getFields("id", "title", "authors", "year")

      val id = jsId.convertTo[String]
      val title = jsTitle.convertTo[String]
      val authors = jsAuthors.convertTo[Seq[String]]
      val year = jsYear.convertTo[Int]

      new LabeledPaper(
        PaperSource.getDefault.getPdf(id),
        LabeledData.empty.copy(
          id = s"DBLP:$id",
          title = Some(title),
          authors = Some(authors.map(LabeledData.Author(_))),
          year = Some(year)
        ),
        Some(id)
      )
    }
  }
}

object LabeledPapersFromResources extends Datastores {
  import LabeledData._

  def apply = get

  def get: Iterator[LabeledPaper] = {
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
      val ld = LabeledData(
        id = s"Resources:$pid",
        title = paperId2titles.get(pid).flatMap(_.headOption),
        authors = paperId2authors.get(pid).map { authorNames =>
          authorNames.map { authorName => Author(authorName) }
        },
        year = None,
        venue = None,
        abstractText = paperId2abstracts.get(pid).flatMap(_.headOption),
        sections = None,
        references = paperId2bibliographies.get(pid).map { bibStrings =>
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
        },
        mentions = None // TODO: we might be able to get mentions from the data that Isaac created back in the day
      )

      new LabeledPaper(
        Files.newInputStream(pdfDirectory.resolve(s"$pid.pdf")),
        ld,
        Some(pid)
      )
    }
  }

  def main(args: Array[String]): Unit = LabeledData.dump(LabeledPapersFromResources.get.map(_.labels))
}

object LabeledPapersFromScienceParse extends Logging {
  def get(input: => InputStream, parser: Parser = Parser.getInstance()) = {

    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    val bytes = Resource.using(new DigestInputStream(input, digest))(IOUtils.toByteArray)
    val id = Utilities.toHex(digest.digest())
    val labeledPaperId = s"SP:$id"

    val ld = try {
      val output = Resource.using(new ByteArrayInputStream(bytes))(parser.doParse)
      LabeledData.fromExtractedMetadata(labeledPaperId, output)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Error while science-parsing: $e")
        LabeledData.empty
    }

    new LabeledPaper(input, ld, Some(id))
  }

  def main(args: Array[String]): Unit = {
    val fromPMC = LabeledPapersFromPMC.get.take(100).toSeq.sortBy(_.paperId)
    val fromSp = fromPMC.par.map(labeledPaper => get(labeledPaper.inputStream))
    LabeledData.dump(fromSp.iterator.map(_.labels))
  }
}

class LabeledPapersFromGrobidServer(grobidServerUrl: URL) extends Logging {
  private val cachedGrobidServer = new CachedGrobidServer(grobidServerUrl)

  def get(input: => InputStream) = {
    val bytes = IOUtils.toByteArray(input)
    val pid = Utilities.shaForBytes(bytes)
    val labeledDataId = s"Grobid:$grobidServerUrl/$pid"

    val ld = try {
      val em = Resource.using(cachedGrobidServer.getExtractions(bytes)) { is =>
        GrobidParser.parseGrobidXml(is, grobidServerUrl.toString)
      }
      LabeledData.fromExtractedMetadata(labeledDataId, em)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Error '${e.getMessage}' from Grobid for paper $pid")
        LabeledData.empty
    }

    new LabeledPaper(input, ld, Some(pid))
  }
}

object LabeledPapersFromGrobidServer {
  def main(args: Array[String]): Unit = {
    val url = new URL(args.headOption.getOrElse("http://localhost:8080"))
    val labeledDataFromGrobidServer = new LabeledPapersFromGrobidServer(url)

    val fromPMC = LabeledPapersFromPMC.get.take(100).toSeq.sortBy(_.paperId)
    val fromGrobid = fromPMC.par.map(
      labeledDataFromPMC => labeledDataFromGrobidServer.get(labeledDataFromPMC.inputStream))
    LabeledData.dump(fromGrobid.iterator.map(_.labels))
  }
}

object LabeledPapersFromOldGrobid extends Datastores {
  def get: Iterator[LabeledPaper] = {
    val grobidExtractions = publicDirectory("GrobidExtractions", 1)
    val pdfDirectory = publicDirectory("PapersTestSet", 3)
    Files.newDirectoryStream(grobidExtractions, "*.xml").iterator().asScala.map { file =>
      val paperId = FilenameUtils.getBaseName(file.toString)
      val em = GrobidParser.parseGrobidXml(file)
      new LabeledPaper(
        Files.newInputStream(pdfDirectory.resolve(s"$paperId.pdf")),
        LabeledData.fromExtractedMetadata(
          s"OldGrobid:$paperId",
          em
        ),
        Some(paperId))
    }
  }
}
