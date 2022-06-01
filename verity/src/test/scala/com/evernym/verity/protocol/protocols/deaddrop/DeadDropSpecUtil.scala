package com.evernym.verity.protocol.protocols.deaddrop

import akka.actor.ActorSystem

import java.util.UUID
import com.evernym.verity.actor.testkit.{CommonSpecUtil, HasActorSystem}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, SignMsg, SignedMsg}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.vault.wallet_api.StandardWalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import org.apache.commons.codec.digest.DigestUtils

trait DeadDropSpecUtil extends CommonSpecUtil with HasExecutionContextProvider with HasActorSystem{

  def appConfig: AppConfig

  def prepareDeadDropData(walletAPI: StandardWalletAPI,
                          passphraseOpt: Option[String]=None,
                          dataOpt: Option[Array[Byte]]=None)(implicit wap: WalletAPIParam): DeadDropData = {
    val passphrase = passphraseOpt.getOrElse(UUID.randomUUID().toString.replace("-", ""))
    val nkc = walletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option(passphrase)))
    val recoveryVerKey = nkc.verKey
    val namespace = passphrase  //TODO: is it ok to use passphrase as namespace?
    val locator = DigestUtils.sha256Hex(
      walletAPI.executeSync[SignedMsg](SignMsg(KeyParam(Left(recoveryVerKey)), namespace.getBytes)).msg)
    val hashedAddress = DigestUtils.sha256Hex(recoveryVerKey + locator)
    val locatorSignature =
      walletAPI.executeSync[SignedMsg](SignMsg(KeyParam(Left(recoveryVerKey)), locator.getBytes)).msg
    DeadDropData(recoveryVerKey, hashedAddress, locator, locatorSignature, dataOpt.getOrElse("test-data".getBytes))
  }

  def generatePayload(): DeadDropData = {
    val testWallet = new TestWallet(futureExecutionContext, createWallet = true, system)
    prepareDeadDropData(testWallet.testWalletAPI)(testWallet.wap)
  }

}
