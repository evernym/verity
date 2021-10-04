package com.evernym.verity.http.route_handlers.restricted

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.Main.ecp.futureExecutionContext
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.{HttpRouteWithPlatform, PlatformServiceProvider}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamAsync

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
trait AbstractApiHealthCheck{
  def checkAkkaEventStorageReadiness: Future[(Boolean, String)]
  def checkWalletStorageReadiness: Future[(Boolean, String)]
  def plt: Platform
}

class MockApiHealthCheck extends AbstractApiHealthCheck{
  override def checkAkkaEventStorageReadiness: Future[(Boolean, String)] = {
    Future.successful((false, "OK"))
  }

  override def checkWalletStorageReadiness: Future[(Boolean, String)] = {
    Future.successful((true, "OK"))
  }

  override def plt: Platform = ???
}

class ApiHealthCheck(val p: Platform) extends AbstractApiHealthCheck {
  override def checkAkkaEventStorageReadiness: Future[(Boolean, String)] = checkAkkaEventStorageReadiness_(plt.aac, plt.actorSystem)
  override def checkWalletStorageReadiness: Future[(Boolean, String)] = checkWalletStorageReadiness_(plt.aac, plt.actorSystem)

  override def plt: Platform = p


  private def checkAkkaEventStorageReadiness_(aac: AgentActorContext, as: ActorSystem): Future[(Boolean, String)] = {
    implicit val ex: ExecutionContext = plt.executionContextProvider.futureExecutionContext
    implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val keyValueMapper = aac.system.actorOf(KeyValueMapper.props(plt.executionContextProvider.futureExecutionContext)(aac), actorId)
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
    implicit val ex: ExecutionContext = plt.executionContextProvider.futureExecutionContext
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

trait ApiHealthCheckEndpointHandler {
  this: HttpRouteWithPlatform =>
  val apiHealthCheck: AbstractApiHealthCheck
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
                    val rdsFuture = apiHealthCheck.checkAkkaEventStorageReadiness
                    val dynamoDBFuture = apiHealthCheck.checkWalletStorageReadiness
                    val res = for {
                      respRDS <- rdsFuture
                      respDynamo <- dynamoDBFuture
                    } yield (
                      respRDS._1 && respDynamo._1,
                      s"""
                         |"RDS": "${respRDS._2}",
                         |"DynamoDB": "${respDynamo._2}"
                         |""".stripMargin
                    )
                    val ans = Await.result(res, Duration(10, TimeUnit.SECONDS))
                    HttpResponse(if (ans._1) OK else ServiceUnavailable, entity = ans._2)
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
