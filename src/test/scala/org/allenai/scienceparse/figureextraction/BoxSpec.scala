package org.allenai.scienceparse.figureextraction

import org.allenai.common.testkit.UnitSpec

class BoxSpec extends UnitSpec {

  "intersection" should "calculate correctly" in {
    val b1 = Box(5, 5, 10, 10)
    val b2 = Box(0, 0, 4, 10)
    val b3 = Box(0, 0, 6, 10)
    val b4 = Box(5, 0, 6, 20)
    assert(!b1.intersects(b2))
    assert(!b1.intersects(b2, 0.5f))
    assert(b1.intersects(b2, 1))
    assert(b1.intersects(b3))
    assert(b1.intersects(b3), 10)
    assert(b1.intersects(b4))
  }

  "merge" should "merge boxes correctly" in {
    val b1 = Box(2, 2, 8, 3)
    val b2 = Box(6, 1.5f, 9, 7)
    val b3 = Box(1, 5, 2, 6)
    val b4 = Box(10, 10, 11, 11)
    val merged = Box.mergeBoxes(List(b1, b2, b3, b4), 2)
    assertResult(merged.size)(2)

    assert(merged.contains(b4))
    assert(merged.contains(Box(1, 1.5f, 9, 7))) // boxes 1-3 merged

    // All boxes merged
    assertResult(Seq(Box(1, 1.5f, 11, 11)))(Box.mergeBoxes(List(b1, b2, b3, b4), 3))
  }

  "crop" should "crop correctly" in {
    val b1 = Box(2, 2, 3, 3)
    val b2 = Box(4, 4, 5, 5)
    val b3 = Box(2, 1, 3, 10)
    assertResult(Some(Box(2, 2, 5, 5)))(Box.crop(Box(1, 1, 10, 10), Seq(b1, b2)))
    assertResult(None)(Box.crop(Box(10, 10, 11, 10), Seq(b1, b2)))
    assertResult(Some(Box(2, 1, 3, 5)))(Box.crop(Box(1, 0, 5, 5), Seq(b3)))
  }

  "findEmptyHorizontalBlocks" should "work" in {
    val box = Box(0, 0, 20, 20)
    val c1 = Box(4, 4, 5, 6)
    val c2 = Box(18, 8, 21, 10)
    val c3 = Box(4, 6, 5, 9)
    val c4 = Box(16, 18, 18, 30)
    val allContent = Seq(c1, c2, c3, c4)
    assertResult(Seq(box))(Box.findEmptyHorizontalBlocks(box, Seq()))
    val emptyBlocks = Box.findEmptyHorizontalBlocks(box, allContent)
    assertResult(Set(Box(0, 0, 20, 4), Box(0, 10, 20, 18)))(emptyBlocks.toSet)
    assertResult(Seq())(Box.findEmptyHorizontalBlocks(box, emptyBlocks ++ allContent))
  }
}
