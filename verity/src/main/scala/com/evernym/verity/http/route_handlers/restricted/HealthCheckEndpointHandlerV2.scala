package com.evernym.verity.http.route_handlers.restricted

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamAsync
import spray.json.DefaultJsonProtocol.{StringJsonFormat, _}
import spray.json.{RootJsonFormat, enrichAny}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure


trait AbstractApiHealthChecker {
  def checkAkkaEventStorageReadiness: Future[ApiStatus]

  def checkWalletStorageReadiness: Future[ApiStatus]

  def checkStorageAPIReadiness: Future[ApiStatus]

  def checkLiveness: Future[Unit]
}

/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
class ApiHealthChecker(val platform: Platform) extends AbstractApiHealthChecker {
  private implicit val as: ActorSystem = platform.actorSystem
  implicit val ex: ExecutionContext = platform.executionContextProvider.futureExecutionContext

  override def checkAkkaEventStorageReadiness: Future[ApiStatus] = {
    implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val keyValueMapper = platform.aac.system.actorOf(KeyValueMapper.props(platform.executionContextProvider.futureExecutionContext)(platform.aac), actorId)
    val fut = {
      (keyValueMapper ? GetValue("dummy-key"))
        .mapTo[Option[String]]
        .map { _ =>
          platform.aac.system.stop(keyValueMapper)
          ApiStatus(status = true, "OK")
        }
    }.recover({ case e: Exception =>
      platform.aac.system.stop(keyValueMapper)
      ApiStatus(status = false, e.getMessage)
    })
    fut
  }

  //TODO: this logic doesn't seem to be working, should come back to this and fix it
  override def checkWalletStorageReadiness: Future[ApiStatus] = {
    val walletId = "dummy-wallet-" + UUID.randomUUID().toString
    val wap = generateWalletParamAsync(walletId, platform.aac.appConfig, LibIndyWalletProvider)
    val fLibIndy = wap.flatMap(w => {
      LibIndyWalletProvider.openAsync(w.walletName, w.encryptionKey, w.walletConfig)
    }) recover {
      case e: Exception => Future.failed(e)
    }
    fLibIndy.flatMap {
      _ => Future.successful(ApiStatus(status = true, "OK"))
    } recover {
      case _: WalletDoesNotExist => ApiStatus(status = true, "OK")
      case e: Exception => ApiStatus(status = false, e.getMessage)
    }
  }

  override def checkStorageAPIReadiness: Future[ApiStatus] = {
    platform.aac.storageAPI.ping flatMap {
      _ => Future.successful(ApiStatus(status = true, "OK"))
    } recover {
      case e: Exception => ApiStatus(status = false, e.getMessage)
    }
  }

  override def checkLiveness: Future[Unit] = {
    Future.successful((): Unit)
  }
}

trait HealthCheckEndpointHandlerV2 {
  this: HttpRouteWithPlatform =>
  val apiHealthChecker: AbstractApiHealthChecker

  private implicit val apiStatusJsonFormat: RootJsonFormat[ReadinessStatus] = jsonFormat3(ReadinessStatus)

  private def readinessCheck(): Future[(Boolean, ReadinessStatus)] = {
    val rdsFuture = apiHealthChecker.checkAkkaEventStorageReadiness
    val dynamoDBFuture = apiHealthChecker.checkWalletStorageReadiness
    val storageAPIFuture = apiHealthChecker.checkStorageAPIReadiness
    val res = for {
      rds <- rdsFuture
      dynamodb <- dynamoDBFuture
      storageAPI <- storageAPIFuture
    } yield (
      rds.status && dynamodb.status && storageAPI.status,
      ReadinessStatus(rds.msg, dynamodb.msg, storageAPI.msg)
    )
    res
  }

  protected val healthCheckRouteV2: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          path("readiness") {
            (get & pathEnd) {
              onComplete(readinessCheck()) {
                case util.Success(value) => complete(if (value._1) OK else ServiceUnavailable, value._2.toJson.toString())
                case Failure(e) => complete(ServiceUnavailable, e.getMessage)
              }
            }
          } ~
            path("liveness") {
              (get & pathEnd) {
                onComplete(apiHealthChecker.checkLiveness) {
                  case util.Success(_) => complete(OK, "OK")
                  case Failure(e) => complete(ServiceUnavailable, e.getMessage)
                }
              }
            }
        }
      }
    }

}

case class ReadinessStatus(rds: String = "", dynamoDB: String = "", storageAPI: String = "")

case class ApiStatus(status: Boolean, msg: String)