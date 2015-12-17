package org.allenai.scienceparse

import org.allenai.common.testkit.UnitSpec

class DocumentLayoutSpec extends UnitSpec {

  "weighted median" should "calculate correctly" in {
    assertResult(1.0)(DocumentLayout.weightedMedian(Vector((1.0, 100))))
    assertResult(1.0)(DocumentLayout.weightedMedian(Vector((1.0, 100), (2.0, 1), (3.0, 40))))
    assertResult(3.0)(DocumentLayout.weightedMedian(
      Vector((1.0, 20), (2.0, 30), (3.0, 10), (4.0, 20), (5.0, 25))
    ))
  }
}
