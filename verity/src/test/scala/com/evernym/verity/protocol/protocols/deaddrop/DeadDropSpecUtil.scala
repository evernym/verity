package com.evernym.verity.protocol.protocols.deaddrop

import java.util.UUID

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, SignMsg}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.{LegacyWalletAPI, TestWallet}
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import org.apache.commons.codec.digest.DigestUtils

trait DeadDropSpecUtil extends CommonSpecUtil {

  def appConfig: AppConfig

  def prepareDeadDropData(walletAPI: LegacyWalletAPI,
                          passphraseOpt: Option[String]=None,
                          dataOpt: Option[Array[Byte]]=None)(implicit wap: WalletAPIParam): DeadDropData = {
    val passphrase = passphraseOpt.getOrElse(UUID.randomUUID().toString.replace("-", ""))
    val nkc = walletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option(passphrase)))
    val recoveryVerKey = nkc.verKey
    val namespace = passphrase  //TODO: is it ok to use passphrase as namespace?
    val locator = DigestUtils.sha256Hex(
      walletAPI.executeSync[Array[Byte]](SignMsg(KeyParam(Left(recoveryVerKey)), namespace.getBytes)))
    val hashedAddress = DigestUtils.sha256Hex(recoveryVerKey + locator)
    val locatorSignature =
      walletAPI.executeSync[Array[Byte]](SignMsg(KeyParam(Left(recoveryVerKey)), locator.getBytes))
    DeadDropData(recoveryVerKey, hashedAddress, locator, locatorSignature, dataOpt.getOrElse("test-data".getBytes))
  }

  def generatePayload(): DeadDropData = {
    val testWallet = new TestWallet(createWallet = true)
    prepareDeadDropData(testWallet.testWalletAPI)(testWallet.wap)
  }

}
