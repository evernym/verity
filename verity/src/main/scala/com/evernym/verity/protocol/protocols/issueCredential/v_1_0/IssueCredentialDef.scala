package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOne
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}

object IssueCredentialProtoDef extends ProtocolDefinition[IssueCredential, Role, ProtoMsg, Event, State, String] {
  override val msgFamily: MsgFamily = IssueCredMsgFamily

  override def segmentStoreStrategy: Option[SegmentStoreStrategy] = Some(OneToOne)

  override def create(context: ProtocolContextApi[IssueCredential, Role, ProtoMsg, Event, State, String]): Protocol[IssueCredential, Role, ProtoMsg, Event, State, String] = {
    new IssueCredential()(context)
  }

  override def initialState: State = State.Uninitialized()

  override val roles: Set[Role] = Set(Issuer(), Holder())

  override val initParamNames: Set[ParameterName] = Set(
    SELF_ID,
    OTHER_ID,
    MY_PAIRWISE_DID,
    THEIR_PAIRWISE_DID,

    // Needed for OOB invite
    NAME,
    LOGO_URL,
    AGENCY_DID_VER_KEY,
    MY_PUBLIC_DID,
    DATA_RETENTION_POLICY
  )

  override def createInitMsg(params: Parameters): Control = Ctl.Init(params)
}

sealed trait Role

object Role {
  case class Issuer() extends Role
  case class Holder() extends Role
}

object ProblemReportCodes {
  val credentialOfferCreation = "credential-offer-creation"
  val credentialRequestCreation = "credential-request-creation"
  val ledgerAssetsUnavailable = "ledger-assets-unavailable"
  val unexpectedMessage = "unexpected-message"
  val shorteningFailed = "shortening-failed"
  val expiredDataRetention = "expired-data-retention"
  val segmentStorageFailure = "segment-storage-failure"
  val invalidMessageStateError = "invalid-message-state"
}
