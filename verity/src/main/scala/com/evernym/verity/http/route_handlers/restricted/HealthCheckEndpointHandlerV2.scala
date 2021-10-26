package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.AppStateCoordinator
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.util.healthcheck.HealthChecker
import spray.json.DefaultJsonProtocol.{StringJsonFormat, _}
import spray.json.{RootJsonFormat, enrichAny}

import scala.concurrent.Future
import scala.util.{Failure, Success}

case class ReadinessStatus(status: Boolean = false,
                           akkaStorageStatus: String = "",
                           walletStorageStatus: String = "",
                           blobStorageStatus: String = "")


trait HealthCheckEndpointHandlerV2 {
  this: HttpRouteWithPlatform =>
  val healthChecker: HealthChecker
  val appStateCoordinator: AppStateCoordinator

  private implicit val apiStatusJsonFormat: RootJsonFormat[ReadinessStatus] = jsonFormat4(ReadinessStatus)

  private def readinessCheck(): Future[ReadinessStatus] = {
    if (appStateCoordinator.isDrainingStarted) {
      appStateCoordinator.incrementPostDrainingReadinessProbeCount()
      //if draining is already started, it doesn't make sense to check any other external dependency,
      // just return status as `false` to indicate readinessProbe failure
      Future.successful(
        ReadinessStatus(
          status = false,
          "n/a",
          "n/a",
          "n/a"
        )
      )
    } else {
      //TODO: temporary changes 
      //val akkaStorageFuture = healthChecker.checkAkkaStorageStatus
      val walletStorageFuture = healthChecker.checkWalletStorageStatus
      val blobStorageFuture = healthChecker.checkBlobStorageStatus
      for {
        //akkaStorage   <- akkaStorageFuture
        walletStorage <- walletStorageFuture
        blobStorage   <- blobStorageFuture
      } yield ReadinessStatus(
        walletStorage.status && blobStorage.status,
        "OK",
        walletStorage.msg,
        blobStorage.msg
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
