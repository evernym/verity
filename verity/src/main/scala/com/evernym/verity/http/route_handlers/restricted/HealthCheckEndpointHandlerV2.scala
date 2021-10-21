package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.util.healthcheck.HealthChecker
import spray.json.DefaultJsonProtocol._
import spray.json.{RootJsonFormat, enrichAny}

import java.net.InetAddress
import scala.concurrent.Future
import scala.util.{Failure, Success}


case class ReadinessStatus(status: Boolean = false,
                           rds: String = "",
                           dynamoDB: String = "",
                           storageAPI: String = "")

trait HealthCheckEndpointHandlerV2 {
  this: HttpRouteWithPlatform =>
  val healthChecker: HealthChecker

  private implicit val apiStatusJsonFormat: RootJsonFormat[ReadinessStatus] = jsonFormat4(ReadinessStatus)

  private def readinessCheck(): Future[ReadinessStatus] = {
    //TODO: temporary changes
    if (healthChecker.isReady) {
      logger.info(s"[${InetAddress.getLocalHost.getHostName}] HealthCheck -> node is up, checking other services")
      val rdsFuture = healthChecker.checkAkkaEventStorageStatus
      val dynamoDBFuture = healthChecker.checkWalletStorageStatus
      val storageAPIFuture = healthChecker.checkStorageAPIStatus
      for {
        rds <- rdsFuture
        dynamodb <- dynamoDBFuture
        storageAPI <- storageAPIFuture
      } yield ReadinessStatus(
        rds.status && dynamodb.status && storageAPI.status,
        rds.msg,
        dynamodb.msg,
        storageAPI.msg
      )
    } else {
      logger.info(s"[${InetAddress.getLocalHost.getHostName}] HealthCheck -> node is draining...")
      Future.successful(
        ReadinessStatus(
          status = false,
          "n/a",
          "n/a",
          "n/a"
        )
      )
    }
  }

  protected val healthCheckRouteV2: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          path("readiness") {
            (get & pathEnd) {
              onComplete(readinessCheck()) {
                case Success(value) =>
                  logger.info(s"[${InetAddress.getLocalHost.getHostName}] HealthCheck -> result: ${value.status}")
                  complete {
                    val resp = (if (value.status) StatusCodes.OK else StatusCodes.ServiceUnavailable, value.toJson.toString())
                    logger.info(s"[${InetAddress.getLocalHost.getHostName}] HealthCheck -> resp: $resp")
                    resp
                  }
                case Failure(e) => complete(StatusCodes.ServiceUnavailable, e.getMessage)
              }
            }
          } ~
            path("liveness") {
              (get & pathEnd) {
                onComplete(healthChecker.checkLiveness) {
                  case Success(_) => complete(StatusCodes.OK, "OK")
                  case Failure(e) => complete(StatusCodes.ServiceUnavailable, e.getMessage)
                }
              }
            }
        }
      }
    }

}