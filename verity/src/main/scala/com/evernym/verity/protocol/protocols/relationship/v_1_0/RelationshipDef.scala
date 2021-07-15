package com.evernym.verity.protocol.protocols.relationship.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, UrlShorteningAccess}
import com.evernym.verity.protocol.engine.{MsgFamily, ParameterName, Parameters, Protocol, ProtocolContextApi, ProtocolDefinition, Scope}
import com.evernym.verity.protocol.engine.Scope.RelProvisioning

import scala.concurrent.ExecutionContext

object RelationshipDef extends ProtocolDefinition[Relationship, Role, Msg, RelationshipEvent, State, String] {
  override val msgFamily: MsgFamily = RelationshipMsgFamily

  override def create(
                       context: ProtocolContextApi[Relationship, Role, Msg, RelationshipEvent, State, String],
                       executionContext: ExecutionContext
                     ): Protocol[Relationship, Role, Msg, RelationshipEvent, State, String] = {
    new Relationship(context)
  }

  override def initialState: State = State.Uninitialized()

  override val roles: Set[Role] = Set(Role.Provisioner, Role.Requester)

  override val initParamNames: Set[ParameterName] = Set(
    SELF_ID, OTHER_ID, AGENCY_DID_VER_KEY, NAME, LOGO_URL, MY_PUBLIC_DID, DATA_RETENTION_POLICY
  )

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p)

  override def scope: Scope.ProtocolScope = RelProvisioning

  override val requiredAccess: Set[AccessRight] = Set(UrlShorteningAccess)

}

object ProblemReportCodes {
  val shorteningFailed = "shortening-failed"
  val smsSendingFailed = "sms-sending-failed"
  val noPhoneNumberDefined = "no-phone-number-defined"
  val invalidPhoneNumberFormat = "invalid-phone-number-format"
  val unexpectedMessage = "unexpected-message"
}
