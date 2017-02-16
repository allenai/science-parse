package org.allenai.scienceparse

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.allenai.common.Logging
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler

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

  case class MetadataWrapper(metadata: ExtractedMetadata)

  val jsonWriter = new ObjectMapper() with ScalaObjectMapper
  jsonWriter.registerModule(DefaultScalaModule)
  val prettyJsonWriter = jsonWriter.writerWithDefaultPrettyPrinter()

  override def handle(
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
          lazy val extractedMetadata =
            scienceParser.doParseWithTimeout(paperSource.getPdf(paperId), 60 * 1000)

          val formatString = request.getParameter("format")
          formatString match {
            case "LabeledData" | null =>
              response.setContentType("application/json")
              prettyJsonWriter.writeValue(
                response.getOutputStream,
                extractedMetadata)
              response.setStatus(200)
            case _ =>
              response.setContentType("text/plain;charset=utf-8")
              response.getWriter.println(s"Could not understand outout format $formatString.")
              response.setStatus(400)
          }

          response.setContentType("text/plain;charset=utf-8")
          response.getWriter.println(paperId)
          response.setStatus(200)
        case _ =>
          response.setContentType("text/plain;charset=utf-8")
          response.getWriter.println("When someone is searching, said Siddhartha, then it might easily happen that the only thing his eyes still see is that what he searches for, that he is unable to find anything, to let anything enter his mind, because he always thinks of nothing but the object of his search, because he has a goal, because he is obsessed by the goal. Searching means: having a goal. But finding means: being free, being open, having no goal. You, oh venerable one, are perhaps indeed a searcher, because, striving for your goal, there are many things you don't see, which are directly in front of your eyes.")
          response.setStatus(404)
      }

      case _ =>
        response.setStatus(405) // Method not allowed
        response.addHeader("Allow", "GET")
    }

    baseRequest.setHandled(true)
  }
}
