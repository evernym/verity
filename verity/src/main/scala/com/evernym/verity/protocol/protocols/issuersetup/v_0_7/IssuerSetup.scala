package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{defineSelf, noHandleProtoMsg}

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
      State.Created(State.Identity(did, verKey))

    case (s: State.Created, _, e: NeedsManualEndorsement) => State.Done(s.identity)
    case (s: State.Created, _, e: AskedForEndorsement)    => State.WaitingOnEndorser(e.ledgerPrefix, s.identity)
    case (s: State.WaitingOnEndorser, _, e: DIDWritten)  => State.Done(s.identity)
    case (s @ (_: State.Created | _:State.WaitingOnEndorser), _, e: WriteFailed)    => State.Error(e.error)
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized(), _, InitMsg(id)) => ctx.apply(RosterInitialized(id))
    case (State.Initialized(), _, Create(ledgerPrefix, endorserDID)) => writeDIDToLedger(ledgerPrefix, endorserDID)
    case (s: State.WaitingOnEndorser, _, m: EndorsementResult) => handleEndorsementResult(m, s)

    //******** Query *************
    case (State.Created(d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (State.WaitingOnEndorser(l,d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (State.Done(d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (_, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    case (s: State, _, msg: Control) =>
      throw new Exception(s"Unrecognized state, message combination: ${s}, $msg")
  }

  private def writeDIDToLedger(ledgerPrefix: String, endorserDID: Option[String]): Unit = {
    ctx.logger.debug(s"Creating DID/Key pair for Issuer Identifier/Keys for ledger prefix: $ledgerPrefix with endorser: ${endorserDID.getOrElse("default")}")
    ctx.wallet.newDid() {
      case Success(keyCreated) =>
        ctx.apply(CreatePublicIdentifierCompleted(keyCreated.did, keyCreated.verKey))
        val ledgerDefaultLegacyPrefix = ctx.ledger.getIndyDefaultLegacyPrefix()
        ctx.endorser.withCurrentEndorser(ledgerPrefix) {
          case Success(Some(endorser)) if endorserDID.isEmpty || endorserDID.getOrElse("") == endorser.did =>
            ctx.logger.info(s"registered endorser to be used for schema endorsement (prefix: $ledgerDefaultLegacyPrefix): " + endorser)
            //no explicit endorser given/configured or the given/configured endorser matches an active endorser for the ledger prefix
            ctx.ledger.prepareDIDTxnForEndorsement(keyCreated.did, keyCreated.did, keyCreated.verKey, endorser.did) {
              case Success(txn) =>
                ctx.endorser.endorseTxn(txn.req, ledgerPrefix) {
                  case Success(value) => ctx.apply(AskedForEndorsement(keyCreated.did, ledgerPrefix, ledgerPrefix))
                  case Failure(e) => problemReport(e)
                }
              case Failure(e) => problemReport(e)
            }
          case other => {
            ctx.logger.info(s"no active/matched endorser found to be used for issuer endorsement (prefix: $ledgerDefaultLegacyPrefix): " + other)
            //any failure while getting active endorser, or no active endorser or active endorser is NOT the same as given/configured endorserDID
            handleNeedsEndorsement(keyCreated.did, keyCreated.did, keyCreated.verKey, endorserDID.getOrElse(""), ledgerPrefix)
          }
        }
      case Failure(e) => problemReport(new Exception(s"$didCreateErrorMsg, reason: ${e.toString}"))
    }
  }

  private def handleNeedsEndorsement(submitterDID: DidStr,
                                     targetDID: DidStr,
                                     verkey: VerKeyStr,
                                     endorserDID: String,
                                     ledgerPrefix: String): Unit = {
    if (endorserDID.nonEmpty) {
      ctx.ledger.prepareDIDTxnForEndorsement(submitterDID, targetDID, verkey, endorserDID) {
        case Success(ledgerRequest) =>
          ctx.signal(PublicIdentifierCreated(PublicIdentifier(targetDID, verkey), NeedsEndorsement(ledgerRequest.req)))
          ctx.apply(NeedsManualEndorsement(targetDID, verkey, ledgerPrefix))
        case Failure(e) =>
          problemReport(e)
      }
    } else {
      problemReport(new Exception("No default endorser defined"))
    }
  }

  private def handleEndorsementResult(m: EndorsementResult, woe: State.WaitingOnEndorser): Unit = {
    if (m.code == ENDORSEMENT_RESULT_SUCCESS_CODE) {
      ctx.apply(DIDWritten(woe.ledgerPrefix))
      ctx.signal(PublicIdentifierCreated(PublicIdentifier(woe.identity.did, woe.identity.verKey), WrittenToLedger(woe.ledgerPrefix)))
    } else {
      problemReport(new RuntimeException(s"error during endorsement => code: ${m.code}, description: ${m.description}"))
    }
  }

  private def problemReport(e: Throwable): Unit = {
    ctx.logger.error(e.toString)
    ctx.apply(IssuerSetupFailed(Option(e.getMessage).getOrElse("unknown error")))
    ctx.signal(ProblemReport(e.toString))
  }
}

object IssuerSetup {
  val didCreateErrorMsg = "Unable to create Issuer Public Identity"
  val identifierNotCreatedProblem = "Issuer Identifier has not been created yet"
}