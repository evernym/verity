package com.evernym.verity.util.healthcheck

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.appStateManager.AppStateConstants.CONTEXT_AGENT_SERVICE_INIT
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.util2.Exceptions.NoResponseFromLedgerPoolServiceException
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamAsync

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
class HealthCheckerImpl(val agentActorContext: AgentActorContext,
                        val actorSystem: ActorSystem,
                        implicit val futureExecutionContext: ExecutionContext) extends HealthChecker {

  override def checkAkkaEventStorageStatus: Future[ApiStatus] = {
    implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val keyValueMapper = actorSystem.actorOf(KeyValueMapper.props(futureExecutionContext)(agentActorContext), actorId)
    val fut = {
      (keyValueMapper ? GetValue("dummy-key"))
        .mapTo[Option[String]]
        .transform( t => {
          actorSystem.stop(keyValueMapper)
          t match {
            case Success(_) => Success(ApiStatus(status = true, "OK"))
            case Failure(e) => Success(ApiStatus(status = false, e.getMessage))
          }
        })
    }
    fut
  }

  //TODO: this logic doesn't seem to be working, should come back to this and fix it
  override def checkWalletStorageStatus: Future[ApiStatus] = {
    val walletId = "dummy-wallet-" + UUID.randomUUID().toString
    val wap = generateWalletParamAsync(walletId,agentActorContext.appConfig, LibIndyWalletProvider)
    val fLibIndy = wap.flatMap(w => {
      LibIndyWalletProvider.openAsync(w.walletName, w.encryptionKey, w.walletConfig)
    })
    fLibIndy.map {
      _ => ApiStatus(status = true, "OK")
    } recover {
      case _: WalletDoesNotExist => ApiStatus(status = true, "OK")
      case e: Exception => ApiStatus(status = false, e.getMessage)
    }
  }

  override def checkStorageAPIStatus: Future[ApiStatus] = {
    agentActorContext.storageAPI.ping map {
      _ => ApiStatus(status = true, "OK")
    } recover {
      case e: Exception => ApiStatus(status = false, e.getMessage)
    }
  }

  //This method checks that Verity can respond to the liveness request,
  // and `Future{}` checks if ExecutionContext is available, and can execute Future.
  override def checkLiveness: Future[Unit] = {
    Future {}
  }

  override def checkLedgerPoolStatus: Future[ApiStatus] = {
    val pcFut = Future(agentActorContext.poolConnManager.open()).map(_ => true)
    pcFut.map(
      _ => ApiStatus(status = true, "OK")
    ).recover {
      case e: Exception =>
        val errorMsg = s"ledger connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
        AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        ApiStatus(status = false, e.getMessage)
    }
  }
}