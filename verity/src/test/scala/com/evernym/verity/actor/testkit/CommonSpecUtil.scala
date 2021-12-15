package com.evernym.verity.actor.testkit

import java.util.concurrent.TimeUnit
import com.evernym.verity.actor.wallet.{CreateWallet, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.DidPair
import com.evernym.verity.testkit.LegacyWalletAPI
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.util.Base58Util
import com.evernym.verity.util.Util.getSeed
import com.evernym.verity.vault._
import com.evernym.verity.vault.WalletUtil._

import scala.concurrent.duration.{Duration, FiniteDuration}

object CommonSpecUtil extends CommonSpecUtil {
  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig
}

trait CommonSpecUtil extends CanGenerateDid {

  val CONN_ID = "connId"
  val phoneNo: String = "+14045943696"
  val edgeAgentLogoUrl = "https://test-ent.ent.com"
  val edgeAgentName = "Local Enterprise Agent"

  val agencyInternalPathPrefix = "/agency/internal"
  val agencySetupUrlPathPrefix = s"$agencyInternalPathPrefix/setup"

  lazy val user1AgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail(Option("000000000000000000000000userDID1"))
  lazy val user2AgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail(Option("000000000000000000000000userDID2"))
  lazy val ent1AgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail(Option("0000000000000000000000000entDID1"))
  lazy val ent2AgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail(Option("0000000000000000000000000entDID2"))

  lazy val entAgencyAgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail()
  lazy val consumerAgencyAgentDIDDetail: AgentDIDDetail = generateNewAgentDIDDetail()

  def buildConnIdMap(connId: String): Map[String, Any] = Map(CONN_ID -> connId)

  def getConnId(map: Map[String, Any]): String = map(CONN_ID).toString
  def appConfig: AppConfig

  lazy val testWalletConfig: WalletConfig = buildWalletConfig(appConfig)

  def buildDurationInSeconds(seconds: Int): FiniteDuration = Duration.create(seconds, TimeUnit.SECONDS)

  def getSeparatorLine(length: Int): String = "-" * length

  def generateNewDid(seed: String): DidPair = {
    generateNewDid(seedOpt = Option(seed))
  }

  def createWallet(walletId: String, walletAPI: LegacyWalletAPI): WalletAPIParam = {
    val wap = WalletAPIParam(walletId)
    try {
      walletAPI.executeSync[WalletCreated.type](CreateWallet())(wap)
    } catch {
      case _: WalletAlreadyExist =>
        //nothing to do
    }
    wap
  }
}

trait CanGenerateDid {
  def generateNewDid(seedOpt: Option[String]=None): DidPair = {
    val dinfo = generateNewAgentDIDDetail(seedOpt=seedOpt)
    DidPair(dinfo.did, dinfo.verKey)
  }

  def generateNewAgentDIDDetail(nameOpt: Option[String]=None, seedOpt: Option[String]=None): AgentDIDDetail = {
    val seed = getSeed(seedOpt)
    val sk = TestUtil.getSigningKey(seed)
    val vk = sk.getVerifyKey.toBytes
    val verKey = Base58Util.encode(vk)
    val did = Base58Util.encode(vk.take(16))
    AgentDIDDetail(nameOpt.getOrElse(did), seed, did, verKey)
  }
}
