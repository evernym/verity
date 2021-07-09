package com.evernym.verity.protocol.protocols.questionAnswer.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.metrics.MetricsWriterExtensionImpl
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AccessSign, AccessVerify}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOne
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Role.{Questioner, Responder}

object QuestionAnswerDefinition extends ProtocolDefinition[QuestionAnswerProtocol, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = QuestionAnswerMsgFamily

  override def segmentStoreStrategy: Option[SegmentStoreStrategy] = Some(OneToOne)

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p.paramValueRequired(SELF_ID), p.paramValueRequired(OTHER_ID))

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID, DATA_RETENTION_POLICY)

  override val roles: Set[Role] = Set(Questioner, Responder)

  override val requiredAccess: Set[AccessRight] = Set(AccessSign, AccessVerify)

  override def create(context: ProtocolContextApi[QuestionAnswerProtocol, Role, Msg, Event, State, String],
                      mw: MetricsWriterExtensionImpl): QuestionAnswerProtocol = {
    new QuestionAnswerProtocol(context)
  }

  def initialState: State = State.Uninitialized()
}

sealed trait Role

object Role {

  case object Questioner extends Role {
    def roleNum = 0
  }

  case object Responder extends Role {
    def roleNum = 1
  }

  def numToRole: Int ?=> Role = {
    case 0 => Questioner
    case 1 => Responder
  }

  def otherRole: Role ?=> Role = {
    case Questioner => Responder
    case Responder => Questioner
  }

}

object ProblemReportCodes {
  val unexpectedMessage = "unexpected-message"
  val expiredDataRetention = "expired-data-retention"
  val segmentStorageFailure = "segment-storage-failure"
}