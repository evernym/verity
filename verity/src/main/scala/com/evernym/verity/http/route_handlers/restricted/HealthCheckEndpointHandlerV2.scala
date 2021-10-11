package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.util.healthcheck.{AbstractHealthChecker, ReadinessStatus}
import spray.json.DefaultJsonProtocol.{StringJsonFormat, _}
import spray.json.{RootJsonFormat, enrichAny}

import scala.concurrent.Future
import scala.util.{Failure, Success}


trait HealthCheckEndpointHandlerV2 {
  this: HttpRouteWithPlatform =>
  val healthChecker: AbstractHealthChecker

  private implicit val apiStatusJsonFormat: RootJsonFormat[ReadinessStatus] = jsonFormat4(ReadinessStatus)

  private def readinessCheck(): Future[ReadinessStatus] = {
    val rdsFuture = healthChecker.checkAkkaEventStorageReadiness
    val dynamoDBFuture = healthChecker.checkWalletStorageReadiness
    val storageAPIFuture = healthChecker.checkStorageAPIReadiness
    for {
      rds <- rdsFuture
      dynamodb <- dynamoDBFuture
      storageAPI <- storageAPIFuture
    } yield ReadinessStatus(rds.status && dynamodb.status && storageAPI.status,
      rds.msg,
      dynamodb.msg,
      storageAPI.msg)
  }

  protected val healthCheckRouteV2: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          path("readiness") {
            (get & pathEnd) {
              onComplete(readinessCheck()) {
                case Success(value) => complete(if (value.status) StatusCodes.OK else StatusCodes.ServiceUnavailable, value.toJson.toString())
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