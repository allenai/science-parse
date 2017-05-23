package org.allenai.scienceparse

class InterleavingIterator[T](inners: Iterator[T]*) extends Iterator[T] {
  override def hasNext = inners.exists(_.hasNext)

  private var index = 0
  private def bumpIndex(): Unit = {
    index += 1
    index %= inners.size
  }

  while(!inners(index).hasNext)
    bumpIndex()

  private def moveToNextIndex(): Unit = {
    require(hasNext)
    bumpIndex()
    while(!inners(index).hasNext)
      bumpIndex()
  }

  override def next() = {
    require(inners(index).hasNext)
    val result = inners(index).next()
    if(hasNext)
      moveToNextIndex()
    result
  }
}
