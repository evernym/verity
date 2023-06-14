package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.util2.Status.MSG_STATUS_CREATED
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.constants.Constants.DEFAULT_INVITE_RECEIVER_USER_NAME
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.connecting.common.{InviteDetail, SenderAgencyDetail}
import com.evernym.verity.util.MsgIdProvider.getNewMsgId
import com.evernym.verity.util2.UrlParam

import com.evernym.verity.vdr.Ledgers
import scala.io.Source

trait HasCloudAgent { this: MockAgent =>

  private val logger = getLoggerByName("HasCloudAgent")

  //my agency's public DID detail
  // var agencyPublicDid: Option[AgencyPublicDid] = None
  var agencyPublicDid: Option[AgencyPublicDid] = Some(AgencyPublicDid(verKey = "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL", DID = "V4SGRU86Z58d6TV7PBUe6f", ledgers = Some(agencyLedgerDetail())))

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

  def setAgencyPairwiseAgentDetail(id: String, verKey: VerKeyStr): Unit = {
    agencyPairwiseAgentDetail = Option(DidPair(id, verKey))
  }

    def agencyLedgerDetail(): Ledgers = {
    // Architecture requested that this be future-proofed by assuming Agency will have more than one ledger.
    val genesis = try {
      val genesisFileLocation = "/home/abddulbois/Documents/verity/target/genesis.txt"
      val genesisFileSource = Source.fromFile(genesisFileLocation)
      val lines = genesisFileSource.getLines().toList
      genesisFileSource.close()
      lines
    } catch {
      case e: Exception =>
        logger.error(s"Could not read config")
        List()
    }

    val ledgers: Ledgers = List(Map(
      "name" -> "default",
      "genesis" -> genesis,
      "taa_enabled" -> true
    ))
    ledgers
  }
}
