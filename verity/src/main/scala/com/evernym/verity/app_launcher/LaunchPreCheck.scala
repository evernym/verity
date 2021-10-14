package com.evernym.verity.app_launcher

import java.util.UUID
import java.util.concurrent.TimeUnit
import akka.pattern.ask
import akka.actor.ActorSystem
import akka.util.Timeout
import com.evernym.verity.actor.Platform

import scala.concurrent.ExecutionContext
import com.evernym.verity.util2.Exceptions.NoResponseFromLedgerPoolServiceException
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.healthcheck.{ApiStatus, HealthChecker, HealthCheckerImpl, ReadinessStatus}
import com.evernym.verity.util2.Exceptions

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.math.min

case class StartupProbeStatus(status: Boolean, rds: String, dynamodb: String, storageAPI: String, ledger: String)

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def waitReqDependenciesIsOk(platform: Platform): Unit = {
    while (true) {
      val result = Await.result(startupProbe(platform), 10.seconds)
      if (result.status) {
        logger.info("Successfully check external deps")
        return // all are ok if this line executed
      }
      logger.warn("Readiness check returned 'not ready': \n" + result.toString)
    }
  }

  private def startupProbe(platform: Platform): Future[StartupProbeStatus] = {
    val healthChecker: HealthChecker = HealthChecker.apply(platform)
    implicit val ex: ExecutionContext = platform.executionContextProvider.futureExecutionContext
    val rdsFuture = healthChecker.checkAkkaEventStorageReadiness
    val dynamoDBFuture = healthChecker.checkWalletStorageReadiness
    val storageAPIFuture = healthChecker.checkStorageAPIReadiness
    val ledgerFuture = ledgerStartupProbe(platform.aac)(platform.actorSystem,
      platform.executionContextProvider.futureExecutionContext)
    for {
      rds <- rdsFuture
      dynamodb <- dynamoDBFuture
      storageAPI <- storageAPIFuture
      ledger <- ledgerFuture
    } yield StartupProbeStatus(rds.status && dynamodb.status && storageAPI.status && ledger.status,
      rds.msg,
      dynamodb.msg,
      storageAPI.msg,
      ledger.msg)
  }

  @tailrec
  private def checkLedgerPoolConnection(aac: AgentActorContext, delay: Int = 0)
                                       (implicit as: ActorSystem, ec: ExecutionContext): Boolean = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000) //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
      val pcFut = Future(aac.poolConnManager.open()).map(_ => true)
      Await.result(pcFut, timeout.duration)
    } catch {
      case e: Exception =>
        val errorMsg = s"ledger connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        // increase delay exponentially until 60s
        checkLedgerPoolConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  private def ledgerStartupProbe(aac: AgentActorContext)
                                (implicit as: ActorSystem, ec: ExecutionContext): Future[ApiStatus] = {
    val pcFut = Future(aac.poolConnManager.open()).map(_ => true)
    pcFut.map(
      _ => ApiStatus(status = true, "OK")
    ).recover {
      case e: Exception =>
        val errorMsg = s"ledger connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        ApiStatus(status = false, errorMsg)
    }
  }

  @tailrec
  private def checkAkkaEventStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                             (implicit as: ActorSystem, ec: ExecutionContext): Boolean = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000) //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
      val actorId = "dummy-actor-" + UUID.randomUUID().toString
      val keyValueMapper = aac.system.actorOf(KeyValueMapper.props(ec)(aac), actorId)
      val fut =
        (keyValueMapper ? GetValue("dummy-key"))
          .mapTo[Option[String]]
          .map { _ =>
            aac.system.stop(keyValueMapper)
            true
          }
      Await.result(fut, timeout.duration)
    } catch {
      case e: Exception =>
        val errorMsg = s"akka event storage connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkAkkaEventStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  @tailrec
  private def checkWalletStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                          (implicit as: ActorSystem): Boolean = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000) //this is only executed during agent service start time
      val walletId = "dummy-wallet-" + UUID.randomUUID().toString
      val wap = generateWalletParamSync(walletId, aac.appConfig, LibIndyWalletProvider)
      LibIndyWalletProvider.openSync(wap.walletName, wap.encryptionKey, wap.walletConfig)
      true
    } catch {
      //TODO: this logic doesn't seem to be working, should come back to this and fix it
      case _@(_: WalletDoesNotExist) =>
        //NOTE: we are catching this exceptions which is thrown if invalid/wrong data (in our case non existent wallet name)
        //is passed but at least it confirms that connection with wallet storage was successful.
        true
      case e: Exception =>
        val errorMsg = s"wallet storage connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkWalletStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }
}