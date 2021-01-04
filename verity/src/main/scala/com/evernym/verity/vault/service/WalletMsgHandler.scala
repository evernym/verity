package com.evernym.verity.vault.service


import java.util.concurrent.ExecutionException

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.actor.wallet._
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest}
import com.evernym.verity.libindy.wallet.operation_executor.{AnoncredsWalletOpExecutor, CryptoOpExecutor, DidOpExecutor, LedgerWalletOpExecutor}
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.{HashUtil, UtilBase}
import com.evernym.verity.vault.{WalletConfig, WalletExt, WalletProvider}

import scala.concurrent.Future

object WalletMsgHandler {

  def executeAsync[T](cmd: Any)(implicit wmp: WalletMsgParam, walletExt: WalletExt): Future[Any] = {
    cmd match {
      case cnk: CreateNewKey                => handleCreateNewKey(cnk)
      case slr: SignLedgerRequest           => handleSignLedgerReq(slr)
      case cd: CreateDID                    => handleCreateDID(cd)
      case stk: StoreTheirKey               => handleStoreTheirKey(stk)
      case gvk: GetVerKey                   => handleGetVerKey(gvk)
      case gvko: GetVerKeyOpt               => handleGetVerKeyOpt(gvko)
      case sm: SignMsg                      => handleSignMsg(sm)
      case vs: VerifySigByKeyParam          => handleVerifySigByKeyParam(vs)
      case pm: PackMsg                      => handlePackMsg(pm)
      case um: UnpackMsg                    => handleUnpackMsg(um)
      case lpm: LegacyPackMsg               => handleLegacyPackMsg(lpm)
      case lum: LegacyUnpackMsg             => handleLegacyUnpackMsg(lum)
      case cms: CreateMasterSecret          => handleCreateMasterSecret(cms)
      case ccd: CreateCredDef               => handleCreateCredDef(ccd)
      case cco: CreateCredOffer             => handleCreateCredOffer(cco)
      case ccr: CreateCredReq               => handleCreateCredReq(ccr)
      case cc: CreateCred                   => handleCreateCred(cc)
      case sc: StoreCred                    => handleStoreCred(sc)
      case cfpr: CredForProofReq            => handleCredForProofReq(cfpr)
      case cp: CreateProof                  => handleCreateProof(cp)
    }
  }

