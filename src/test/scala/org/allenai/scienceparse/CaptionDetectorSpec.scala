package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec
import org.allenai.scienceparse.CaptionDetector.CandidateFilter

class CaptionDetectorSpec extends UnitSpec {
  var onLine = 0
  def line(str: String): Line = {
    onLine += 1
    Line(str.split(" ").map(s =>
      Word(s, Box(0, 0, 10, 10), List(null))).toList, Box(0, 0, 10, 10), onLine)
  }

  "find candidates" should "find correct candidates" in {
    val allLines = List(
      List(
        line("blah Figure 1p"),
        line("FIG 32:"),
        line("nothing of interest")
      ),
      List(
        line("Table 1. ect"),
        line("Figure 2I bad OCR"),
        line("Fig. 5 test"),
        line("TABLE IV roman numeral test"),
        line("Fig 9 test")
      )
    )

    val textPage = PageWithClassifiedText(
      1,
      allLines.map(lines => Paragraph(lines)),
      ClassifiedText()
    )

    val candidates = CaptionDetector.findCaptionCandidates(Seq(textPage)).sortBy(_.line.lineNumber)

    assertResult(CaptionStart("FIG", "32", FigureType.Figure, ":",
      allLines(0)(1), Some(allLines(0)(2)), 1, false, true))(candidates(0))
    assert(candidates(0).colonMatch)
    assert(!candidates(0).periodMatch)
    assert(candidates(0).allCapsFig)

    assertResult(CaptionStart("Table", "1", FigureType.Table, ".",
      allLines(1)(0), Some(allLines(1)(1)), 1, true, false))(candidates(1))
    assert(candidates(1).periodMatch)

    assertResult((false, false))((candidates(2).colonMatch, candidates(2).periodMatch))
    assert(!candidates(2).colonMatch)
    assert(!candidates(2).periodMatch)

    assertResult("5")(candidates(3).name)
    assert(candidates(3).figAbbreviated)

    assertResult("IV")(candidates(4).name)
    assert(candidates(4).allCapsTable)
    assert(!candidates(4).lineEnd)

    assertResult("9")(candidates(5).name)
    assert(!candidates(5).figAbbreviated)
    assert(!candidates(5).allCapsTable)
  }

  "filter candidates" should "filter candidates correctly" in {
    var lineNumber = 0

    // Stub CandidateFilter the rejects captions that match a blacklist
    case class MockFilter(disallowHeaders: String*) extends CandidateFilter {
      val name: String = s"Reject $disallowHeaders"
      override def accept(cc: CaptionStart): Boolean = !disallowHeaders.contains(cc.header)
    }

    // Builds mock CaptionStarts
    def cs(name: String, header: String, paragraphStart: Boolean = false): CaptionStart = {
      lineNumber += 1
      CaptionStart(header, name, FigureType.Figure, "", null, None, 0, paragraphStart, false)
    }

    val trueCaptions = Seq("c1", "c2", "c3").map(name => cs(name, name))
    val decoyC1 = cs("c1", "dc1")
    val decoyC12 = cs("c1", "dc2")
    val decoyC2 = cs("c2", "dc2")

    // No need to filter anything
    assertResult(trueCaptions.toSet)(CaptionDetector.selectCaptionCandidates(
      trueCaptions,
      Seq(MockFilter(decoyC1.header), MockFilter("dc3"))
    ).toSet)

    // Two decoys + two filters that can filter them out
    assertResult(trueCaptions.toSet)(CaptionDetector.selectCaptionCandidates(
      trueCaptions ++ Seq(decoyC1, decoyC2),
      Seq(MockFilter(decoyC1.header), MockFilter(decoyC2.header))
    ).toSet)

    // Two decoys but only one filter that can be applied without removing real captions
    assertResult(trueCaptions.toSet + decoyC1)(CaptionDetector.selectCaptionCandidates(
      trueCaptions ++ Seq(decoyC1, decoyC2),
      Seq(
        MockFilter(decoyC1.header, "c3"),
        MockFilter(decoyC1.header, trueCaptions(0).header), MockFilter(decoyC12.header)
      )
    ).toSet)

    // Requires applying multiples filters to prune the decoys for 'c1'
    assertResult(trueCaptions.toSet)(CaptionDetector.selectCaptionCandidates(
      trueCaptions ++ Seq(decoyC1, decoyC12),
      Seq(MockFilter(decoyC1.header), MockFilter(decoyC12.header))
    ).toSet)

    // Should fall back to paragraph start heuristic
    assertResult(Set(trueCaptions.head))(CaptionDetector.selectCaptionCandidates(
      Seq(trueCaptions.head, decoyC1, trueCaptions.head.copy(paragraphStart = false)),
      Seq(MockFilter(decoyC1.header))
    ).toSet)
  }
}
