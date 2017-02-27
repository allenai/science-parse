package org.allenai.scienceparse

import java.io.{File, StringWriter, InputStream, ByteArrayInputStream}
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
import scopt.OptionParser

import scala.util.control.NonFatal
import scala.util.matching.Regex
import scala.collection.JavaConverters._

object SPServer extends Logging {
  def main(args: Array[String]): Unit = {
    case class Config(
      modelFile: Option[File] = None,
      bibModelFile: Option[File] = None,
      gazetteerFile: Option[File] = None,
      paperDirectory: Option[File] = None
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      opt[File]('m', "model") action { (m, c) =>
        c.copy(modelFile = Some(m))
      } text "Specifies the model file to evaluate. Defaults to the production model"

      opt[File]('b', "bibModel") action { (m, c) =>
        c.copy(bibModelFile = Some(m))
      } text "Specifies the model for bibliography parsing. Defaults to the production model"

      opt[File]('g', "gazetteer") action { (g, c) =>
        c.copy(gazetteerFile = Some(g))
      } text "Specifies the gazetteer file. Defaults to the production one. Take care not to use a gazetteer that you also used to train the model."

      opt[File]('p', "paperDirectory") action { (p, c) =>
        c.copy(paperDirectory = Some(p))
      } text "Specifies a directory with papers in them. If this is not specified, or a paper can't be found in the directory, we fall back to getting the paper from the bucket."

      help("help") text "Prints help text"
    }

    parser.parse(args, Config()).foreach { config =>
      val start = System.currentTimeMillis()
      val modelFile = config.modelFile.map(_.toPath).getOrElse(Parser.getDefaultProductionModel)
      val bibModelFile = config.bibModelFile.map(_.toPath).getOrElse(Parser.getDefaultBibModel)
      val gazetteerFile = config.gazetteerFile.map(_.toPath).getOrElse(Parser.getDefaultGazetteer)
      val scienceParser = new Parser(modelFile, gazetteerFile, bibModelFile)
      val end = System.currentTimeMillis()
      logger.info(s"Loaded science parser in ${end - start}ms")

      val paperSource = {
        val bucketSource = new RetryPaperSource(ScholarBucketPaperSource.getInstance())
        config.paperDirectory match {
          case None => bucketSource
          case Some(dir) =>
            new FallbackPaperSource(
              new DirectoryPaperSource(dir),
              bucketSource
            )
        }
      }

      val server = new Server(8080)
      server.setHandler(new SPServer(paperSource, scienceParser))
      server.start()
      server.join()
    }
  }

  val paperIdRegex = "^/v1/([a-f0-9]{40})$".r
}

