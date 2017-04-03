package org.allenai.scienceparse

import java.awt.print.Book
import java.io.{File, StringWriter, InputStream, ByteArrayInputStream}
import java.security.{DigestInputStream, MessageDigest}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.{JsonMappingException, ObjectMapper}
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

import spray.json._
import LabeledDataJsonProtocol._

object SPServer extends Logging {
  def main(args: Array[String]): Unit = {
    case class Config(
      modelFile: Option[File] = None,
      bibModelFile: Option[File] = None,
      gazetteerFile: Option[File] = None,
      paperDirectory: Option[File] = None,
      enableFeedback: Boolean = false,
      downloadModelOnly: Boolean = false
    )

    val parser = new OptionParser[Config](this.getClass.getSimpleName) {
      override def errorOnUnknownArgument = false

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

      opt[Unit]("enableFeedback") action { (_, c) =>
        c.copy(enableFeedback = true)
      } text "Enables the feedback mechanism"

      opt[Unit]("downloadModelOnly") action { (_, c) =>
        c.copy(downloadModelOnly = true)
      } text "Just downloads all the model files, and then quits"

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

      if(config.downloadModelOnly)
        System.exit(0)

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
      server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", 10000000)
      server.setHandler(new SPServer(paperSource, scienceParser, config.enableFeedback))
      server.start()
      server.join()
    }
  }

  val paperIdRegex = "^/v1/([a-f0-9]{40})$".r
}

