package com.evernym.verity.actor.persistence.recovery.latest.verity2.vas

import java.util.UUID

import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.persistence.recovery.base.{AgentIdentifiers, BasePersistentStore}
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR}
import com.google.protobuf.ByteString

//base traits for different type of agent actor setup

trait AgencyAgentEventSetter extends AgentIdentifiers { this: BasePersistentStore =>

  def aaRegion: agentRegion = agentRegion(myAgencyAgentEntityId, agencyAgentRegion)

  def setupBasicAgencyAgent(): Unit = {
    //TODO: if we move below line 'setting of key value mapper' to any other
    // position in this method, ideally it should work but for some reason it doesn't, should find out why?
    storeAgencyDIDKeyValueMapping(myAgencyAgentDIDPair.DID)
    setupBasicAgencyAgentWalletData()
    addEventsToPersistentStorage(myAgencyAgentPersistenceId, basicAgencyAgentEvents)
    storeAgentRoute(myAgencyAgentDIDPair.DID, ACTOR_TYPE_AGENCY_AGENT_ACTOR, myAgencyAgentEntityId)
  }

  private def setupBasicAgencyAgentWalletData(): Unit = {
    createWallet(myAgencyAgentEntityId)
    createNewKey(myAgencyAgentEntityId, Option(myAgencyAgentDIDKeySeed))
  }

  protected lazy val basicAgencyAgentEvents = scala.collection.immutable.Seq(
    KeyCreated(myAgencyAgentDIDPair.DID, myAgencyAgentDIDPair.verKey),
    EndpointSet()
  )
}

trait UserAgentCommon extends AgentIdentifiers { this: BasePersistentStore =>
  lazy val publicKey = createDID(mySelfRelAgentEntityId)   //publicKey
}

trait UserAgentEventSetter extends UserAgentCommon { this: BasePersistentStore =>

  def uaRegion: agentRegion = agentRegion(mySelfRelAgentEntityId, userAgentRegionActor)

  def setupBasicUserAgent(): Unit = {
    setupBasicUserAgentWalletData()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents)
    storeAgentRoute(mySelfRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
    storeAgentRoute(mySelfRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentEntityId)
  }

  val sponsorRel = SponsorRel("00000000000000000000LocalSponsor", UUID.randomUUID().toString)
  val pubIdCreatedMsgId = UUID.randomUUID().toString
  val pubIdLookupMsgId = UUID.randomUUID().toString
  val updateConfigStatusReportMsgId = UUID.randomUUID().toString
  val writeSchemaStatusReportMsgId = UUID.randomUUID().toString
  val writeCredDefStatusReportMsgId = UUID.randomUUID().toString

