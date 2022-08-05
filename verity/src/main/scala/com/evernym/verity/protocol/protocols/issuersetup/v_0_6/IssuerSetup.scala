package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.constants.InitParamConstants.{MY_ISSUER_DID, SELF_ID}

import java.util.UUID
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.ProtocolInitialized
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.State.StateData
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{defineSelf, noHandleProtoMsg}
import com.evernym.verity.util2.Exceptions

import scala.util.{Failure, Success}

class IssuerSetup(implicit val ctx: ProtocolContextApi[IssuerSetup, Role, Msg, Any, State, String])
  extends Protocol[IssuerSetup, Role, Msg, Any, State, String](IssuerSetupDefinition)
  with ProtocolHelpers[IssuerSetup, Role, Msg, Any, State, String] {

  import IssuerSetup._

  override def applyEvent: ApplyEvent = {
    case (State.Uninitialized(), r: Roster[Role], e: ProtocolInitialized) =>
      val initParams = getInitParams(e)
      State.InitializedWithParams(initParams) -> defineSelf(r, initParams.paramValueRequired(SELF_ID), Role.Owner)
    case (State.InitializedWithParams(params), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(StateData(None, Option(State.Identity(did, verKey))))
    // These case are not possible from the code NOW but must be left for already
    // recorded events
    case (State.Initialized(), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(StateData(None, Option(State.Identity(did, verKey))))
    case (State.Uninitialized(), r: Roster[Role], RosterInitialized(selfId)) =>
      State.Initialized() -> defineSelf(r, selfId, Role.Owner)
    case (State.Initialized(), _, CreatePublicIdentifierInitiated(nonce)) =>
      State.Creating(State.StateData(Some(nonce), None))
    case (State.Creating(d), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(d.copy(identity=Option(State.Identity(did, verKey))))
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized(), _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (init: State.InitializedWithParams, _, Create()) =>
      val issuerDid = init.parameters.paramValue(MY_ISSUER_DID).map(ctx.vdr.toLegacyNonFqId)
      issuerDid match {
        case Some(issuerDid) if issuerDid.nonEmpty => ctx.wallet.verKey(issuerDid) {
          case Success(verKeyRes) =>
            ctx.logger.debug(s"Found existing did/key pair. DID: ${}, Key: ${}")
            ctx.signal(PublicIdentifier(issuerDid, verKeyRes.verKey))
            ctx.apply(CreatePublicIdentifierCompleted(issuerDid, verKeyRes.verKey))
          case _ => ctx.signal(ProblemReport(s"${unableToFindIssuerVerkey}: ${issuerDid}"))
        }
        case _ =>
          ctx.logger.debug("Creating DID/Key pair for Issuer Identifier/Keys")
          ctx.wallet.newDid() {
            case Success(keyCreated) =>
              val fqId = ctx.vdr.fqDID(keyCreated.did, force = false)
              ctx.apply(CreatePublicIdentifierCompleted(fqId, keyCreated.verKey))
              ctx.signal(PublicIdentifierCreated(PublicIdentifier(fqId, keyCreated.verKey)))
            case Failure(e) =>
              ctx.logger.warn("Wallet access failed to create DID/Verkey pair - " + e.getMessage)
              ctx.logger.warn(Exceptions.getStackTraceAsSingleLineString(e))
              ctx.signal(ProblemReport(didCreateErrorMsg))
          }
      }
    //******** Query *************
    case (State.Created(d), _, CurrentPublicIdentifier()) =>
      d.identity match {
        case Some(didDetails) =>
          val fqId = ctx.vdr.fqDID(didDetails.did, force = false)
          ctx.signal(PublicIdentifier(fqId, didDetails.verKey))
        case None => ctx.logger.warn(corruptedStateErrorMsg + " - Created state don't have did and/or verkey")
      }
    case (init: State.InitializedWithParams, _, CurrentPublicIdentifier()) =>
      val issuerDid = init.parameters.paramValue(MY_ISSUER_DID).map(ctx.vdr.toLegacyNonFqId)
      issuerDid match {
        case Some(issuerDid) if issuerDid.nonEmpty => ctx.wallet.verKey(issuerDid) {
          case Success(verKeyRes) =>
            ctx.logger.debug(s"Found existing did/key pair. DID: ${}, Key: ${}")
            ctx.signal(PublicIdentifier(issuerDid, verKeyRes.verKey))
            ctx.apply(CreatePublicIdentifierCompleted(issuerDid, verKeyRes.verKey))
          case _ => ctx.signal(ProblemReport(s"${unableToFindIssuerVerkey}: ${issuerDid}"))
        }
        case _ => ctx.signal(ProblemReport(identifierNotCreatedProblem))
      }
    case (_, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    // These case are not possible from the code NOW but must be left for already
    // recorded events
    case (State.Uninitialized(), _, InitMsg(id)) => ctx.apply(RosterInitialized(id))
    case (State.Initialized(), _, Create()) =>
      ctx.logger.debug("Creating DID/Key pair for Issuer Identifier/Keys")
      ctx.wallet.newDid() {
        case Success(keyCreated) =>
          val fqId = ctx.vdr.fqDID(keyCreated.did, force = false)
          ctx.apply(CreatePublicIdentifierCompleted(fqId, keyCreated.verKey))
          ctx.signal(PublicIdentifierCreated(PublicIdentifier(fqId, keyCreated.verKey)))
        case Failure(e) =>
          ctx.logger.warn("Wallet access failed to create DID/Verkey pair - " + e.getMessage)
          ctx.logger.warn(Exceptions.getStackTraceAsSingleLineString(e))
          ctx.signal(ProblemReport(didCreateErrorMsg))
      }
    case (State.Creating(_) | State.Created(_), _,  Create()) =>
      ctx.signal(ProblemReport(alreadyCreatingProblem))
    case (s: State, _, msg: Control) =>
      ctx.signal(ProblemReport(s"Unexpected '$msg' message in current state '$s"))
  }

  private var hasInitialized: Boolean = false

  private def getInitParams(params: ProtocolInitialized): Parameters = {
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )
  }

}

object IssuerSetup {
  val didCreateErrorMsg = "Unable to Issuer Public Identity"
  val corruptedStateErrorMsg = "Issuer Identifier is in a corrupted state"
  val alreadyCreatingProblem  = "Issuer Identifier is already created or in the process of creation"
  val identifierNotCreatedProblem = "Issuer Identifier has not been created yet"
  val unableToFindIssuerVerkey = "Agent wallet has no verkey for public identifier"

  type Nonce = String
  def createNonce(): Nonce = {
    UUID.randomUUID().toString
  }
}