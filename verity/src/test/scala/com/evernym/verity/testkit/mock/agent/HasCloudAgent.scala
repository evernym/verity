package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.util2.Status.MSG_STATUS_CREATED
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.constants.Constants.DEFAULT_INVITE_RECEIVER_USER_NAME
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.protocol.protocols.connecting.common.{InviteDetail, SenderAgencyDetail}
import com.evernym.verity.util.MsgIdProvider.getNewMsgId
import com.evernym.verity.util2.UrlParam

trait HasCloudAgent { this: MockAgent =>

  private val logger = getLoggerByName("HasCloudAgent")

  //my agency's public DID detail
  var agencyPublicDid: Option[AgencyPublicDid] = None

  //my agency's pairwise DID for me
  var agencyPairwiseAgentDetail: Option[DidPair] = None

  //my cloud agent's detail
  var cloudAgentDetail: Option[DidPair] = None

  def agencyEndpoint: UrlParam

  def agencyPublicDIDReq: DidStr = agencyPublicDid.get.DID

  def getAgencyDIDReq: DidStr = agencyPublicDid.get.DID

  lazy val senderAgencyDetail: SenderAgencyDetail = SenderAgencyDetail(agencyPublicDid.get.DID, agencyPublicDid.get.verKey, agencyEndpoint.toString)

  def setInviteData(connId: String, mockEdgeCloudAgent: MockCloudAgent): Unit = {
    val edgePcd = setPairwiseData(connId, mockEdgeCloudAgent)
    val keyDlgProof = buildAgentKeyDlgProofForConn(connId)
    val senderDetail = buildInviteSenderDetail(connId, Option(keyDlgProof))
    edgePcd.lastSentInvite = InviteDetail(getNewMsgId, DEFAULT_INVITE_RECEIVER_USER_NAME, senderAgencyDetail,
      senderDetail, MSG_STATUS_CREATED.statusCode, "msg-created", "1.0")
  }

  private def setPairwiseData(connId: String, mockEdgeCloudAgent: MockCloudAgent): MockPairwiseConnDetail = {
    val edgePcd = addNewLocalPairwiseKey(connId)
    val edgeCloudPcd = mockEdgeCloudAgent.addNewLocalPairwiseKey(connId)
    edgeCloudPcd.setTheirPairwiseDidPair(edgePcd.myPairwiseDidPair.did, edgePcd.myPairwiseDidPair.verKey)
    edgePcd.setMyCloudAgentPairwiseDidPair(edgeCloudPcd.myPairwiseDidPair.did, edgeCloudPcd.myPairwiseDidPair.verKey)
    edgePcd
  }

  def setCloudAgentDetail(agentDID: DidPair): Unit = {
    logger.debug(s"Set cloud agent detail for did: ${agentDID.did}")
    storeTheirKey(agentDID.did, agentDID.verKey)
    logger.debug("Set mock client cloudAgentDetail")
    cloudAgentDetail = Option(agentDID)
  }

  def setAgencyIdentity(ad: AgencyPublicDid): Unit = {
    storeTheirKey(ad.DID, ad.verKey, ignoreIfAlreadyExists = true)
    agencyPublicDid = Option(ad)
  }

  def setAgencyIdentityWithRandomData(): Unit = {
    if (agencyPublicDid.isDefined)
      throw new RuntimeException("agency detail is already set")
    val dd = generateNewDid()
    setAgencyIdentity(AgencyPublicDid(dd.did, dd.verKey))
  }

  def updateCloudAgentPairwiseKeys(connId: String, did: DidStr, verKey: VerKeyStr): Unit = {
    if (! pairwiseConnDetails.contains(connId)) {
      throw new RuntimeException(s"name '$connId' is not yet created")
    }
    val pcd = pairwiseConnDetail(connId)
    pcd.setTheirPairwiseDidPair(did, verKey)
  }

  def setAgencyPairwiseAgentDetail(id: String, verKey: VerKeyStr): Unit = {
    agencyPairwiseAgentDetail = Option(DidPair(id, verKey))
  }
}
