package com.evernym.verity.protocol.protocols.deaddrop

import java.util.UUID

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.config.AppConfig
import com.evernym.verity.libindy.{IndyLedgerPoolConnManager, LibIndyWalletProvider}
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.vault.{CreateNewKeyParam, WalletAPI, WalletAccessParam, WalletExt}
import org.apache.commons.codec.digest.DigestUtils
import org.hyperledger.indy.sdk.crypto.Crypto

trait DeadDropSpecUtil extends CommonSpecUtil {

  def appConfig: AppConfig

  def prepareDeadDropData(walletAPI: WalletAPI,
                          walletExt: WalletExt,
                          passphraseOpt: Option[String]=None,
                          dataOpt: Option[Array[Byte]]=None)(implicit wap: WalletAccessParam): DeadDropData = {
    val passphrase = passphraseOpt.getOrElse(UUID.randomUUID().toString.replace("-", ""))
    val nkc = walletAPI.createNewKey(CreateNewKeyParam(seed = Option(passphrase)))
    val recoveryVerKey = nkc.verKey
    val namespace = passphrase  //TODO: is it ok to use passphrase as namespace?
    val locator = DigestUtils.sha256Hex(Crypto.cryptoSign(walletExt.wallet, recoveryVerKey, namespace.getBytes).get)
    val hashedAddress = DigestUtils.sha256Hex(recoveryVerKey + locator)
    val locatorSignature = Crypto.cryptoSign(walletExt.wallet, recoveryVerKey, locator.getBytes).get
    DeadDropData(recoveryVerKey, hashedAddress, locator, locatorSignature, dataOpt.getOrElse("test-data".getBytes))
  }

  def generatePayload(): DeadDropData = {
    implicit lazy val walletAPI: WalletAPI =
      new WalletAPI(new LibIndyWalletProvider(appConfig), TestUtil, new IndyLedgerPoolConnManager(appConfig))

    lazy val (walletExt, wap) = {
      val key = walletAPI.generateWalletKey()
      val wap = buildWalletAccessParam(key, key)
      val wallet = walletAPI.createAndOpenWallet(wap)
      (wallet, wap)
    }

    prepareDeadDropData(walletAPI, walletExt)(wap)
  }

}
