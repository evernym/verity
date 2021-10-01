package com.evernym.verity.http.route_handlers.restricted

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamSync

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
object ExternalDependenciesHeathCheck {


}

trait AbstractApiHealthCheck{
  def checkAkkaEventStorageReadiness: Future[(Boolean, String)]
  def checkWalletStorageReadiness: Future[(Boolean, String)]
}

trait ApiHeathCheck extends AbstractApiHealthCheck{
  this: HttpRouteWithPlatform =>
  implicit val ac: ActorSystem = platform.actorSystem
  override def checkAkkaEventStorageReadiness: Future[(Boolean, String)] = checkAkkaEventStorageReadiness_(platform.aac)
  override def checkWalletStorageReadiness: Future[(Boolean, String)] = checkWalletStorageReadiness_(platform.aac)


  private def checkAkkaEventStorageReadiness_(aac: AgentActorContext)
                                            (implicit as: ActorSystem): Future[(Boolean, String)] = {
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val keyValueMapper = aac.system.actorOf(KeyValueMapper.props(futureExecutionContext)(aac), actorId)
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

  private def checkWalletStorageReadiness_(aac: AgentActorContext)
                                         (implicit as: ActorSystem): Future[(Boolean, String)] = {
    val walletId = "dummy-wallet-" + UUID.randomUUID().toString
    val wap = generateWalletParamSync(walletId, aac.appConfig, LibIndyWalletProvider)
    val fLibIndy = LibIndyWalletProvider.openAsync(wap.walletName, wap.encryptionKey, wap.walletConfig)
    fLibIndy.flatMap {
      _ => Future.successful((true, "OK"))
    } recover {
      case _: WalletDoesNotExist => (true, "OK")
      case e: Exception => (false, e.getMessage)
    }
  }

}

//TODO: need inject AbstractApiHealthCheck implementation
trait ApiHealthCheckEndpointHandler extends ApiHeathCheck {
  this: HttpRouteWithPlatform =>

  protected val apiHealthCheckRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("verity" / "node") {
          extractRequest { implicit req: HttpRequest => {
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              path("readiness") {
                (get & pathEnd) {
                  complete {
                    val rdsFuture = checkAkkaEventStorageReadiness
                    val dynamoDBFuture = checkWalletStorageReadiness
                    val res = for {
                      respRDS <- rdsFuture
                      respDynamo <- dynamoDBFuture
                    } yield (
                      respRDS._1 && respDynamo._1,
                      s"""
                         |"RDS": "${respRDS._2}",
                         |"DynamoDB": "${respRDS._2}"
                         |""".stripMargin
                    )
                    val ans = Await.result(res, Duration(10, TimeUnit.SECONDS))
                    HttpResponse(if (ans._1) OK else ServiceUnavailable, entity = ans._2)
                  }
                }
              } ~
                //TODO: need to implement liveness check
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
