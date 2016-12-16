package org.allenai.scienceparse

import java.security.MessageDigest

object Utilities {
  private val sha1HexLength = 40
  def toHex(bytes: Array[Byte]): String = {
    val sb = new scala.collection.mutable.StringBuilder(sha1HexLength)
    bytes.foreach { byte => sb.append(f"$byte%02x") }
    sb.toString
  }

  def shaForBytes(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    digest.update(bytes)
    toHex(digest.digest())
  }
}
