package org.allenai.scienceparse

import java.io.{InputStream, ByteArrayInputStream, IOException}
import java.net.{SocketTimeoutException, URL}
import java.nio.file.{NoSuchFileException, Paths, Files}
import java.util.zip.{GZIPOutputStream, GZIPInputStream}

import org.allenai.common.{Logging, Resource}
import org.allenai.datastore.Datastores
import org.apache.commons.io.{FileUtils, IOUtils}

import scala.util.control.NonFatal
import scala.util.{Success, Failure, Try, Random}
import scalaj.http.{Http, MultiPart, HttpResponse}


class CachedGrobidServer(url: URL) extends Logging with Datastores {
  private val cacheDir = {
    val dirName = url.toString.replaceAll("[^\\w-.:]+", "#")
    Files.createDirectories(CachedGrobidServer.cacheDir)
    val dir = CachedGrobidServer.cacheDir.resolve(dirName)
    if(!Files.exists(dir)) {
      // Warm the cache, so for most evaluations we don't need to have a running Grobid server at
      // all.
      val warmCacheDir = publicDirectory("GrobidServerCache", 1)
      FileUtils.copyDirectory(warmCacheDir.toFile, dir.toFile)
    }
    dir
  }

  private val random = new Random
  /** Gets a response from an HTTP server given a request. Retries if we think retrying might fix it. */
  private def withRetries[T](f: () => HttpResponse[T], retries: Int = 10): HttpResponse[T] = if (retries <= 0) {
    f()
  } else {
    val sleepTime = random.nextInt(1000) + 2500 // sleep between 2.5 and 3.5 seconds
    // If something goes wrong, we sleep a random amount of time, to make sure that we don't slam
    // the server, get timeouts, wait for exactly the same amount of time on all threads, and then
    // slam the server again.

    Try(f()) match {
      case Failure(e: SocketTimeoutException) =>
        logger.warn(s"$e while querying Grobid. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Failure(e: IOException) =>
        logger.warn(s"Got IOException '${e.getMessage}' while querying Grobid. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Success(response) if response.isServerError =>
        logger.warn(s"Got response code '${response.statusLine}' while querying Grobid. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Failure(e) => throw e

      case Success(response) => response
    }
  }

  // Note: This is not thread safe if you have two threads or processes ask for the same file at
  // the same time.
  def getExtractions(bytes: Array[Byte]): InputStream = {
    val paperId = Utilities.shaForBytes(bytes)

    val cacheFile = cacheDir.resolve(paperId + ".xml.gz")
    try {
      if (Files.size(cacheFile) == 0)
        throw new IOException(s"Paper $paperId is tombstoned")
      else
        new GZIPInputStream(Files.newInputStream(cacheFile))
    } catch {
      case _: NoSuchFileException =>
        logger.debug(s"Cache miss for $paperId")
        try {
          val response = withRetries { () =>
            val multipart = MultiPart("input", s"$paperId.pdf", "application/octet-stream", bytes)
            Http(url + "/processFulltextDocument").timeout(60000, 60000).postMulti(multipart).asBytes
          }
          val bais = new ByteArrayInputStream(response.body)
          Resource.using(new GZIPOutputStream(Files.newOutputStream(cacheFile))) { os =>
            IOUtils.copy(bais, os)
          }
          bais.reset()
          bais
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Tombstoning $paperId because of the following error:", e)
            Files.deleteIfExists(cacheFile)
            Files.createFile(cacheFile)
            throw e
        }
    }
  }
}

object CachedGrobidServer {
  val cacheDir = Files.createDirectories(
    Paths.get(
      System.getProperty("java.io.tmpdir"),
      this.getClass.getSimpleName.stripSuffix("$")))
}
