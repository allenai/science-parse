package org.allenai.scienceparse

import java.util.regex.Pattern
import scala.collection.JavaConverters._
import spray.json._

object JsonProtocol extends DefaultJsonProtocol {
  import java.util.{ List => JavaList }

  private def expected(name: String) = throw DeserializationException(s"Expected $name")

  private def optional[T >: Null](obj: JsValue)(implicit format: JsonFormat[T]): T =
    obj.convertTo[Option[T]].orNull

  implicit def javaListJsonFormat[T: JsonFormat]: RootJsonFormat[JavaList[T]] =
    new RootJsonFormat[JavaList[T]] {
      override def write(list: JavaList[T]): JsValue =
        JsArray(list.asScala.map(_.toJson): _*)

      override def read(json: JsValue): JavaList[T] = json match {
        case JsArray(values) => values.map { value => value.convertTo[T] }.toList.asJava
        case _ => expected("List<>")
      }
    }

  implicit object PatternJsonFormat extends RootJsonFormat[Pattern] {
    override def write(pattern: Pattern): JsValue = JsString(pattern.pattern())

    override def read(json: JsValue): Pattern = json match {
      case JsString(p) => Pattern.compile(p)
      case _ => expected("Pattern")
    }
  }

  implicit object ExtractedMetadataSourceJsonFormat extends RootJsonFormat[ExtractedMetadata.Source] {
    override def write(source: ExtractedMetadata.Source): JsValue = {
      JsString(source.name())
    }

    override def read(json: JsValue): ExtractedMetadata.Source = {
      json match {
        case JsString(name) => ExtractedMetadata.Source.valueOf(name)
        case _ => expected("ExtractedMetadata.Source")
      }
    }
  }

  implicit object SectionJsonFormat extends RootJsonFormat[Section] {
    override def write(section: Section): JsValue = JsObject(
      "heading" -> Option(section.heading).toJson,
      "text" -> section.text.toJson
    )

    override def read(json: JsValue): Section = json.asJsObject.getFields("heading", "text") match {
      case Seq(heading, JsString(text)) =>
        new Section(
          optional[String](heading),
          text)
      case _ => expected("Section")
    }
  }

  implicit object BibRecordJsonFormat extends RootJsonFormat[BibRecord] {
    override def write(bibRecord: BibRecord) = JsObject(
      "title" -> Option(bibRecord.title).toJson,
      "author" -> bibRecord.author.toJson,
      "venue" -> Option(bibRecord.venue).toJson,
      "citeRegEx" -> Option(bibRecord.citeRegEx).toJson,
      "shortCiteRegEx" -> Option(bibRecord.shortCiteRegEx).toJson,
      "year" -> bibRecord.year.toJson
    )

    override def read(json: JsValue): BibRecord = json.asJsObject.getFields(
      "title",
      "author",
      "venue",
      "citeRegEx",
      "shortCiteRegEx",
      "year"
    ) match {
      case Seq(
        title,
        author,
        venue,
        citeRegEx,
        shortCiteRegEx,
        JsNumber(year)
      ) =>
        new BibRecord(
          optional[String](title),
          author.convertTo[JavaList[String]],
          optional[String](venue),
          optional[Pattern](citeRegEx),
          optional[Pattern](shortCiteRegEx),
          year.intValue()
        )
      case _ => expected("BibRecord")
    }
  }

  implicit object CitationRecordJsonFormat extends RootJsonFormat[CitationRecord] {
    override def write(cr: CitationRecord): JsValue = JsObject(
      "referenceID" -> cr.referenceID.toJson,
      "context" -> cr.context.toJson,
      "startOffset" -> cr.startOffset.toJson,
      "endOffset" -> cr.endOffset.toJson
    )

    override def read(json: JsValue): CitationRecord = json.asJsObject.getFields(
      "referenceID",
      "context",
      "startOffset",
      "endOffset"
    ) match {
      case Seq(
        JsNumber(referenceID),
        JsString(context),
        JsNumber(startOffset),
        JsNumber(endOffset)
      ) => new CitationRecord(referenceID.toInt, context, startOffset.toInt, endOffset.toInt)
      case _ => expected("CitationRecord")
    }
  }

  implicit object ExtractedMetadataJsonFormat extends RootJsonFormat[ExtractedMetadata] {
    override def write(em: ExtractedMetadata): JsValue = JsObject(
      "source" -> Option(em.source).toJson,
      "title" -> Option(em.title).toJson,
      "authors" -> em.authors.toJson,
      "emails" -> em.emails.toJson,
      "sections" -> Option(em.sections).toJson,
      "references" -> Option(em.references).toJson,
      "referenceMentions" -> Option(em.referenceMentions).toJson,
      "year" -> em.year.toJson,
      "abstractText" -> Option(em.abstractText).toJson,
      "creator" -> Option(em.creator).toJson
    )

    override def read(json: JsValue): ExtractedMetadata = json.asJsObject.getFields(
      "source",
      "title",
      "authors",
      "emails",
      "sections",
      "references",
      "referenceMentions",
      "year",
      "abstractText",
      "creator"
    ) match {
      case Seq(
        source,
        title,
        authors,
        emails,
        sections,
        references,
        referenceMentions,
        JsNumber(year),
        abstractText,
        creator
      ) =>
        val em = new ExtractedMetadata(
          optional[String](title),
          authors.convertTo[JavaList[String]],
          null)
        em.source = optional[ExtractedMetadata.Source](source)
        em.emails = emails.convertTo[JavaList[String]]
        em.sections = optional[JavaList[Section]](sections)
        em.references = optional[JavaList[BibRecord]](references)
        em.referenceMentions = optional[JavaList[CitationRecord]](referenceMentions)
        em.year = year.intValue()
        em.abstractText = optional[String](abstractText)
        em.creator = optional[String](creator)
        em
      case _ => expected("ExtractedMetadata")
    }
  }

  // Some formats for LabeledData
  implicit val authorFormat = jsonFormat3(LabeledData.Author)
  implicit val sectionFormat = jsonFormat2(LabeledData.Section)
  implicit val referenceFormat = jsonFormat7(LabeledData.Reference)
  implicit val rangeFormat = jsonFormat2(LabeledData.Range)
  implicit val mentionFormat = jsonFormat3(LabeledData.Mention)
  implicit val labeledDataFormat = jsonFormat9(LabeledData.apply)
}
