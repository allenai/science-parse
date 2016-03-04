package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class MetaEvalSpec extends UnitSpec {
  "MetaEval" should "produce good P/R numbers" in {
    val parser = new Parser()
    val evaluationResult = Evaluation.evaluate(parser)
    Evaluation.printResults(evaluationResult)

    val minimumPR = Map(
      "abstract                ".trim -> ((0.856, 0.856)),
      "abstractNormalized      ".trim -> ((0.856, 0.856)),
      "authorFullName          ".trim -> ((0.725, 0.693)),
      "authorFullNameNormalized".trim -> ((0.753, 0.718)),
      "authorLastName          ".trim -> ((0.833, 0.788)),
      "authorLastNameNormalized".trim -> ((0.855, 0.808)),
      "bibAll                  ".trim -> ((0.024, 0.014)),
      "bibAllNormalized        ".trim -> ((0.032, 0.020)),
      "bibAuthors              ".trim -> ((0.504, 0.349)),
      "bibAuthorsNormalized    ".trim -> ((0.683, 0.499)),
      "bibCounts               ".trim -> ((1.000, 0.659)),
      "bibMentions             ".trim -> ((0.230, 0.202)),
      "bibMentionsNormalized   ".trim -> ((0.257, 0.215)),
      "bibTitles               ".trim -> ((0.837, 0.607)),
      "bibTitlesNormalized     ".trim -> ((0.840, 0.609)),
      "bibVenues               ".trim -> ((0.062, 0.029)),
      "bibVenuesNormalized     ".trim -> ((0.062, 0.029)),
      "bibYears                ".trim -> ((0.909, 0.658)),
      "title                   ".trim -> ((0.408, 0.408)),
      "titleNormalized         ".trim -> ((0.807, 0.807))
      )

    val tolerance = 0.002
    evaluationResult.scienceParse.foreach { case (metric, eval) =>
      val (minimumP, minimumR) = minimumPR(metric.name)
      assert(eval.p > minimumP - tolerance)
      assert(eval.r > minimumR - tolerance)
    }
  }
}
