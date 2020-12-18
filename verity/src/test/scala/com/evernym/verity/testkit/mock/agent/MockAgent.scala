package com.evernym.verity.testkit.mock.agent

import java.util.UUID

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.testkit.AgentDIDDetail
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, StoreTheirKey}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, SenderDetail}
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.mock.edge_agent.MockPairwiseConnDetail
import com.evernym.verity.util.Util.{getAgentKeyDlgProof, logger}
import com.evernym.verity.vault._

/**
 * a mock agent
 */
trait MockAgent extends HasWalletHelper with HasAppConfig {

  def myDIDDetail: AgentDIDDetail  //selfDID detail
  def appConfig: AppConfig
  def initSpecific(): Unit = {}

  override def name: String = myDIDDetail.name

  //my all pairwise connection's DID details
  var pairwiseConnDetails: Map[String, MockPairwiseConnDetail] = Map.empty

  def setupWallet(): Unit = {
    //creates public DID and ver key and store it into its wallet
    walletAPI.createNewKey(CreateNewKey(seed = Option(myDIDDetail.DIDSeed)))
  }

  def init(): Unit = {
    setupWallet()

    //this initSpecific will be overridden by specific implementation if it wants to do anything during initialization
    initSpecific()
  }

  init()

  def getVerKeyFromWallet(did: DID): VerKey = walletAPI.getVerKey(
    GetVerKeyByKeyInfoParam(KeyInfo(Right(GetVerKeyByDIDParam(did, getKeyFromPool = false)))))

  def buildInviteSenderDetail(connId: String, kdpOpt: Option[AgentKeyDlgProof]): SenderDetail = {
    val pcd = pairwiseConnDetail(connId)
    SenderDetail(pcd.myPairwiseDidPair.DID, pcd.myPairwiseDidPair.verKey,
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
    pairwiseConnDetail(connId).myCloudAgentPairwiseDidPair.DID

  private def addNewPairwiseConnDetail(connId: String, mpcd: MockPairwiseConnDetail): Unit = {
    pairwiseConnDetails = pairwiseConnDetails ++ Map(connId -> mpcd)
  }

  def addNewKey(seedOpt: Option[String]=None): NewKeyCreated = {
    val seed = seedOpt orElse Option(UUID.randomUUID().toString.replace("-", ""))
    walletAPI.createNewKey(CreateNewKey(seed = seed))
  }

  def addNewLocalPairwiseKey(connId: String): MockPairwiseConnDetail = {
    if (pairwiseConnDetails.contains(connId)) {
      throw new RuntimeException(s"name '$connId' is already used, please provide unique name")
    }
    val cnkp = addNewKey()
    val dd = DidPair(cnkp.did, cnkp.verKey)
    val mpcd = new MockPairwiseConnDetail(dd)(walletAPI, wap)
    addNewPairwiseConnDetail(connId, mpcd)
    mpcd
  }

  def storeTheirKey(did: DID, verKey: VerKey): Unit = {
    logger.debug(s"Store their key for did: $did")
    val skp = StoreTheirKey(did, verKey)
    walletAPI.storeTheirKey(skp)(wap)
  }

  def storeTheirKey(DIDDetail: DidPair): Unit = {
    storeTheirKey(DIDDetail.DID, DIDDetail.verKey)
  }

  private def buildAgentKeyDlgProof(pcd: MockPairwiseConnDetail): AgentKeyDlgProof = {
    getAgentKeyDlgProof(pcd.myPairwiseDidPair.verKey, pcd.myCloudAgentPairwiseDidPair.DID,
      pcd.myCloudAgentPairwiseDidPair.verKey)(walletAPI, wap)
  }

  def buildAgentKeyDlgProofForConn(connId: String): AgentKeyDlgProof = {
    val pcd = pairwiseConnDetail(connId)
    buildAgentKeyDlgProof(pcd)
  }

}
