package com.evernym.verity.app_launcher

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.actor.cluster_singleton.{GetValue, KeyValueMapper}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.healthcheck.HealthChecker
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.util2.Exceptions.NoResponseFromLedgerPoolServiceException
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil._

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.min

case class StartupProbeStatus(status: Boolean,
                              akkaStorageStatus: String,
                              walletStorageStatus: String,
                              blobStorageStatus: String,
                              ledgerPoolStatus: String)

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def waitForRequiredDepsIsOk(healthChecker: HealthChecker, executionContext: ExecutionContext): Unit = {
    while (true) {
      val result = Await.result(startupProbe(healthChecker)(executionContext), 10.seconds)
      if (result.status) {
        logger.info("Successfully check external deps")
        return // all are ok if this line executed
      }
      logger.warn("Startup probe check returned 'not ready': \n" + result.toString)
    }
  }

  private def startupProbe(healthChecker: HealthChecker)(implicit executionContext: ExecutionContext): Future[StartupProbeStatus] = {
    val akkaStorageFuture = healthChecker.checkAkkaStorageStatus
    val walletStorageFuture = healthChecker.checkWalletStorageStatus
    val blobStorageFuture = healthChecker.checkBlobStorageStatus
    val ledgerFuture = healthChecker.checkLedgerPoolStatus
    for {
      akkaStorage   <- akkaStorageFuture
      walletStorage <- walletStorageFuture
      blobStorage   <- blobStorageFuture
      ledger        <- ledgerFuture
    } yield StartupProbeStatus(
      akkaStorage.status && walletStorage.status && blobStorage.status && ledger.status,
      akkaStorage.msg,
      walletStorage.msg,
      blobStorage.msg,
      ledger.msg
    )
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