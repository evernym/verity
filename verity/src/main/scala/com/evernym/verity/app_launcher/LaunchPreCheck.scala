package com.evernym.verity.app_launcher

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.util.Timeout
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Exceptions.NoResponseFromLedgerPoolServiceException
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI._
import com.evernym.verity.constants.Constants.{AGENCY_DID_KEY, KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID}
import com.evernym.verity.util.Util.logger
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.cache.base.{GetCachedObjectParam, KeyDetail}

import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration.Duration
import scala.math.min

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  def checkReqDependencies(aac: AgentActorContext): Unit = {
    checkAkkaEventStorageConnection(aac)(aac.system)
    checkWalletStorageConnection(aac)(aac.system)
    checkLedgerPoolConnection(aac)(aac.system)
  }

  private def checkLedgerPoolConnection(aac: AgentActorContext, delay: Int = 0)
                                       (implicit as: ActorSystem): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(10, TimeUnit.SECONDS))
      val pcFut = Future(aac.poolConnManager.open())
      Await.result(pcFut, timeout.duration)
    } catch {
      case e: TimeoutException =>
        val errorMsg = "no response from ledger pool: " + e.toString
        publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        checkLedgerPoolConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)

      case e: Exception =>
        val errorMsg =
          "unable to connect with ledger pool (" +
            "possible-causes: something wrong with genesis txn file, " +
            "ledger pool not reachable/up/responding etc, " +
            s"error-msg: ${Exceptions.getErrorMsg(e)})"
        publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
          new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
        // increase delay exponentially until 60s
        checkLedgerPoolConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  private def checkAkkaEventStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                             (implicit as: ActorSystem): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      implicit val timeout: Timeout = Timeout(Duration.create(10, TimeUnit.SECONDS))
      val gcop = GetCachedObjectParam(KeyDetail(AGENCY_DID_KEY, required = false),
        KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
      val fut = aac.generalCache.getByParamAsync(gcop)
      Await.result(fut, timeout.duration)
    } catch {
      case e: Exception =>
        val errorMsg =
          "akka event storage connection check failed (" +
            "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
            s"error-msg: ${Exceptions.getErrorMsg(e)})"
        publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkAkkaEventStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }

  private def checkWalletStorageConnection(aac: AgentActorContext, delay: Int = 0)
                                          (implicit as: ActorSystem): Unit = {
    try {
      if (delay > 0)
        logger.debug(s"Retrying after $delay seconds")
      Thread.sleep(delay * 1000)    //this is only executed during agent service start time
      val walletId = "test-wallet-name-" + UUID.randomUUID().toString
      val wap = generateWalletParamSync(walletId, aac.appConfig, aac.walletProvider)
      aac.walletProvider.openSync(wap.walletName, wap.encryptionKey, wap.walletConfig)
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
        publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errorMsg)))
        // increase delay exponentially until 60s
        checkWalletStorageConnection(aac, if (delay > 0) min(delay * 2, 60) else 1)
    }
  }
}
