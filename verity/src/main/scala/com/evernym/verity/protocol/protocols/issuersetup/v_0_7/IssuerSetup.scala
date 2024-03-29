package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.constants.InitParamConstants.{MY_ISSUER_DID, MY_ISSUER_VERKEY, SELF_ID}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.asyncapi.vdr.{IndyLedgerUtil, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.SignedMsgResult
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{defineSelf, noHandleProtoMsg}
import com.evernym.verity.vdr.{FqDID, PreparedTxn}
import com.evernym.verity.vdr.VDRUtil.extractUnqualifiedDidStr

import scala.util.{Failure, Success, Try}

class IssuerSetup(implicit val ctx: ProtocolContextApi[IssuerSetup, Role, Msg, Event, State, String])
  extends Protocol[IssuerSetup, Role, Msg, Event, State, String](IssuerSetupDefinition)
  with ProtocolHelpers[IssuerSetup, Role, Msg, Event, State, String] {

  import IssuerSetup._

  override def applyEvent: ApplyEvent = {
    case (State.Uninitialized(), r: Roster[Role], e: Initialized) =>
      val initParams = getInitParams(e)
      val issuerDid = ctx.vdr.fqDID(initParams.paramValueRequired(MY_ISSUER_DID), force = false)
      val issuerVerkey = initParams.paramValueRequired(MY_ISSUER_VERKEY)
      if (issuerDid.nonEmpty && issuerVerkey.nonEmpty) {
        State.Created(State.Identity(issuerDid, issuerVerkey)) -> defineSelf(r, initParams.paramValueRequired(SELF_ID), Role.Owner)
      } else {
        State.Initialized(initParams) -> defineSelf(r, initParams.paramValueRequired(SELF_ID), Role.Owner)
      }

    case (State.Initialized(params), _, CreatePublicIdentifierCompleted(did, verKey)) => State.Created(State.Identity(did, verKey))
    case (s: State.Created, _, e: NeedsManualEndorsement) => State.Created(s.identity)
    case (s: State.Created, _, e: AskedForEndorsement)    => State.WaitingOnEndorser(e.ledgerPrefix, s.identity)
    case (s: State.WaitingOnEndorser, _, e: DIDWritten)  => State.Created(s.identity)
    case (s: State.WaitingOnEndorser, _, e: IssuerSetupFailed) => State.Created(s.identity)
    case (s: State, _, e: IssuerSetupFailed) => s
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized(), _, c: Initialize) => ctx.apply(Initialized(c.parametersStored))
    case (init: State.Initialized, _, cr @ Create(ledgerPrefix, endorser)) =>
      ctx.signal(GetIssuerIdentifier(cr))
    case (init: State.Initialized, _, CurrentIssuerIdentifierResult(cr, Some(pid))) =>
      ctx.apply(CreatePublicIdentifierCompleted(pid.did, pid.verKey))
      ctx.signal(PublicIdentifier(pid.did, pid.verKey))
    case (init: State.Initialized, _, CurrentIssuerIdentifierResult(cr, None)) =>
      handleCreateIdentifier(init, cr.ledgerPrefix, cr.endorser)
    case (s: State.WaitingOnEndorser, _, m: EndorsementResult) => handleEndorsementResult(m, s)

    //******** Query *************
    case (init: State.Initialized, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    case (State.Created(d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (State.Created(d), _, c: Create) => ctx.signal(ProblemReport(identifierAlreadyCreatedErrorMsg))
    case (State.WaitingOnEndorser(l,d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (_, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    case (s: State, _, msg: Control) =>
      ctx.signal(ProblemReport(s"Unexpected '$msg' message in current state '$s"))
  }

  private def handleCreateIdentifier(init: State.Initialized, ledgerPrefix: String, endorserDID: Option[String]): Unit = {
      ctx.logger.debug(s"Creating DID/Key pair for Issuer Identifier/Keys for ledger prefix: $ledgerPrefix with endorser: ${endorserDID.getOrElse("default")}")
      ctx.wallet.newDid(Some(ledgerPrefix)) {
      case Success(keyCreated) =>
        ctx.apply(CreatePublicIdentifierCompleted(keyCreated.did, keyCreated.verKey))
        val fqSubmitterDID = ctx.vdr.fqDID(keyCreated.did, force = false)
        ctx.endorser.withCurrentEndorser(ledgerPrefix) {
          case Success(Some(endorser)) if endorserDID.isEmpty || endorserDID.contains(endorser.did) =>
            ctx.logger.info(s"registered endorser to be used for issuer endorsement (prefix: $ledgerPrefix): " + endorser)
            //no explicit endorser given/configured or the given/configured endorser matches an active endorser for the ledger prefix
            prepareTxnForEndorsement(fqSubmitterDID, prepareDidJson(keyCreated.did, keyCreated.verKey), endorser.did) {
              case Success(txn) =>
                ctx.endorser.endorseTxn(txn, ledgerPrefix) {
                  case Success(_) => ctx.apply(AskedForEndorsement(ledgerPrefix))
                  case Failure(e) => problemReport(s"Unable to endorse transaction, encountered error: ${e.getMessage}")
                }
              case Failure(e) => problemReport(s"Unable to prepare transaction for automatic endorsement, encountered error: ${e.getMessage}")
            }
          case other => {
            ctx.logger.info(s"no active/matched endorser found to be used for issuer endorsement (prefix: $ledgerPrefix): " + other)
            //any failure while getting active endorser, or no active endorser or active endorser is NOT the same as given/configured endorserDID
            handleNeedsEndorsement(fqSubmitterDID, fqSubmitterDID, keyCreated.verKey, endorserDID.getOrElse(""))
          }
        }
      case Failure(e) => problemReport((s"$didCreateErrorMsg, reason: ${e.toString}"))
    }
  }

  private def handleNeedsEndorsement(submitterDID: DidStr,
                                     targetDID: DidStr,
                                     verkey: VerKeyStr,
                                     endorserDID: String): Unit = {
    prepareTxnForEndorsement(submitterDID, prepareDidJson(targetDID, verkey), endorserDID) {
      case Success(ledgerRequest) =>
        ctx.signal(PublicIdentifierCreated(PublicIdentifier(targetDID, verkey), NeedsEndorsement(ledgerRequest)))
        ctx.apply(NeedsManualEndorsement())
      case Failure(e) =>
        problemReport(s"Unable to prepare transaction for manual endorsement, encountered error: ${e.getMessage}")
        ctx.signal(PublicIdentifierCreated(PublicIdentifier(targetDID, verkey), NeedsEndorsement("")))
    }
  }

  private def prepareDidJson(targetDid: String, verkey: String): String = {
    // This assumes an indy ledger, as the txnSpecificParams for VDRTools prepareDID Cheqd API have not been finalized
    s"""{"dest": "$targetDid", "verkey": "$verkey"}"""
  }

  private def prepareTxnForEndorsement(fqSubmitterDID: FqDID,
                                       didJson: String,
                                       endorserDid: DidStr)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    if (endorserDid.nonEmpty) {
      ctx.vdr
        .prepareDidTxn(
          didJson,
          fqSubmitterDID,
          Option(endorserDid)) {
          case Success(pt: PreparedTxn) =>
            ctx.wallet.sign(pt.bytesToSign, pt.signatureType, Option(fqSubmitterDID)) {
              case Success(smr: SignedMsgResult) =>
                //NOTE: what about other endorsementSpecType (cheqd etc)
                if (pt.isEndorsementSpecTypeIndy) {
                  val txn = IndyLedgerUtil.buildIndyRequest(pt.txnBytes, Map(extractUnqualifiedDidStr(fqSubmitterDID) -> smr.signatureResult.toBase58))
                  handleResult(Success(txn))
                } else {
                  handleResult(Failure(new RuntimeException("endorsement spec type not supported: " + pt.endorsementSpec)))
                }
              case Failure(ex) =>
                handleResult(Failure(new RuntimeException(s"Failed to sign prepared transaction: ${ex.toString}")))
            }
          case Failure(ex) =>
            handleResult(Failure(new RuntimeException(s"Failed to prepare DID transaction: ${ex.toString}")))
        }
    } else {
      handleResult(Failure(new Exception("No default endorser defined")))
    }
  }

  private def handleEndorsementResult(m: EndorsementResult, woe: State.WaitingOnEndorser): Unit = {
    if (m.code == ENDORSEMENT_RESULT_SUCCESS_CODE) {
      ctx.apply(DIDWritten())
      ctx.signal(PublicIdentifierCreated(PublicIdentifier(woe.identity.did, woe.identity.verKey), WrittenToLedger(woe.ledgerPrefix)))
    } else {
      ctx.apply(IssuerSetupFailed(s"error during endorsement => code: ${m.code}, description: ${m.description}"))
    }
  }

  private def getInitParams(params: Initialized): Parameters = {
    Parameters(params
      .params
      .map(p => Parameter(p.name, p.value))
      .toSet
    )
  }

  private def problemReport(errorMsg: String): Unit = {
    ctx.logger.error(errorMsg)
    ctx.signal(ProblemReport(errorMsg))
  }
}

object IssuerSetup {
  val didCreateErrorMsg = "Unable to create Issuer Public Identity"
  val identifierNotCreatedProblem = "Issuer Identifier has not been created yet"
  val identifierAlreadyCreatedErrorMsg = "Public identifier has already been created"
}