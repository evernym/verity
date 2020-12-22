package com.evernym.verity.vault.service

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import com.evernym.verity.Exceptions.HandledErrorException

import com.evernym.verity.actor.wallet.{CreateWallet, WalletCmdErrorResponse, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics.AS_SERVICE_LIBINDY_WALLET_DURATION
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.UtilBase
import com.evernym.verity.vault.WalletUtil._
import com.evernym.verity.vault.{WalletConfig, WalletExt, WalletProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * this is local object based wallet service
 * this should NOT be used as soon as ActorWalletService is ready to be used
 * @param appConfig
 * @param util
 * @param walletProvider
 * @param ledgerPoolManager
 */
class NonActorWalletService(appConfig:AppConfig,
                            util: UtilBase,
                            walletProvider: WalletProvider,
                            ledgerPoolManager: LedgerPoolConnManager)
  extends WalletService {

  private val walletConfig: WalletConfig = buildWalletConfig(appConfig)

  override def execute(walletId: String, cmd: Any): Future[Any] = {
    implicit val wp: WalletParam = generateWalletParam(walletId, appConfig, walletProvider, walletConfig)
    implicit val wmp: WalletMsgParam = WalletMsgParam(walletProvider, wp, util: UtilBase, ledgerPoolManager)

    val resp = cmd match {
      case CreateWallet =>
        addToOpenedWalletIfReq(WalletMsgHandler.handleCreateAndOpenWallet())
        Future(WalletCreated)
      case cmd =>
        executeOpWithWalletInfo("with wallet", { implicit we: WalletExt =>
          WalletMsgHandler.executeAsync(cmd)
        })
    }
    resp.recover {
      case e: HandledErrorException =>
        WalletCmdErrorResponse(StatusDetail(e.respCode, e.responseMsg))
    }
  }

  private var wallets: Map[String, WalletExt] = Map.empty
  val logger: Logger = getLoggerByClass(classOf[NonActorWalletService])

  private def executeOpWithWalletInfo[T](opContext: String, op: WalletExt => T)
                                (implicit wap: WalletParam): T = {
    _executeOpWithWalletParam(opContext, op)(wap)
  }

  private def _executeOpWithWalletParam[T](opContext: String, op: WalletExt => T)
                                          (implicit wp: WalletParam): T = {

    //for multi-node environment, there would scenarios where a wallet got opened on one node
    // but other/further operations are getting executed on other node (for example which belongs
    // to a pairwise actors or any protocol actors which may spinned up on different nodes)
    // where that wallet is not yet opened. Long term solution would be a architecture change and will take time
    // this is a short term solution to just check if wallet is already opened, if not, open it.
    implicit val w: WalletExt = if (! wallets.contains(wp.getUniqueId)) {
      val ow = _openWallet
      addToOpenedWalletIfReq(ow)
      ow
    } else wallets(wp.getUniqueId)
    _executeOpWithWallet(opContext, op)
  }

  private def _openWallet(implicit wap: WalletParam): WalletExt = {
    walletProvider.open(wap.walletName, wap.encryptionKey, wap.walletConfig)
  }

  private def _executeOpWithWallet[T](opContext: String, op: WalletExt => T)
                                     (implicit w: WalletExt): T = {
    val startTime = LocalDateTime.now
    logger.debug(s"libindy api call started ($opContext)")
    val result = op(w)
    val curTime = LocalDateTime.now
    val millis = ChronoUnit.MILLIS.between(startTime, curTime)
    logger.debug(s"libindy api call finished ($opContext), time taken (in millis): $millis")
    MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_DURATION, millis)
    result
  }

  private def addToOpenedWalletIfReq(w: WalletExt)(implicit wp: WalletParam): Unit = synchronized {
    val uniqueKey = wp.getUniqueId
    if (! wallets.contains(uniqueKey)) {
      wallets += uniqueKey -> w
    }
  }
}
