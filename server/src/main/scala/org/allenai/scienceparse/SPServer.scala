package org.allenai.scienceparse

import java.io.ByteArrayInputStream
import java.security.{DigestInputStream, MessageDigest}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.allenai.common.{Resource, Logging}
import org.apache.commons.io.IOUtils
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler

import scala.util.control.NonFatal

object SPServer {
  def main(args: Array[String]): Unit = {
    val paperSource = new RetryPaperSource(ScholarBucketPaperSource.getInstance())
    val scienceParser = Parser.getInstance()

    val server = new Server(8080)
    server.setHandler(new SPServer(paperSource, scienceParser))
    server.start()
    server.join()
  }

  val paperIdRegex = "^/v1/([a-f0-9]{40})$".r
}

class SPServer(
  private val paperSource: PaperSource,
  private val scienceParser: Parser
) extends AbstractHandler with Logging {

  private val jsonWriter = new ObjectMapper() with ScalaObjectMapper
  jsonWriter.registerModule(DefaultScalaModule)
  private val prettyJsonWriter = jsonWriter.writerWithDefaultPrettyPrinter()

  private val bucket: String = "ai2-s2-pdfs"
  private val s3: AmazonS3 = new AmazonS3Client

  private case class SPServerException(val status: Int, message: String) extends Exception(message) {
    def getStatus = status // because all the other methods are named get*
  }

  private case class Response(
    status: Int,
    contentType: String,
    content: Array[Byte],
    headers: Map[String, String] = Map.empty
  )

  private object Response {
    def plainText(content: String, status: Int = 200) =
      Response(status, "text/plain;charset=utf-8", content.getBytes("UTF-8"))
  }

  override def handle(
    target: String,
    baseRequest: Request,
    request: HttpServletRequest,
    response: HttpServletResponse
  ): Unit = {
    try {
      val spResponse = handleRequest(target, baseRequest, request)
      response.setContentType(spResponse.contentType)
      response.setStatus(spResponse.status)
      spResponse.headers.foreach { case (k, v) => response.addHeader(k, v) }
      response.getOutputStream.write(spResponse.content)
      baseRequest.setHandled(true)
    } catch {
      case e: SPServerException =>
        response.setContentType("text/plain;charset=utf-8")
        response.setStatus(e.getStatus)
        response.getWriter.println(e.getMessage)
        baseRequest.setHandled(true)
      case NonFatal(e) =>
        logger.warn(s"Uncaught exception: ${e.getMessage}", e)
        response.setContentType("text/plain;charset=utf-8")
        response.setStatus(500)
        response.getWriter.println(e.getMessage)
        baseRequest.setHandled(true)
    }
  }

  private def handleRequest(
    target: String,
    baseRequest: Request,
    request: HttpServletRequest
  ): Response = {
    baseRequest.getMethod match {
      case "GET" => target match {
        case "" | "/" =>
          Response.plainText(
            "Usage: GET /v1/<paperid>[?format={LabeledData,ExtractedMetadata}]\n" +
            "format is optional, defaults to LabeledData")

        case SPServer.paperIdRegex(paperId) =>
          val formatString = request.getParameter("format")
          val content = formatString match {
            case "LabeledData" | null =>
              val labeledData =
                LabeledDataFromScienceParse.get(paperSource.getPdf(paperId), scienceParser).toJson.prettyPrint
              labeledData.getBytes("UTF-8")
            case "ExtractedMetadata" =>
              prettyJsonWriter.writeValueAsBytes(scienceParser.doParse(paperSource.getPdf(paperId)))
            case _ =>
              throw SPServerException(400, s"Could not understand output format '$formatString'.")
          }
          Response(200, "application/json", content)

        case _ =>
          throw SPServerException(404, "When someone is searching, said Siddhartha, then it might easily happen that the only thing his eyes still see is that what he searches for, that he is unable to find anything, to let anything enter his mind, because he always thinks of nothing but the object of his search, because he has a goal, because he is obsessed by the goal. Searching means: having a goal. But finding means: being free, being open, having no goal. You, oh venerable one, are perhaps indeed a searcher, because, striving for your goal, there are many things you don't see, which are directly in front of your eyes.")
      }

      case "POST" => target match {
        case "/v1" =>
          // calculate SHA of paper
          val digest = MessageDigest.getInstance("SHA-1")
          digest.reset()
          val bytes = Resource.using(new DigestInputStream(request.getInputStream, digest))(IOUtils.toByteArray)
          val paperId = Utilities.toHex(digest.digest())

          // parse paper
          val formatString = request.getParameter("format")
          val content = formatString match {
            case "LabeledData" | null =>
              val labeledData =
                LabeledDataFromScienceParse.get(
                  new ByteArrayInputStream(bytes), scienceParser).toJson.prettyPrint
              labeledData.getBytes("UTF-8")
            case "ExtractedMetadata" =>
              prettyJsonWriter.writeValueAsBytes(
                scienceParser.doParse(
                  new ByteArrayInputStream(bytes)))
            case _ =>
              throw SPServerException(400, s"Could not understand output format '$formatString'.")
          }

          // upload to S3
          val key = paperId.substring(0, 4) + "/" + paperId.substring(4) + ".pdf"
          val alreadyUploaded = try {
            s3.getObjectMetadata(bucket, key)
            true
          } catch {
            case e: AmazonServiceException if e.getStatusCode == 404 =>
              false
          }
          if(!alreadyUploaded) {
            val metadata = new ObjectMetadata()
            metadata.setCacheControl("public, immutable")
            metadata.setContentType("application/pdf")
            metadata.setContentLength(bytes.size)
            s3.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata)
          }

          // set new location
          val newLocation = request.getRequestURL
          newLocation.append('/')
          newLocation.append(paperId)
          if(formatString != null)
            newLocation.append(s"?format=$formatString")
          val headers = scala.collection.mutable.Map[String, String]()
          headers.update("Location", newLocation.toString)

          // Turns out you are allowed to return a 200 from a POST, if you have a Content-Location
          // header. https://tools.ietf.org/html/rfc7231#section-4.3.3
          headers.update("Content-Location", newLocation.toString)
          Response(
            200,
            "application/json",
            content,
            headers.toMap
          )
      }

      case _ =>
        Response(
          405,
          "text/plain;charset=utf-8",
          "Method not allowed".getBytes("UTF-8"),
          Map("Allow" -> "GET")
        )
    }
  }
}
