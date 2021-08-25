package com.evernym.verity.testkit.mock.agent

import java.util.UUID
import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, SenderDetail}
import com.evernym.verity.testkit.{HasDefaultTestWallet, HasTestWalletAPI}
import com.evernym.verity.testkit.util.PublicIdentifier
import com.evernym.verity.util.Util.getAgentKeyDlgProof
import com.evernym.verity.vault.EncryptParam

/**
 * a mock agent
 */
trait MockAgent
  extends HasTestWalletAPI
    with CommonSpecUtil
    with HasAppConfig
    with HasDefaultTestWallet {

  private val logger = getLoggerByName("MockAgent")

  def myDIDDetail: AgentDIDDetail  //selfDID detail
  def appConfig: AppConfig
  def initSpecific(): Unit = {}

  def name: String = myDIDDetail.name
  override def createWallet: Boolean = true

  //my all pairwise connection's DID details
  type ConnId = String
  var pairwiseConnDetails: Map[ConnId, MockPairwiseConnDetail] = Map.empty
  var publicIdentifier: Option[PublicIdentifier] = None

  def setupWallet(): Unit = {
    //creates public DID and ver key and store it into its wallet
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option(myDIDDetail.DIDSeed)))
  }

  def init(): Unit = {
    setupWallet()

    //this initSpecific will be overridden by specific implementation if it wants to do anything during initialization
    initSpecific()
  }

  init()

  def getVerKeyFromWallet(did: DidStr): VerKeyStr = testWalletAPI.executeSync[GetVerKeyResp](GetVerKey(did)).verKey

  def buildInviteSenderDetail(connId: String, kdpOpt: Option[AgentKeyDlgProof]): SenderDetail = {
    val pcd = pairwiseConnDetail(connId)
    SenderDetail(pcd.myPairwiseDidPair.did, pcd.myPairwiseDidPair.verKey,
      kdpOpt, Option(myDIDDetail.name), Option("some-logo-url"), None)
  }

  def encryptParamForOthersPairwiseKey(connId: String): EncryptParam = {
    val pcd = pairwiseConnDetail(connId)
    pcd.getEncryptParamForOthersCloudAgent
  }

  def pairwiseConnDetail(connId: String): MockPairwiseConnDetail = {
    pairwiseConnDetails(connId)
  }

  def cloudAgentPairwiseDIDForConn(connId: String): String =
    pairwiseConnDetail(connId).myCloudAgentPairwiseDidPair.did

  private def addNewPairwiseConnDetail(connId: String, mpcd: MockPairwiseConnDetail): Unit = {
    pairwiseConnDetails = pairwiseConnDetails ++ Map(connId -> mpcd)
  }

  def addNewKey(seedOpt: Option[String]=None): NewKeyCreated = {
    val seed = seedOpt orElse Option(UUID.randomUUID().toString.replace("-", ""))
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = seed))
  }

  def addNewLocalPairwiseKey(connId: String): MockPairwiseConnDetail = {
    if (pairwiseConnDetails.contains(connId)) {
      throw new RuntimeException(s"name '$connId' is already used, please provide unique name")
    }
    val cnkp = addNewKey()
    addNewLocalPairwiseKey(connId, cnkp.didPair)
  }

  def addNewLocalPairwiseKey(connId: String, dp: DidPair, storeKey: Boolean = false): MockPairwiseConnDetail = {
    if (pairwiseConnDetails.contains(connId)) {
      throw new RuntimeException(s"name '$connId' is already used, please provide unique name")
    }
    if (storeKey)
      storeTheirKey(dp)
    val dd = DidPair(dp.did, dp.verKey)
    val mpcd = new MockPairwiseConnDetail(dd)(testWalletAPI, wap)
    addNewPairwiseConnDetail(connId, mpcd)
    mpcd
  }

  def storeTheirKey(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false): Unit = {
    logger.debug(s"Store their key for did: $did")
    val stk = StoreTheirKey(did, verKey, ignoreIfAlreadyExists)
    testWalletAPI.executeSync[TheirKeyStored](stk)(wap)
  }

  def storeTheirKey(DIDDetail: DidPair): Unit = {
    storeTheirKey(DIDDetail.did, DIDDetail.verKey)
  }

  private def buildAgentKeyDlgProof(pcd: MockPairwiseConnDetail): AgentKeyDlgProof = {
    getAgentKeyDlgProof(pcd.myPairwiseDidPair.verKey, pcd.myCloudAgentPairwiseDidPair.did,
      pcd.myCloudAgentPairwiseDidPair.verKey)(testWalletAPI, wap)
  }

  def buildAgentKeyDlgProofForConn(connId: String): AgentKeyDlgProof = {
    val pcd = pairwiseConnDetail(connId)
    buildAgentKeyDlgProof(pcd)
  }

}
