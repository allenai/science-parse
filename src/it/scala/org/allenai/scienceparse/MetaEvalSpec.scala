package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class MetaEvalSpec extends UnitSpec {
  "MetaEval" should "produce good P/R numbers" in {
    val parser = new Parser()
    val evaluationResult = Evaluation.evaluate(parser)
    Evaluation.printResults(evaluationResult)

    val minimumPR = Map(
      "abstract                 ".trim -> ((0.856, 0.856)),
      "abstractNormalized       ".trim -> ((0.856, 0.856)),
      "authorFullName           ".trim -> ((0.821, 0.805)),
      "authorFullNameNormalized ".trim -> ((0.851, 0.831)),
      "authorLastName           ".trim -> ((0.871, 0.847)),
      "authorLastNameNormalized ".trim -> ((0.889, 0.862)),
      "bibAll                   ".trim -> ((0.033, 0.031)),
      "bibAllButVenuesNormalized".trim -> ((0.619, 0.560)),
      "bibAllNormalized         ".trim -> ((0.044, 0.041)),
      "bibAuthors               ".trim -> ((0.726, 0.637)),
      "bibAuthorsNormalized     ".trim -> ((0.840, 0.743)),
      "bibCounts                ".trim -> ((1.000, 0.826)),
      "bibMentions              ".trim -> ((0.232, 0.218)),
      "bibMentionsNormalized    ".trim -> ((0.273, 0.245)),
      "bibTitles                ".trim -> ((0.795, 0.709)),
      "bibTitlesNormalized      ".trim -> ((0.796, 0.710)),
      "bibVenues                ".trim -> ((0.062, 0.051)),
      "bibVenuesNormalized      ".trim -> ((0.063, 0.052)),
      "bibYears                 ".trim -> ((0.933, 0.835)),
      "title                    ".trim -> ((0.427, 0.427)),
      "titleNormalized          ".trim -> ((0.842, 0.842))
      )

    val tolerance = 0.002
    evaluationResult.scienceParse.foreach { case (metric, eval) =>
      val (minimumP, minimumR) = minimumPR(metric.name)
      assert(eval.p > minimumP - tolerance, s"Evaluating precision for ${metric.name}")
      assert(eval.r > minimumR - tolerance, s"Evaluating recall for ${metric.name}")
    }
  }
}
