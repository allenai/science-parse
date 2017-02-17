package org.allenai.scienceparse

import java.io.ByteArrayInputStream
import java.security.{DigestInputStream, MessageDigest}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

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

  private case class SPServerException(val status: Int, message: String) extends Exception(message) {
    def getStatus = status // because all the other methods are named get*
  }

  override def handle(
    target: String,
    baseRequest: Request,
    request: HttpServletRequest,
    response: HttpServletResponse
  ): Unit = {
    try {
      handleRequest(target, baseRequest, request, response)
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
    request: HttpServletRequest,
    response: HttpServletResponse
  ): Unit = {
    baseRequest.getMethod match {
      case "GET" => target match {
        case "" | "/" =>
          response.setContentType("text/plain;charset=utf-8")
          response.getWriter.println("Usage: GET /v1/<paperid>[?format={LabeledData,ExtractedMetadata}]")
          response.getWriter.println("format is optional, defaults to LabeledData")
          response.setStatus(200)

        case SPServer.paperIdRegex(paperId) =>
          val formatString = request.getParameter("format")
          formatString match {
            case "LabeledData" | null =>
              response.setContentType("application/json")
              val labeledData =
                LabeledDataFromScienceParse.get(paperSource.getPdf(paperId), scienceParser).toJson.prettyPrint
              response.getOutputStream.write(labeledData.getBytes("UTF-8"))
            case "ExtractedMetadata" =>
              response.setContentType("application/json")
              prettyJsonWriter.writeValue(
                response.getOutputStream,
                scienceParser.doParse(paperSource.getPdf(paperId)))
            case _ =>
              throw SPServerException(400, s"Could not understand output format '$formatString'.")
          }
          response.setStatus(200)

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

          val formatString = request.getParameter("format")

          val newLocation = request.getRequestURL
          newLocation.append(paperId)
          if(formatString != null)
            newLocation.append(s"?format=$formatString")

          response.setStatus(201)

          // parse paper
          formatString match {
            case "LabeledData" | null =>
              response.addHeader("Location", newLocation.toString)
              response.setContentType("application/json")
              val labeledData =
                LabeledDataFromScienceParse.get(new ByteArrayInputStream(bytes), scienceParser).toJson.prettyPrint
              response.getOutputStream.write(labeledData.getBytes("UTF-8"))
            case "ExtractedMetadata" =>
              response.addHeader("Location", newLocation.toString)
              response.setContentType("application/json")
              prettyJsonWriter.writeValue(
                response.getOutputStream,
                scienceParser.doParse(new ByteArrayInputStream(bytes)))
            case _ =>
              throw SPServerException(400, s"Could not understand output format '$formatString'.")
          }

          // TODO: upload paper
      }

      case _ =>
        response.addHeader("Allow", "GET")
        throw SPServerException(405, "Method not allowed")
    }

    baseRequest.setHandled(true)
  }
}
