package com.evernym.verity.http.route_handlers.restricted

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.alpakka.s3.scaladsl.S3
import akka.util.Timeout
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamAsync
import spray.json.DefaultJsonProtocol.{StringJsonFormat, jsonFormat2}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.{RootJsonFormat, enrichAny}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure


trait AbstractApiHealthCheck{
  def checkAkkaEventStorageReadiness: Future[(Boolean, String)]
  def checkWalletStorageReadiness: Future[(Boolean, String)]
  def platform: Platform
}

/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
class ApiHealthCheck(val p: Platform) extends AbstractApiHealthCheck {
  override def checkAkkaEventStorageReadiness: Future[(Boolean, String)] = checkAkkaEventStorageReadiness_(platform.aac, platform.actorSystem)
  override def checkWalletStorageReadiness: Future[(Boolean, String)] = checkWalletStorageReadiness_(platform.aac, platform.actorSystem)

  override def platform: Platform = p


  private def checkAkkaEventStorageReadiness_(aac: AgentActorContext, as: ActorSystem): Future[(Boolean, String)] = {
    implicit val ex: ExecutionContext = platform.executionContextProvider.futureExecutionContext
    implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val keyValueMapper = aac.system.actorOf(KeyValueMapper.props(platform.executionContextProvider.futureExecutionContext)(aac), actorId)
    val fut = {
      (keyValueMapper ? GetValue("dummy-key"))
        .mapTo[Option[String]]
        .map { _ =>
          aac.system.stop(keyValueMapper)
          (true, "OK")
        }
    }.recover({ case e: Exception => (false, e.getMessage) })
    fut
  }

  private def checkWalletStorageReadiness_(aac: AgentActorContext, as: ActorSystem): Future[(Boolean, String)] = {
    implicit val ex: ExecutionContext = platform.executionContextProvider.futureExecutionContext
    val walletId = "dummy-wallet-" + UUID.randomUUID().toString
    val wap = generateWalletParamAsync(walletId, aac.appConfig, LibIndyWalletProvider)
    val fLibIndy = wap.flatMap(w => {
      LibIndyWalletProvider.openAsync(w.walletName, w.encryptionKey, w.walletConfig)
    }) recover {
      case e: Exception => Future.failed(e)
    }
    fLibIndy.flatMap {
      _ => Future.successful((true, "OK"))
    } recover {
      case _: WalletDoesNotExist => (true, "OK")
      case e: Exception => (false, e.getMessage)
    }
  }
}

trait HealthCheckEndpointHandlerV2 {
  this: HttpRouteWithPlatform =>
  val apiHealthCheck: AbstractApiHealthCheck

  implicit val apiStatusJsonFormat: RootJsonFormat[ApiStatus] = jsonFormat2(ApiStatus)

  def readinessCheck(): Future[(Boolean, ApiStatus)] = {
    val rdsFuture = apiHealthCheck.checkAkkaEventStorageReadiness
    val dynamoDBFuture = apiHealthCheck.checkWalletStorageReadiness
    val res = for {
      respRDS <- rdsFuture
      respDynamo <- dynamoDBFuture
    } yield (
      respRDS._1 && respDynamo._1,
      ApiStatus(respRDS._2, respDynamo._2)
    )
    res
  }

  protected val healthCheckRouteV2: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          extractRequest { implicit req: HttpRequest => {
            extractClientIP { implicit remoteAddress =>
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
                    complete {
                      OK
                    }
                  }
                }
            }
          }
          }
        }
      }
    }

}

case class ApiStatus(rds: String, dynamoDB: String)