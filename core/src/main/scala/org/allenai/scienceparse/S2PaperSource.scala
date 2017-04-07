package org.allenai.scienceparse

import java.io.{BufferedInputStream, IOException}
import java.net.SocketTimeoutException
import java.nio.file.{Files, Path, StandardCopyOption}

import org.allenai.common.Logging

import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}
import scalaj.http.{Http, HttpResponse}

object S2PaperSource extends PaperSource with Logging {

  private val random = new Random
  /** Gets a response from an HTTP server given a request. Retries if we think retrying might fix it. */
  private def withRetries[T](f: () => HttpResponse[T], retries: Int = 10): T = if (retries <= 0) {
    val result = f()
    if(result.isSuccess)
      result.body
    else
      throw new IOException(s"Got error ${result.code} (${result.statusLine}) from S2 server")
  } else {
    val sleepTime = random.nextInt(1000) + 2500 // sleep between 2.5 and 3.5 seconds
    // If something goes wrong, we sleep a random amount of time, to make sure that we don't slam
    // the server, get timeouts, wait for exactly the same amount of time on all threads, and then
    // slam the server again.

    Try(f()) match {
      case Failure(e: SocketTimeoutException) =>
        logger.warn(s"$e while querying S2. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Failure(e: IOException) =>
        logger.warn(s"Got IOException '${e.getMessage}' while querying S2. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Success(response) if response.isServerError =>
        logger.warn(s"Got response code '${response.statusLine}' while querying S2. $retries retries left.")
        Thread.sleep(sleepTime)
        withRetries(f, retries - 1)

      case Failure(e) => throw e

      case Success(response) => response.body
    }
  }

  override def getPdf(paperId: String) = {
    val key = paperId.take(4) + "/" + paperId.drop(4) + ".pdf"

    // We download to a temp file first. If we gave out an InputStream that comes directly from
    // S3, it would time out if the caller of this function reads the stream too slowly.
    val tempFile = withRetries { () =>
      Http(s"https://pdfs.semanticscholar.org/$key").timeout(30000, 30000).execute { is =>
        val result = Files.createTempFile(paperId + ".", ".paper.pdf")
        try {
          Files.copy(is, result, StandardCopyOption.REPLACE_EXISTING)
          result
        } catch {
          case NonFatal(e) =>
            Files.deleteIfExists(result)
            throw e
        }
      }
    }
    tempFile.toFile.deleteOnExit()
    new BufferedInputStream(Files.newInputStream(tempFile))
  }
}
