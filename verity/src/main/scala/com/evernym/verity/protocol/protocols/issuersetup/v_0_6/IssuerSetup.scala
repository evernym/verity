package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import java.util.UUID
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.State.StateData
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{defineSelf, noHandleProtoMsg}
import com.evernym.verity.util2.Exceptions

import scala.util.{Failure, Success}

class IssuerSetup(implicit val ctx: ProtocolContextApi[IssuerSetup, Role, Msg, Event, State, String])
  extends Protocol[IssuerSetup, Role, Msg, Event, State, String](IssuerSetupDefinition)
  with ProtocolHelpers[IssuerSetup, Role, Msg, Event, State, String] {

  import IssuerSetup._

  override def applyEvent: ApplyEvent = {
    case (State.Uninitialized(), r: Roster[Role], RosterInitialized(selfId)) =>
      State.Initialized() -> defineSelf(r, selfId, Role.Owner)

    case (State.Initialized(), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(StateData(None, Option(State.Identity(did, verKey))))

    // These case are not possible from the code NOW but must be left for already
    // recorded events
    case (State.Initialized(), _, CreatePublicIdentifierInitiated(nonce)) =>
      State.Creating(State.StateData(Some(nonce), None))
    case (State.Creating(d), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(d.copy(identity=Option(State.Identity(did, verKey))))
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized(), _, InitMsg(id)) => ctx.apply(RosterInitialized(id))
    case (State.Initialized(), _, Create()) =>
      ctx.logger.debug("Creating DID/Key pair for Issuer Identifier/Keys")
      ctx.wallet.newDid() {
        case Success(keyCreated) =>
          ctx.apply(CreatePublicIdentifierCompleted(keyCreated.did, keyCreated.verKey))
          ctx.signal(PublicIdentifierCreated(PublicIdentifier(keyCreated.did, keyCreated.verKey)))
        case Failure(e) =>
          ctx.logger.warn("Wallet access failed to create DID/Verkey pair - " + e.getMessage)
          ctx.logger.warn(Exceptions.getStackTraceAsSingleLineString(e))
          ctx.signal(ProblemReport(didCreateErrorMsg))
      }
    case (State.Creating(_) | State.Created(_), _,  Create()) =>
      ctx.signal(ProblemReport(alreadyCreatingProblem))
    //******** Query *************
    case (State.Created(d), _, CurrentPublicIdentifier()) =>
      d.identity match {
        case Some(didDetails) => ctx.signal(PublicIdentifier(didDetails.did, didDetails.verKey))
        case None => ctx.logger.warn(corruptedStateErrorMsg + " - Created state don't have did and/or verkey")
      }
    case (_, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    case _ =>
      throw new Exception("TEST")
  }

  private var hasInitialized: Boolean = false


}

object IssuerSetup {
  val selfIdErrorMsg = "SELF ID was not provided with init parameters"
  val didCreateErrorMsg = "Unable to Issuer Public Identity"
  val corruptedStateErrorMsg = "Issuer Identifier is in a corrupted state"
  val alreadyCreatingProblem  = "Issuer Identifier is already created or in the process of creation"
  val identifierNotCreatedProblem = "Issuer Identifier has not been created yet"


  type Nonce = String
  def createNonce(): Nonce = {
    UUID.randomUUID().toString
  }
}