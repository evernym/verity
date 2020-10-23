package com.evernym.verity.testkit.mock

import com.evernym.verity.constants.Constants.DEFAULT_INVITE_RECEIVER_USER_NAME
import com.evernym.verity.Status.MSG_STATUS_CREATED
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{InviteDetail, SenderAgencyDetail}
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.mock.cloud_agent.MockCloudAgentBase
import com.evernym.verity.testkit.mock.edge_agent.MockPairwiseConnDetail
import com.evernym.verity.util.MsgIdProvider._
import com.evernym.verity.util.Util.logger
import com.evernym.verity.UrlDetail
import com.evernym.verity.actor.agent.DidPair

trait HasCloudAgent { this: MockAgent =>

  //my agency's public DID detail
  var agencyPublicDid: Option[AgencyPublicDid] = None

  //my agency's pairwise DID for me
  var agencyPairwiseAgentDetail: Option[DidPair] = None

  //my cloud agent's detail
  var cloudAgentDetail: Option[DidPair] = None

  def agencyEndpoint: UrlDetail

  def agencyPublicDIDReq: DID = agencyPublicDid.get.DID

  def getAgencyDIDReq: DID = agencyPublicDid.get.DID

  lazy val senderAgencyDetail: SenderAgencyDetail = SenderAgencyDetail(agencyPublicDid.get.DID, agencyPublicDid.get.verKey, agencyEndpoint.toString)

  def setInviteData(connId: String, mockEdgeCloudAgent: MockCloudAgentBase): Unit = {
    val edgePcd = setPairwiseData(connId, mockEdgeCloudAgent)
    val keyDlgProof = buildAgentKeyDlgProofForConn(connId)
    val senderDetail = buildInviteSenderDetail(connId, Option(keyDlgProof))
    edgePcd.lastSentInvite = InviteDetail(getNewMsgId, DEFAULT_INVITE_RECEIVER_USER_NAME, senderAgencyDetail,
      senderDetail, MSG_STATUS_CREATED.statusCode, "msg-created", "1.0")
  }

  private def setPairwiseData(connId: String, mockEdgeCloudAgent: MockCloudAgentBase): MockPairwiseConnDetail = {
    val edgePcd = addNewLocalPairwiseKey(connId)
    val edgeCloudPcd = mockEdgeCloudAgent.addNewLocalPairwiseKey(connId)
    edgeCloudPcd.setTheirPairwiseDidPair(edgePcd.myPairwiseDidPair.DID, edgePcd.myPairwiseDidPair.verKey)
    edgePcd.setMyCloudAgentPairwiseDidPair(edgeCloudPcd.myPairwiseDidPair.DID, edgeCloudPcd.myPairwiseDidPair.verKey)
    edgePcd
  }

  def setCloudAgentDetail(agentDID: DidPair): Unit = {
    logger.debug(s"Set cloud agent detail for did: ${agentDID.DID}")
    storeTheirKey(agentDID.DID, agentDID.verKey)
    logger.debug("Set mock client cloudAgentDetail")
    cloudAgentDetail = Option(agentDID)
  }

  def setAgencyIdentity(ad: AgencyPublicDid): Unit = {
    storeTheirKey(ad.DID, ad.verKey)
    agencyPublicDid = Option(ad)
  }

  def setAgencyIdentityWithRandomData(): Unit = {
    if (agencyPublicDid.isDefined)
      throw new RuntimeException("agency detail is already set")
    val dd = generateNewDid()
    setAgencyIdentity(AgencyPublicDid(dd.DID, dd.verKey))
  }

  def updateCloudAgentPairwiseKeys(connId: String, did: DID, verKey: VerKey): Unit = {
    if (! pairwiseConnDetails.contains(connId)) {
      throw new RuntimeException(s"name '$connId' is not yet created")
    }
    val pcd = pairwiseConnDetail(connId)
    pcd.setTheirPairwiseDidPair(did, verKey)
  }

  def setAgencyPairwiseAgentDetail(id: String, verKey: VerKey): Unit = {
    agencyPairwiseAgentDetail = Option(DidPair(id, verKey))
  }
}
