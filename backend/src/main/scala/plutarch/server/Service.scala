package plutarch.server

import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.http.scaladsl.server.{ Directives, RejectionHandler, Route }
import akka.stream.scaladsl.Flow
import org.webjars.WebJarAssetLocator
import plutarch.server.pages.Pages

import scala.util.Failure
import plutarch.server.ws.WebSocketFlowCoordinator

import scala.concurrent.ExecutionContext

class Service(webSocketFlowCoordinator: WebSocketFlowCoordinator)(implicit executor: ExecutionContext) extends Directives with LazyLogging {

  private val locator = new WebJarAssetLocator

  def routes: Route =
    handleRejections(rejectionHandler) {
      path("web" / "js" / Pages.tmsp / Segment) { name ⇒ encodeResponse(getFromResource(s"web/js/$name")) } ~
        path("web" / "lib" / Remaining) { ref ⇒
          logger.debug(s"Requested lib $ref")
          val path = locator.getFullPath(ref)
          logger.debug(s"webjar asset path $path")
          getFromResource(path)
        } ~
        get(pathSingleSlash(redirect("welcome", StatusCodes.TemporaryRedirect))) ~
        (path("welcome") & get)(htmlResp(Pages.welcome)) ~
        path("favicon.ico")(getFromResource("images/favicon.ico", MediaTypes.`image/x-icon`)) ~
        path("robots.txt")(forbiddenHandler) ~
        path("websocket") {
          parameters('req.?) { req ⇒ handleWebSocketMessages(websocketFlow(req)) }
        }
    }

  private def rejectionHandler =
    RejectionHandler.newBuilder()
      .handleNotFound(
        extract(rc ⇒ (rc.request.headers, rc.unmatchedPath)) {
          case (headers, path) ⇒
            logger.info("Rejected request for '{}' from client with headers: {}", path,
              headers.map(header ⇒ s"${header.name}: ${header.value}").mkString(", "))
            htmlResp(Pages.notFound, StatusCodes.NotFound)
        })
      .result()

  private def forbiddenHandler =
    extract(rc ⇒ (rc.request.headers, rc.unmatchedPath)) {
      case (headers, path) ⇒
        logger.info("Forbidden request for '{}' from client with headers: {}", path,
          headers.map(header ⇒ s"${header.name}: ${header.value}").mkString(", "))
        complete(
          HttpResponse(
            StatusCodes.Forbidden,
            entity = HttpEntity.empty(ContentTypes.NoContentType)))
    }

  private def htmlResp(html: String, statusCode: StatusCode = StatusCodes.OK) =
    encodeResponse(
      complete(
        HttpResponse(
          statusCode,
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, html))))

  def websocketFlow(req: Option[String]): Flow[Message, Message, Any] = {
    logger.info("Incoming connect to websocket with req={}", req)
    Flow[Message]
      .collect {
        case TextMessage.Strict(msg) ⇒ msg
        case other ⇒
          logger.error("Unsupported message class: {}", other)
          throw new Exception("Not supported message type!")
      }
      .via(webSocketFlowCoordinator.createFlow(req))
      .via(reportErrorsFlow())
  }

  def reportErrorsFlow[T](): Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) ⇒ f.onComplete {
        case Failure(cause) ⇒
          logger.warn(s"WS stream failed with", cause)
        case _ ⇒
          logger.info(s"WS stream completed")
      })

}