  protected lazy val basicUserAgentEvents = scala.collection.immutable.Seq(
    OwnerDIDSet(mySelfRelDIDPair.DID, mySelfRelDIDPair.verKey),
    AgentKeyCreated(mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey),
    SponsorAssigned(sponsorRel.sponsorId, sponsorRel.sponseeId),
    RequesterKeyAdded(requesterDIDPair.verKey),
    ComMethodUpdated("webhook", 2, "http://localhost:6001", Option(ComMethodPackaging("1.0", Seq(requesterDIDPair.verKey)))),

    PublicIdentityStored(publicKey.did, publicKey.verKey),
    MsgCreated(pubIdCreatedMsgId, "public-identifier-created", mySelfRelAgentDIDPair.DID, "MS-103",
      1615697663705l,1615697663705l,"",Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(pubIdCreatedMsgId,ByteString.copyFromUtf8("public-identifier-created"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/public-identifier-created","plain"))),
    MsgCreated(pubIdLookupMsgId, "public-identifier", mySelfRelAgentDIDPair.DID, "MS-103",
      1615697663705l,1615697663705l,"",Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(pubIdLookupMsgId,ByteString.copyFromUtf8("public-identifier"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/public-identifier","plain"))),

    ConfigUpdated("name","name1",1615697665879l),
    ConfigUpdated("logoUrl","/logo_url.ico",1615697665880l),
    MsgCreated(updateConfigStatusReportMsgId, "status-report", mySelfRelAgentDIDPair.DID, "MS-103",
      1615697663705l,1615697663705l,"",Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(updateConfigStatusReportMsgId,ByteString.copyFromUtf8("update-configs/0.6/status-report"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/update-configs/0.6/status-report","plain"))),

    MsgCreated(writeSchemaStatusReportMsgId, "status-report", mySelfRelAgentDIDPair.DID, "MS-103",
      1615697663705l,1615697663705l,"",Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(writeSchemaStatusReportMsgId,ByteString.copyFromUtf8("write-schema/0.6/status-report"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/write-schema/0.6/status-report","plain"))),

    MsgCreated(writeCredDefStatusReportMsgId, "status-report", mySelfRelAgentDIDPair.DID, "MS-103",
      1615697663705l,1615697663705l,"",Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(writeCredDefStatusReportMsgId,ByteString.copyFromUtf8("write-cred-def/0.6/status-report"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/write-cred-def/0.6/status-report","plain"))),

    //event for each new connection
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey),
  )

  private def setupBasicUserAgentWalletData(): Unit = {
    createWallet(mySelfRelAgentEntityId)
    createNewKey(mySelfRelAgentEntityId, Option(mySelfRelAgentDIDKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, mySelfRelDIDPair)
  }
}

trait UserAgentPairwiseEventSetter extends UserAgentCommon { this: BasePersistentStore =>

  def uapRegion: agentRegion = agentRegion(myPairwiseRelAgentEntityId, userAgentPairwiseRegionActor)

  def setupBasicUserAgentPairwise(): Unit = {
    setupBasicUserAgentPairwiseWalletData()
    storeUserAgentPairwiseEvents()
  }

  private def setupBasicUserAgentPairwiseWalletData(): Unit = {
    createNewKey(mySelfRelAgentEntityId, Option(myPairwiseRelAgentKeySeed))
    storeTheirKey(mySelfRelAgentEntityId, myPairwiseRelDIDPair)
    storeTheirKey(mySelfRelAgentEntityId, theirPairwiseRelDIDPair)
  }

  private def storeUserAgentPairwiseEvents(): Unit = {
    addEventsToPersistentStorage(myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents)
    storeAgentRoute(myPairwiseRelDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
    storeAgentRoute(myPairwiseRelAgentDIDPair.DID, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, myPairwiseRelAgentEntityId)
  }

  val invitationMsgId = UUID.randomUUID().toString
  val reqReceivedMsgId = UUID.randomUUID().toString
  val respSentMsgId = UUID.randomUUID().toString
  val respMsgId = UUID.randomUUID().toString

  protected lazy val basicUserAgentPairwiseEvents = scala.collection.immutable.Seq(
    PublicIdentityStored(publicKey.did, publicKey.verKey),
    OwnerSetForAgent(mySelfRelDIDPair.DID, mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey),
    AgentDetailSet(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID, myPairwiseRelDIDPair.verKey, myPairwiseRelAgentDIDPair.verKey),

    MsgCreated(invitationMsgId,"invitation",myPairwiseRelDIDPair.DID,"MS-103",1615697693226l,1615697693226l,"",
      Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(invitationMsgId,ByteString.copyFromUtf8("invitation"),
      Some(PayloadContext("did:sov:123456789abcdefghi1234;spec/relationship/1.0/invitation","plain"))),

    MsgCreated(reqReceivedMsgId,"request-received",myPairwiseRelDIDPair.DID,"MS-103",1615697693226l,1615697693226l,"",
      Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(reqReceivedMsgId,ByteString.copyFromUtf8("request-received"),
      Some(PayloadContext("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/request-received","plain"))),

    ConnectionStatusUpdated(
      reqReceived = true,
      answerStatusCode = "MS-104",
      None,
      Option(TheirProvisionalDidDocDetail(
        theirPairwiseRelDIDPair.DID,
        theirPairwiseRelDIDPair.verKey,
        "http://localhost:9001/agency/msg",
        Seq(theirPairwiseRelAgentDIDPair.DID, theirAgencyAgentDIDPair.DID)
      ))
    ),

    MsgCreated(respSentMsgId,"response-sent",myPairwiseRelDIDPair.DID,"MS-103",1615697693226l,1615697693226l,"",
      Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(respSentMsgId,ByteString.copyFromUtf8("response-sent"),
      Some(PayloadContext("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response-sent","plain"))),

    MsgCreated(respMsgId,"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response",myPairwiseRelDIDPair.DID,"MS-103",1615697693226l,1615697693226l,"",
      Some(MsgThreadDetail(UUID.randomUUID().toString,"",0,Vector())),true),
    MsgPayloadStored(respMsgId,ByteString.copyFromUtf8("response"),
      Some(PayloadContext("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response","plain"))),

    MsgDeliveryStatusUpdated(respMsgId,"http://localhost:9001/agency/msg","MDS-102","",1615697701620l,0)
  )
}
