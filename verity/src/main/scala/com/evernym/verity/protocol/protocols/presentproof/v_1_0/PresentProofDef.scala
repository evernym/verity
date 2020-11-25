package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.constants.Constants.UNKNOWN_OTHER_ID
import com.evernym.verity.constants.InitParamConstants.{NAME, _}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>

object PresentProofDef extends ProtocolDefinition[PresentProof, Role, ProtoMsg, Event, State, String] {

  override val roles: Set[Role] = Set(Role.Prover, Role.Verifier)
  override val msgFamily: MsgFamily = PresentProofMsgFamily

  override def create(context: ProtocolContextApi[PresentProof, Role, ProtoMsg, Event, State, String]):
  Protocol[PresentProof, Role, ProtoMsg, Event, State, String] = {
    new PresentProof()(context)
  }

  override val initParamNames: Set[ParameterName] = Set(
    SELF_ID,
    OTHER_ID,

    // Needed for OOB invite
    NAME,
    LOGO_URL,
    AGENCY_DID_VER_KEY,
    MY_PUBLIC_DID
  )

  override def createInitMsg(p: Parameters): Control = Ctl.Init(
    p.paramValueRequired(SELF_ID),
    p.paramValue(OTHER_ID).filterNot(_ == UNKNOWN_OTHER_ID),
    p.paramValue(NAME),
    p.paramValue(LOGO_URL),
    p.paramValue(AGENCY_DID_VER_KEY),
    p.paramValue(MY_PUBLIC_DID),
  )


  override def initialState: State = States.Uninitialized()

  override val requiredAccess: Set[AccessRight] = Set(AnonCreds, LedgerReadAccess, AccessVerKey)
}

object AttIds {
  val request0: String = "libindy-request-presentation-0"
  val presentation0: String = "libindy-presentation-0"
}

sealed trait Role {
  def roleNum: Int
  def toEvent: MyRole = MyRole(roleNum)
}

object VerificationResults {
  val ProofValidated = "ProofValidated"
  val ProofInvalid = "ProofInvalid"
  val ProofUndefined = "ProofUndefined"
}

object ProblemReportCodes {
  // These are made up since the RFC does not specify them
  val rejectionNotAllowed = "rejection-not-allowed"
  val unimplemented = "unimplemented"
  val invalidPresentation = "invalid-presentation"
  val invalidRequest = "invalid-request"
  val invalidMessageStateError = "invalid-message-state"
  val rejection = "rejection"

  // controller problem report codes (not specified by RFC)
  val invalidRequestedPresentation = "invalid-requested-presentation"
  val presentationCreationFailure = "presentation-creation-failure"
  val ledgerAssetsUnavailable = "ledger-assets-unavailable"
  val unexpectedMessage = "unexpected-message"
  val shorteningFailed = "shortening-failed"
}

object Role {

  case object Verifier extends Role {
    def roleNum = 0
  }

  case object Prover extends Role {
    def roleNum = 1
  }

  def numToRole: Int ?=> Role = {
    case 0 => Verifier
    case 1 => Prover
  }

  def otherRole: Role ?=> Role = {
    case Verifier => Prover
    case Prover => Verifier
  }

  def otherRole(num: Int): Role = {
    otherRole(numToRole(num))
  }
}