class SPServer(
  private val paperSource: PaperSource,
  private val scienceParser: Parser,
  enableFeedback: Boolean = true
) extends AbstractHandler with Logging {

  private val jsonMapper = new ObjectMapper() with ScalaObjectMapper
  jsonMapper.registerModule(DefaultScalaModule)
  private val prettyJsonWriter = jsonMapper.writerWithDefaultPrettyPrinter()

  private val bucket: String = "ai2-s2-pdfs"
  private val s3: AmazonS3 = new AmazonS3Client


  //
  // Request / response stuff
  //

  private case class SPServerException(status: Int, message: String) extends Exception(message) {
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
      if (canHandle(request)) {
        val capturedGroups = regex.findFirstMatchIn(request.target).map { m =>
          m.groupNames.map(groupName => groupName -> m.group(groupName)).toMap
        }
        capturedGroups.map(cg => f(request, cg))
      } else {
        None
      }
    }
  }

  private val feedbackStore = if(enableFeedback) Some(FeedbackStore) else None

  private val feedbackRoutes = Seq(
    StringRoute("/v1/corrections")(correctionsGetAll),
    RegexRoute("^/v1/corrections/([a-f0-9]{40})$".r("paperId"), "GET")(correctionsGet),
    RegexRoute("^/v1/corrections/([a-f0-9]{40})$".r("paperId"), "PUT")(correctionsPut)
  )

  private val routes = Seq(
    RegexRoute("^$|^/$".r) { case _ =>
      SPResponse.plainText(
        "Usage: GET /v1/<paperid>[?format={LabeledData,ExtractedMetadata}]\n" +
          "format is optional, defaults to LabeledData")
    },
    RegexRoute("^/v1/([a-f0-9]{40})$".r("paperId"))(handlePaperId),
    StringRoute("/v1", "POST")(handlePost)
  ) ++ (if(feedbackStore.isDefined) feedbackRoutes else Seq.empty)

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
      if (spResponse.contentType.nonEmpty)
        response.setContentType(spResponse.contentType)
      spResponse.headers.foreach { case (k, v) => response.addHeader(k, v) }
      if (spResponse.content.nonEmpty)
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
    val skipFields = request.queryParams.getOrElse("skipFields", "").split(",").map(_.trim).toSet
    val content = formatString match {
      case "LabeledData" =>
        val labeledDataJson =
          LabeledPapersFromScienceParse.get(paperSource.getPdf(paperId), scienceParser).labels.toJson
        val strippedFields = skipFields.foldLeft(labeledDataJson.asJsObject.fields) {
          case (fields, skipField) =>
            fields - skipField
          }
        JsObject(strippedFields).prettyPrint.getBytes("UTF-8")
      case "ExtractedMetadata" if skipFields.isEmpty =>
        prettyJsonWriter.writeValueAsBytes(scienceParser.doParse(paperSource.getPdf(paperId)))
      case "ExtractedMetadata" if skipFields.nonEmpty =>
        throw SPServerException(400, s"'skipFields' only works with output format 'LabeledData'.")
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
          LabeledPapersFromScienceParse.get(
            new ByteArrayInputStream(bytes), scienceParser).labels.toJson.prettyPrint
        labeledData.getBytes("UTF-8")
      case "ExtractedMetadata" =>
        prettyJsonWriter.writeValueAsBytes(
          scienceParser.doParse(
            new ByteArrayInputStream(bytes)))
      case _ =>
        throw SPServerException(400, s"Could not understand output format '$formatString'.")
    }

    SPResponse(200, "application/json", content)
  }

  private val feedbackUnavailableException =
    SPServerException(403, "Feedback has been disabled on this server")

  private def correctionsGet(request: SPRequest, regexGroups: Map[String, String]) = feedbackStore.map { store =>
    val paperId = regexGroups("paperId")
    val formatString = request.queryParams.getOrElse("format", "LabeledData")
    val feedback = store.getFeedback(paperId).getOrElse(
      throw new SPServerException(404, s"No feedback collected for $paperId"))
    formatString match {
      case "LabeledData" =>
        SPResponse(200, "application/json", feedback.toJson.prettyPrint.getBytes("UTF-8"))
      case _ =>
        throw SPServerException(400, s"Could not understand output format '$formatString'.")
    }
  }.getOrElse(throw feedbackUnavailableException)

  private def correctionsPut(request: SPRequest, regexGroups: Map[String, String]) = feedbackStore.map { store =>
    if (request.contentType != "application/json")
      throw SPServerException(400, "Content type for PUT must be application/json.")

    val paperId = regexGroups("paperId")
    val formatString = request.queryParams.getOrElse("format", "LabeledData")
    val inputString = Resource.using(request.inputStream()) { is =>
      IOUtils.toString(is, "UTF-8")
    }

    // We de-serialize and then re-serialize the posted data to check for errors and validity.
    var input: LabeledData = try {
      formatString match {
        case "LabeledData" =>
          import spray.json._
          import LabeledDataJsonProtocol._
          inputString.parseJson.convertTo[LabeledData]
        case "ExtractedMetadata" =>
          val em = jsonMapper.readValue(inputString, classOf[ExtractedMetadata])
          LabeledData.fromExtractedMetadata(paperId, em)
        case _ =>
          throw SPServerException(400, s"Could not understand input format '$formatString'")
      }
    } catch {
      case e @ (_: spray.json.DeserializationException | _: JsonMappingException | _: UnrecognizedPropertyException) =>
        throw SPServerException(400, s"Error while parsing input: ${e.getMessage}")
    }

    if(!input.id.startsWith("feedback:"))
      input = input.copy(id = s"feedback:${input.id}")

    store.addFeedback(paperId, input)

    SPResponse.Success
  }.getOrElse(throw feedbackUnavailableException)

  private def correctionsGetAll(request: SPRequest) = feedbackStore.map { store =>
    val result = store.getAllFeedback
    // This keeps the whole result set in memory, which is bad. It should be streamed.
    // ScalikeJDBC already insists on keeping it in memory, so I didn't take the time to optimize
    // it here.
    SPResponse(
      200,
      "application/json",
      result.map(_.toJson.compactPrint).mkString("\n").getBytes("UTF-8")
    )
  }.getOrElse(throw feedbackUnavailableException)
}
