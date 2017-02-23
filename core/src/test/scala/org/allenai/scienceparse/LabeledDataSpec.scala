package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

import spray.json._

class LabeledDataSpec extends UnitSpec {
  "LabeledData" should "round-trip through the JSON format" in {
    val sha = "a7c25298c607d5bf32e3301b6b209431e2a7f830"
    def getInputStream = this.getClass.getResourceAsStream(s"/$sha.pdf")
    val em = Parser.getInstance().doParse(getInputStream)
    val labeledData = LabeledData.fromExtractedMetadata(getInputStream, sha, em)
    val json = labeledData.toJson.prettyPrint

    val labeledDataFromJson = LabeledData.fromJson(json.parseJson, getInputStream)

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