  private def handleCreateProof(proof: CreateProof)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleCreateProof(proof)
  }

  private def handleCredForProofReq(req: CredForProofReq)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleCredForProofReq(req)
  }

  private def handleCreateCred(cred: CreateCred)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleCreateCred(cred)
  }

  private def handleStoreCred(sc: StoreCred)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleStoreCred(sc)
  }

  private def handleCreateCredReq(req: CreateCredReq)(implicit we: WalletExt): Future[CreatedCredReq] = {
    AnoncredsWalletOpExecutor.handleCreateCredReq(req)
  }

  private def handleCreateCredOffer(offer: CreateCredOffer)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleCreateCredOffer(offer)
  }

  private def handleCreateCredDef(ccd: CreateCredDef)(implicit we: WalletExt): Future[CreatedCredDef] = {
    AnoncredsWalletOpExecutor.handleCreateCredDef(ccd)
  }

  private def handleCreateMasterSecret(secret: CreateMasterSecret)(implicit we: WalletExt): Future[String] = {
    AnoncredsWalletOpExecutor.handleCreateMasterSecret(secret)
  }

  private def handleUnpackMsg(um: UnpackMsg)(implicit we: WalletExt): Future[UnpackedMsg] = {
    CryptoOpExecutor.handleUnpackMsg(um)
  }

  private def handleSignMsg(smp: SignMsg)(implicit wmp: WalletMsgParam, we: WalletExt): Future[Array[Byte]] = {
    CryptoOpExecutor.handleSignMsg(smp)
  }

  private def handlePackMsg(pm: PackMsg)(implicit wmp: WalletMsgParam, we: WalletExt): Future[PackedMsg] = {
    CryptoOpExecutor.handlePackMsg(pm, wmp.util, wmp.poolManager)
  }

  private def handleLegacyPackMsg(pm: LegacyPackMsg)(implicit wmp: WalletMsgParam, we: WalletExt): Future[PackedMsg] = {
    CryptoOpExecutor.handleLegacyPackMsg(pm, wmp.util, wmp.poolManager)
  }

  private def handleLegacyUnpackMsg(msg: LegacyUnpackMsg)(implicit wmp: WalletMsgParam, we: WalletExt): Future[UnpackedMsg] = {
    CryptoOpExecutor.handleLegacyUnpackMsg(msg, wmp.util, wmp.poolManager)
  }

  private def handleVerifySigByKeyParam(vs: VerifySigByKeyParam)
                              (implicit wmp: WalletMsgParam, we: WalletExt): Future[VerifySigResult] = {
    handleGetVerKey(GetVerKey(vs.keyParam)).flatMap { verKey =>
      CryptoOpExecutor.verifySig(verKey, vs.challenge, vs.signature)
    }
  }

  private def handleGetVerKeyOpt(gvko: GetVerKeyOpt)(implicit wmp: WalletMsgParam, walletExt: WalletExt): Future[Option[VerKey]] = {
    handleGetVerKey(GetVerKey(gvko.keyParam))
      .map(vk => Option(vk))
      .recover {
        case _: ExecutionException => None
      }
  }

  private def handleStoreTheirKey(stk: StoreTheirKey)(implicit walletExt: WalletExt): Future[TheirKeyStored]= {
    DidOpExecutor.handleStoreTheirKey(stk)
  }

  private def handleCreateDID(d: CreateDID)(implicit walletExt: WalletExt): Future[NewKeyCreated] = {
    DidOpExecutor.handleCreateDID(d)
  }

  private def handleCreateNewKey(cnk: CreateNewKey)(implicit walletExt: WalletExt): Future[NewKeyCreated] = {
    DidOpExecutor.handleCreateNewKey(cnk)
  }

  private def handleSignLedgerReq(slr: SignLedgerRequest)(implicit walletExt: WalletExt): Future[LedgerRequest] = {
    LedgerWalletOpExecutor.handleSignRequest(
      slr.submitterDetail.did,
      slr.request)
  }

  /**
   * purposefully not returning a future as mostly the calling code has to do a
   * state change when this function returns a wallet
   * @param wmp wallet msg param
   * @return
   */
  def handleCreateWalletASync()(implicit wmp: WalletMsgParam): Future[WalletCreated.type] = {
    wmp.walletProvider.createAsync(
      wmp.walletParam.walletName, wmp.walletParam.encryptionKey, wmp.walletParam.walletConfig)
  }

  /**
   * purposefully not returning a future as mostly the calling code has to do a
   * state change when this function returns a wallet
   * @param wmp wallet msg param
   * @return
   */
  def handleCreateAndOpenWalletSync()(implicit wmp: WalletMsgParam): WalletExt = {
    wmp.walletProvider.createSync(
      wmp.walletParam.walletName, wmp.walletParam.encryptionKey, wmp.walletParam.walletConfig)
    wmp.walletProvider.openSync(
      wmp.walletParam.walletName, wmp.walletParam.encryptionKey, wmp.walletParam.walletConfig)
  }

  def handleGetVerKey(gvk: GetVerKey)(implicit wmp: WalletMsgParam, we: WalletExt): Future[VerKey] = {
    gvk.keyParam.verKeyParam.fold (
      l => Future.successful(l),
      r => {
        wmp.util.getVerKey(r.did, we, r.getKeyFromPool, wmp.poolManager)
      }
    )
  }
}

/**
 * a parameter to be used by WalletMsgHandler
 * @param walletProvider
 * @param walletParam
 * @param util
 * @param poolManager
 */
case class WalletMsgParam(walletProvider: WalletProvider,
                          walletParam: WalletParam,
                          util: UtilBase,
                          poolManager: LedgerPoolConnManager) {
}

/**
 * a parameter to be used to create or open wallet
 * @param walletId
 * @param walletName
 * @param encryptionKey
 * @param walletConfig
 */
case class WalletParam(walletId: String, walletName: String, encryptionKey: String, walletConfig: WalletConfig) {
  def getUniqueId: String = HashUtil.safeMultiHash(SHA256, walletId , encryptionKey).hex
}