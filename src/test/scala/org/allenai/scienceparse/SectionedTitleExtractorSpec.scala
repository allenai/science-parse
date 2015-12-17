package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class SectionedTitleExtractorSpec extends UnitSpec {

  def line(string: String): Line =
    Line(string.split(' ').map(Word(_, null, List(null))).toList, null, 0)

  "isPrefixed" should "match obvious prefixes" in {
    assert(SectionTitleExtractor.lineIsPrefixed(line("2.1.1 a")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("3. a b")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("4 a b")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("1.1. a")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("A. cat")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("A1 cat")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("C2. cat")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("Appendix cat")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("III. cat")))
    assert(SectionTitleExtractor.lineIsPrefixed(line("V cat")))

    assert(!SectionTitleExtractor.lineIsPrefixed(line("1")))
    assert(!SectionTitleExtractor.lineIsPrefixed(line("2-Tuple")))
    assert(!SectionTitleExtractor.lineIsPrefixed(line("cat")))
    assert(!SectionTitleExtractor.lineIsPrefixed(line("Introduction to the")))
  }

  "isList" should "match obvious lists" in {
    assert(SectionTitleExtractor.isList(line("Definition 2.1")))
    assert(SectionTitleExtractor.isList(line("Theorem IX.")))
    assert(SectionTitleExtractor.isList(line("Proposition 3")))

    assert(!SectionTitleExtractor.isList(line("2.1 Definition")))
    assert(!SectionTitleExtractor.isList(line("2.1")))
    assert(!SectionTitleExtractor.isList(line("Definition if the")))
  }
}
