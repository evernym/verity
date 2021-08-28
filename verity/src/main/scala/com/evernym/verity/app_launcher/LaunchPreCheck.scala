package com.evernym.verity.app_launcher

import java.util.UUID
import java.util.concurrent.TimeUnit
import akka.pattern.ask
import akka.actor.ActorSystem
import akka.util.Timeout

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
import com.evernym.verity.util2.Exceptions

import scala.annotation.tailrec
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration.Duration
import scala.math.min

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def checkReqDependencies(aac: AgentActorContext, ec: ExecutionContext): Unit = {
    checkAkkaEventStorageConnection(aac)(aac.system, ec)
    checkWalletStorageConnection(aac)(aac.system)
    checkLedgerPoolConnection(aac)(aac.system, ec)
  }

  @tailrec
  private def checkLedgerPoolConnection(aac: AgentActorContext, delay: Int = 0)
                                       (implicit as: ActorSystem, ec: ExecutionContext): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
      val pcFut = Future(aac.poolConnManager.open())
        .recover {
          case e: Throwable =>
            logger.error("error while checking ledger connection: " + Exceptions.getStackTraceAsSingleLineString(e))
            throw e
        }
      Await.result(pcFut, timeout.duration)
    } catch {
      case e: TimeoutException =>
        val errorMsg = "no response from ledger pool: " + e.toString
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        checkLedgerPoolConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)

      case e: Exception =>
        val errorMsg =
          "unable to connect with ledger pool (" +
            "possible-causes: something wrong with genesis txn file, " +
            "ledger pool not reachable/up/responding etc, " +
            s"error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        // increase delay exponentially until 60s
        checkLedgerPoolConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  @tailrec
  private def checkAkkaEventStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                             (implicit as: ActorSystem, ec: ExecutionContext): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
      val actorId = "dummy-actor-" + UUID.randomUUID().toString
      val keyValueMapper = aac.system.actorOf(KeyValueMapper.props(ec)(aac), actorId)
      val fut = (keyValueMapper ? GetValue("dummy-key"))
          .mapTo[Option[String]]
          .recover {
            case e: Throwable =>
              logger.error("error while checking akka event storage connection: " + Exceptions.getStackTraceAsSingleLineString(e))
              throw e
          }
      Await.result(fut, timeout.duration)
      aac.system.stop(keyValueMapper)
    } catch {
      case e: Exception =>
        val errorMsg =
          "akka event storage connection check failed (" +
            "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
            s"error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkAkkaEventStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  @tailrec
  private def checkWalletStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                          (implicit as: ActorSystem): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      val walletId = "dummy-wallet-" + UUID.randomUUID().toString
      val wap = generateWalletParamSync(walletId, aac.appConfig, LibIndyWalletProvider)
      LibIndyWalletProvider.openSync(wap.walletName, wap.encryptionKey, wap.walletConfig)
    } catch {
      //TODO: this logic doesn't seem to be working, should come back to this and fix it
      case _ @ (_ : WalletDoesNotExist) =>
        //NOTE: we are catching this exceptions which is thrown if invalid/wrong data (in our case non existent wallet name)
        //is passed but at least it confirms that connection with wallet storage was successful.
      case e: Exception =>
        val errorMsg =
          "wallet storage connection check failed (" +
            "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
            s"error-msg: ${Exceptions.getErrorMsg(e)})"
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkWalletStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }
}
