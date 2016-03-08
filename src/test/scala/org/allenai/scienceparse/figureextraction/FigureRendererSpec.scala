package org.allenai.scienceparse.figureextraction

import java.awt.image.BufferedImage

import org.allenai.common.testkit.UnitSpec

class FigureRendererSpec extends UnitSpec {

  "expandFigureBounds" should "work" in {
    val img = new BufferedImage(9, 9, BufferedImage.TYPE_INT_BGR)
    img.getRaster.setPixels(0, 0, 9, 9, Array.fill(100 * 3)(0xffffffff))
    Range(2, 7).foreach(x => img.setRGB(x, 4, 0))
    assertResult((2, 4, 6, 4))(FigureRenderer.expandFigureBounds(3, 4, 4, 4, Seq(), 0, img))
    Range(2, 7).foreach(y => img.setRGB(4, y, 0)) // Forms a cross in the center
    assertResult((2, 2, 6, 6))(FigureRenderer.expandFigureBounds(3, 2, 5, 4, Seq(), 0, img))
    assertResult((2, 2, 5, 6))(
      FigureRenderer.expandFigureBounds(3, 3, 4, 4, Seq(Box(5.1, 0, 5.1, 10)), 0, img)
    )
    assertResult((2, 2, 4, 6))(
      FigureRenderer.expandFigureBounds(3, 3, 4, 4, Seq(Box(5.1, 0, 5.1, 10)), 1, img)
    )

    // Move top pixel of the of cross one pixel to the right
    img.setRGB(4, 2, 0xffffffff)
    img.setRGB(3, 2, 0)
    // Now we should not expand all they way to the top since their is link
    // between row 2 and row 3
    assertResult((1, 3, 6, 6))(
      FigureRenderer.expandFigureBounds(1, 4, 5, 5, Seq(), 0, img)
    )
  }
}
