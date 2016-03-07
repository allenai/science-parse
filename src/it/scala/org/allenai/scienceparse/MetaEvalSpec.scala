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
      "bibAll                  ".trim -> ((0.020, 0.011)),
      "bibAllNormalized        ".trim -> ((0.024, 0.012)),
      "bibAuthors              ".trim -> ((0.504, 0.346)),
      "bibAuthorsNormalized    ".trim -> ((0.683, 0.497)),
      "bibCounts               ".trim -> ((1.000, 0.654)),
      "bibMentions             ".trim -> ((0.230, 0.202)),
      "bibMentionsNormalized   ".trim -> ((0.257, 0.213)),
      "bibTitles               ".trim -> ((0.832, 0.603)),
      "bibTitlesNormalized     ".trim -> ((0.834, 0.604)),
      "bibVenues               ".trim -> ((0.049, 0.017)),
      "bibVenuesNormalized     ".trim -> ((0.049, 0.017)),
      "bibYears                ".trim -> ((0.909, 0.654)),
      "title                   ".trim -> ((0.408, 0.408)),
      "titleNormalized         ".trim -> ((0.807, 0.807))
      )

    val tolerance = 0.002
    evaluationResult.scienceParse.foreach { case (metric, eval) =>
      val (minimumP, minimumR) = minimumPR(metric.name)
      assert(eval.p > minimumP - tolerance, s"Evaluating precision for ${metric.name}")
      assert(eval.r > minimumR - tolerance, s"Evaluating recall for ${metric.name}")
    }
  }
}
