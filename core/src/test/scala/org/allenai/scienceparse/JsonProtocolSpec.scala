package org.allenai.scienceparse

import java.util
import java.util.regex.Pattern

import org.allenai.common.testkit.UnitSpec

class JsonProtocolSpec extends UnitSpec {
  import spray.json._
  import JsonProtocol._

  "JsonProtocol" should "round trip basic content" in {
    val em = new ExtractedMetadata(
      "The Brand Personality of Rocks: A Critical Evaluation of a Brand Personality Scale",
      util.Arrays.asList("Mark Avis", "Sarah Forbes", "Shelagh Ferguson"),
      null)

    em.equals(em.toJson.convertTo[ExtractedMetadata])
  }

  it should "round trip empty authors" in {
    val em = new ExtractedMetadata(
      "The Brand Personality of Rocks: A Critical Evaluation of a Brand Personality Scale",
      util.Arrays.asList("", "Sarah Forbes", "Shelagh Ferguson"),
      null)

    em.equals(em.toJson.convertTo[ExtractedMetadata])
  }

  it should "round trip complex content" in {
    val em = new ExtractedMetadata(
      "The Brand Personality of Rocks: A Critical Evaluation of a Brand Personality Scale",
      util.Arrays.asList("Mark Avis", "Sarah Forbes", "Shelagh Ferguson"),
      null)
    em.year = 2014
    em.sections = util.Arrays.asList(
      new Section("Introduction", "In this paper, ..."),
      new Section(null, "Furthermore, ...")
    )
    em.abstractText = "Aaker’s (1997) brand personality (BP) scale is widely used in research and is an important foundation for the theory of BP."
    em.creator = "MS Paint"
    em.source = ExtractedMetadata.Source.META

    em.equals(em.toJson.convertTo[ExtractedMetadata])
  }

  it should "round trip empty content" in {
    // Empty content
    val em = new ExtractedMetadata(
      null,
      util.Arrays.asList(),
      null)
    em.sections = util.Arrays.asList(
      new Section("", ""),
      new Section(null, "")
    )
    em.abstractText = ""
    em.creator = ""

    em.equals(em.toJson.convertTo[ExtractedMetadata])
  }

  it should "round trip references" in {
    val em = new ExtractedMetadata(
      "The Brand Personality of Rocks: A Critical Evaluation of a Brand Personality Scale",
      util.Arrays.asList("Mark Avis", "Sarah Forbes", "Shelagh Ferguson"),
      null)

    em.references = util.Arrays.asList(
      new BibRecord(
        "Managing Brand Equity: Capitalizing on the Value of a Brand Name",
        util.Arrays.asList("Aaker, D"),
        "The Free Press",
        null,
        null,
        1991
      ),
      new BibRecord(
        "Dimensions of Brand Personality",
        util.Arrays.asList("Aaker, D"),
        "Journal of Marketing Research",
        Pattern.compile("Aaker et al\\."),
        Pattern.compile("\\[2\\]"),
        1997
      ),
      new BibRecord(
        null,
        util.Arrays.asList(),
        null,
        null,
        null,
        2001
      )
    )

    em.referenceMentions = util.Arrays.asList(
      new CitationRecord(
        1,
        "As [1] held these truths to be self-evident, ...",
        3,
        6
      )
    )

    em.equals(em.toJson.convertTo[ExtractedMetadata])
  }

  "LabeledData" should "round-trip through the JSON format" in {
    val sha = "a7c25298c607d5bf32e3301b6b209431e2a7f830"
    def getInputStream = this.getClass.getResourceAsStream(s"/$sha.pdf")
    val em = Parser.getInstance().doParse(getInputStream)
    val labeledData = LabeledData.fromExtractedMetadata(sha, em)
    val jsonString = labeledData.toJson.prettyPrint

    val labeledDataFromJson = jsonString.parseJson.convertTo[LabeledData]

    assertResult(labeledData.title)(labeledDataFromJson.title)
    assertResult(labeledData.authors)(labeledDataFromJson.authors)
    assertResult(labeledData.abstractText)(labeledDataFromJson.abstractText)
    assertResult(labeledData.year)(labeledDataFromJson.year)
    assertResult(labeledData.venue)(labeledDataFromJson.venue)
    assertResult(labeledData.sections)(labeledDataFromJson.sections)
    assertResult(labeledData.references)(labeledDataFromJson.references)
    //assertResult(labeledData.mentions)(labeledDataFromJson.mentions)
  }
}
