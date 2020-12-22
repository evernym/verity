package com.evernym.verity.actor.testkit

import java.util.concurrent.TimeUnit

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.testkit.util.TestUtil.getSigningKey
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault._
import com.evernym.verity.vault.WalletUtil._

import scala.concurrent.duration.{Duration, FiniteDuration}

object CommonSpecUtil extends CommonSpecUtil

trait CommonSpecUtil {

  import com.evernym.verity.util.Util._

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

  lazy val testWalletConfig: WalletConfig = buildWalletConfig(new TestAppConfig())

  def buildDurationInSeconds(seconds: Int): FiniteDuration = Duration.create(seconds, TimeUnit.SECONDS)

  def getSeparatorLine(length: Int): String = "-" * length

  def generateNewAgentDIDDetail(nameOpt: Option[String]=None, seedOpt: Option[String]=None): AgentDIDDetail = {
    val seed = getSeed(seedOpt)
    val sk = getSigningKey(seed)
    val vk = sk.getVerifyKey.toBytes
    val verKey = Base58Util.encode(vk)
    val did = Base58Util.encode(vk.take(16))
    AgentDIDDetail(nameOpt.getOrElse(did), seed, did, verKey)
  }

  def generateNewDid(seedOpt: Option[String]=None): DidPair = {
    val dinfo = generateNewAgentDIDDetail(seedOpt=seedOpt)
    DidPair(dinfo.did, dinfo.verKey)
  }

  def createWallet(walletId: String, walletAPI: WalletAPI): WalletAPIParam = {
    val wap = WalletAPIParam(walletId)
    try {
      walletAPI.createWallet(wap)
    } catch {
      case _: WalletAlreadyExist =>
        //nothing to do
    }
    wap
  }
}
