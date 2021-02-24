package com.evernym.verity.testkit

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.vault.WalletUtil.generateWalletParamSync
import com.evernym.verity.vault._
import com.evernym.verity.vault.service.{AsyncToSync, WalletMsgHandler, WalletMsgParam, WalletParam}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future


class LegacyWalletAPI(appConfig: AppConfig,
                      walletProvider: WalletProvider,
                      ledgerPoolManager: Option[LedgerPoolConnManager])
  extends WalletAPI
    with AsyncToSync {

  val logger: Logger = getLoggerByClass(getClass)
  var walletParams: Map[String, WalletParam] = Map.empty
  var wallets: Map[String, WalletExt] = Map.empty

  private def executeOpWithWalletInfo[T](opContext: String, op: WalletExt => T)
                                        (implicit wap: WalletAPIParam): T = {
    _executeOpWithWalletParam(opContext, op)(wap)
  }

  private def _executeOpWithWalletParam[T](opContext: String, op: WalletExt => T)
                                          (implicit wap: WalletAPIParam): T = {

    //for multi-node environment, there would scenarios where a wallet got opened on one node
    // but other/further operations are getting executed on other node (for example which belongs
    // to a pairwise actors or any protocol actors which may spinned up on different nodes)
    // where that wallet is not yet opened. Long term solution would be a architecture change and will take time
    // this is a short term solution to just check if wallet is already opened, if not, open it.
    implicit val wp: WalletParam = getWalletParam(wap)

    implicit val w: WalletExt = if (!wallets.contains(wp.getUniqueId)) {
      val ow = _openWallet
      addToOpenedWalletIfReq(ow)
      ow
    } else wallets(wp.getUniqueId)
    _executeOpWithWallet(opContext, op)
  }

  private def _openWallet(implicit wap: WalletParam): WalletExt = {
    walletProvider.openSync(wap.walletName, wap.encryptionKey, wap.walletConfig)
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
    if (!wallets.contains(uniqueKey)) {
      wallets += uniqueKey -> w
      walletParams += wp.walletId -> wp
    }
  }

  private def getWalletParam(wap: WalletAPIParam): WalletParam = {
    walletParams.getOrElse(wap.walletId, {
      val wp = generateWalletParamSync(wap.walletId, appConfig, walletProvider)
      walletParams += wap.walletId -> wp
      wp
    })
  }

  def executeAsync[T](cmd: Any)(implicit wap: WalletAPIParam): Future[T] = {
    val wp = generateWalletParamSync(wap.walletId, appConfig, walletProvider)
    implicit val wmp: WalletMsgParam = WalletMsgParam(walletProvider, wp, ledgerPoolManager)
    val resp = cmd match {
      case CreateWallet =>
        val walletExt = WalletMsgHandler.handleCreateAndOpenWalletSync()
        addToOpenedWalletIfReq(walletExt)(wp)
        Future(WalletCreated)
      case other        =>
        executeOpWithWalletInfo(cmd.getClass.getSimpleName, { implicit we: WalletExt =>
          WalletMsgHandler.executeAsync[T](other)
        })
    }
    resp.map(_.asInstanceOf[T])
  }

  final def executeSync[T](cmd: Any)(implicit wap: WalletAPIParam): T = {
    convertToSyncReq(executeAsync(cmd))
  }
}
