package com.evernym.verity.protocol.protocols.deaddrop

import java.util.UUID

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.wallet.{CreateNewKey, SignMsg}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import org.apache.commons.codec.digest.DigestUtils

trait DeadDropSpecUtil extends CommonSpecUtil {

  def appConfig: AppConfig

  def prepareDeadDropData(walletAPI: WalletAPI,
                          passphraseOpt: Option[String]=None,
                          dataOpt: Option[Array[Byte]]=None)(implicit wap: WalletAPIParam): DeadDropData = {
    val passphrase = passphraseOpt.getOrElse(UUID.randomUUID().toString.replace("-", ""))
    val nkc = walletAPI.createNewKey(CreateNewKey(seed = Option(passphrase)))
    val recoveryVerKey = nkc.verKey
    val namespace = passphrase  //TODO: is it ok to use passphrase as namespace?
    val locator = DigestUtils.sha256Hex(
      walletAPI.signMsg(SignMsg(KeyParam(Left(recoveryVerKey)), namespace.getBytes)))
    val hashedAddress = DigestUtils.sha256Hex(recoveryVerKey + locator)
    val locatorSignature =
      walletAPI.signMsg(SignMsg(KeyParam(Left(recoveryVerKey)), locator.getBytes))
    DeadDropData(recoveryVerKey, hashedAddress, locator, locatorSignature, dataOpt.getOrElse("test-data".getBytes))
  }

  def generatePayload(): DeadDropData = {
    val testWallet = new TestWallet(createWallet = true)
    prepareDeadDropData(testWallet.walletAPI)(testWallet.wap)
  }

}