class SPServer(
  private val paperSource: PaperSource,
  private val scienceParser: Parser
) extends AbstractHandler with Logging {

  private val jsonMapper = new ObjectMapper() with ScalaObjectMapper
  jsonMapper.registerModule(DefaultScalaModule)
  private val prettyJsonWriter = jsonMapper.writerWithDefaultPrettyPrinter()

  private val bucket: String = "ai2-s2-pdfs"
  private val s3: AmazonS3 = new AmazonS3Client


  //
  // Request / response stuff
  //

  private case class SPServerException(val status: Int, message: String) extends Exception(message) {
    def getStatus = status // because all the other methods are named get*
  }

  private case class SPRequest(
    target: String,
    method: String,
    queryParams: Map[String, String],

    inputStream: () => InputStream,
    contentType: String,
    requestUrl: () => StringBuffer
  )

  private object SPRequest {
    def apply(target: String, baseRequest: Request, request: HttpServletRequest): SPRequest = {
      val parameterMap = baseRequest.getParameterMap.asScala.map { case (key, values) =>
        if (values.length != 1)
          throw SPServerException(400, s"Two values for query parameter $key")
        key -> values.head
      }.toMap

      new SPRequest(
        target,
        baseRequest.getMethod,
        parameterMap,
        request.getInputStream,
        request.getContentType,
        request.getRequestURL)
    }
  }

  private case class SPResponse(
    status: Int,
    contentType: String = "",
    content: Array[Byte] = Array.empty,
    headers: Map[String, String] = Map.empty
  )

  private object SPResponse {
    def plainText(content: String, status: Int = 200) =
      SPResponse(status, "text/plain;charset=utf-8", content.getBytes("UTF-8"))

    lazy val Success = SPResponse(200)
  }


  //
  // Routing stuff
  //

  private trait Route {
    def canHandleTarget(target: String): Boolean
    def canHandle(request: SPRequest): Boolean
    def handle(request: SPRequest): Option[SPResponse]

    def method: String
  }

  private case class StringRoute(
    path: String,
    method: String = "GET"
  )(
    f: SPRequest => SPResponse
  ) extends Route {
    override def canHandleTarget(target: String): Boolean =
      path == target

    override def canHandle(request: SPRequest): Boolean =
      canHandleTarget(request.target) && method == request.method

    override def handle(request: SPRequest): Option[SPResponse] =
      if (canHandle(request)) Some(f(request)) else None
  }

  private case class RegexRoute(
    regex: Regex,
    method: String = "GET"
  )(
    f: (SPRequest, Map[String, String]) => SPResponse
  ) extends Route {
    override def canHandleTarget(target: String): Boolean =
      regex.findFirstMatchIn(target).isDefined

    override def canHandle(request: SPRequest): Boolean =
      canHandleTarget(request.target) && request.method == method

    override def handle(request: SPRequest): Option[SPResponse] = {
      if(canHandle(request)) {
        val capturedGroups = regex.findFirstMatchIn(request.target).map { m =>
          m.groupNames.map(groupName => groupName -> m.group(groupName)).toMap
        }
        capturedGroups.map(cg => f(request, cg))
      } else {
        None
      }
    }
  }

  private val routes = Seq(
    RegexRoute("^$|^/$".r) { case _ =>
      SPResponse.plainText(
        "Usage: GET /v1/<paperid>[?format={LabeledData,ExtractedMetadata}]\n" +
          "format is optional, defaults to LabeledData")
    },
    RegexRoute("^/v1/([a-f0-9]{40})$".r("paperId"))(handlePaperId),
    StringRoute("/v1", "POST")(handlePost),
    RegexRoute("^/v1/([a-f0-9]{40})$".r("paperId"), "PUT")(handlePut)
  )

  override def handle(
    target: String,
    baseRequest: Request,
    request: HttpServletRequest,
    response: HttpServletResponse
  ): Unit = {
    try {
      val spResponse = {
        val spRequest = SPRequest(target, baseRequest, request)
        // weird construction to make sure it tries one route at a time only
        routes.iterator.flatMap(_.handle(spRequest)).take(1).toSeq.headOption
      }.getOrElse {
        val allowedMethods = routes.filter(_.canHandleTarget(target)).map(_.method).toSet
        if (allowedMethods.isEmpty) {
          throw SPServerException(404, "When someone is searching, said Siddhartha, then it might easily happen that the only thing his eyes still see is that what he searches for, that he is unable to find anything, to let anything enter his mind, because he always thinks of nothing but the object of his search, because he has a goal, because he is obsessed by the goal. Searching means: having a goal. But finding means: being free, being open, having no goal. You, oh venerable one, are perhaps indeed a searcher, because, striving for your goal, there are many things you don't see, which are directly in front of your eyes.")
        } else {
          SPResponse(
            405,
            "text/plain;charset=utf-8",
            "Method not allowed".getBytes("UTF-8"),
            Map("Allow" -> allowedMethods.mkString(", "))
          )
        }
      }

      response.setStatus(spResponse.status)
      if(spResponse.contentType.nonEmpty)
        response.setContentType(spResponse.contentType)
      spResponse.headers.foreach { case (k, v) => response.addHeader(k, v) }
      if(spResponse.content.nonEmpty)
        response.getOutputStream.write(spResponse.content)
      baseRequest.setHandled(true)
    } catch {
      case e: SPServerException =>
        response.setStatus(e.getStatus)
        response.setContentType("text/plain;charset=utf-8")
        response.getWriter.println(e.getMessage)
        baseRequest.setHandled(true)
      case NonFatal(e) =>
        logger.warn(s"Uncaught exception: ${e.getMessage}", e)
        response.setStatus(500)
        response.setContentType("text/plain;charset=utf-8")
        response.getWriter.println(e.getMessage)
        baseRequest.setHandled(true)
    }
  }


  //
  // Specific handlers
  //

  private def handlePaperId(request: SPRequest, regexGroups: Map[String, String]) = {
    val paperId = regexGroups("paperId")
    val formatString = request.queryParams.getOrElse("format", "LabeledData")
    val content = formatString match {
      case "LabeledData" =>
        val labeledData =
          LabeledDataFromScienceParse.get(paperSource.getPdf(paperId), scienceParser).toJson.prettyPrint
        labeledData.getBytes("UTF-8")
      case "ExtractedMetadata" =>
        prettyJsonWriter.writeValueAsBytes(scienceParser.doParse(paperSource.getPdf(paperId)))
      case _ =>
        throw SPServerException(400, s"Could not understand output format '$formatString'.")
    }
    SPResponse(200, "application/json", content)
  }

  private def handlePost(request: SPRequest) = {
    // calculate SHA of paper
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    val bytes = Resource.using(new DigestInputStream(request.inputStream(), digest))(IOUtils.toByteArray)
    val paperId = Utilities.toHex(digest.digest())

    // parse paper
    val formatString = request.queryParams.getOrElse("format", "LabeledData")
    val content = formatString match {
      case "LabeledData" =>
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
    if (!alreadyUploaded) {
      val metadata = new ObjectMetadata()
      metadata.setCacheControl("public, immutable")
      metadata.setContentType("application/pdf")
      metadata.setContentLength(bytes.size)
      s3.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata)
    }

    // set new location
    val newLocation = request.requestUrl()
    newLocation.append('/')
    newLocation.append(paperId)
    if (formatString != null)
      newLocation.append(s"?format=$formatString")
    val headers = scala.collection.mutable.Map[String, String]()
    headers.update("Location", newLocation.toString)

    // Turns out you are allowed to return a 200 from a POST, if you have a Content-Location
    // header. https://tools.ietf.org/html/rfc7231#section-4.3.3
    headers.update("Content-Location", newLocation.toString)
    SPResponse(
      200,
      "application/json",
      content,
      headers.toMap
    )
  }

  private val feedbackStore = FeedbackStore // We're triggering this early, so that the FeedbackStore initializes before the first request
  private def handlePut(request: SPRequest, regexGroups: Map[String, String]) = {
    if(request.contentType != "application/json")
      throw SPServerException(400, "Content type for PUT must be application/json.")

    val paperId = regexGroups("paperId")
    val formatString = request.queryParams.getOrElse("format", "LabeledData")
    val inputString = Resource.using(request.inputStream()) { is =>
      IOUtils.toString(is, "UTF-8")
    }

    // We de-serialize and then re-serialize the posted data to check for errors and validity.
    val input: LabeledData = formatString match {
      case "LabeledData" =>
        import spray.json._
        LabeledData.fromJson(inputString.parseJson, paperSource.getPdf(paperId))
      case "ExtractedMetadata" =>
        val em = jsonMapper.readValue(inputString, classOf[ExtractedMetadata])
        LabeledData.fromExtractedMetadata(paperSource.getPdf(paperId), paperId, em)
    }

    feedbackStore.addFeedback(paperId, input)

    SPResponse.Success
  }
}
