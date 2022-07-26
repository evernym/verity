package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID, SELF_ID}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.asyncapi.ledger.{IndyLedgerUtil, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.SignedMsgResult
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{defineSelf, noHandleProtoMsg}
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vdr.{FqDID, PreparedTxn}
import com.evernym.verity.vdr.VDRUtil.extractUnqualifiedDidStr
import com.fasterxml.jackson.databind.exc.PropertyBindingException

import scala.util.{Failure, Success, Try}

class IssuerSetup(implicit val ctx: ProtocolContextApi[IssuerSetup, Role, Msg, Any, State, String])
  extends Protocol[IssuerSetup, Role, Msg, Any, State, String](IssuerSetupDefinition)
  with ProtocolHelpers[IssuerSetup, Role, Msg, Any, State, String] {

  import IssuerSetup._

  override def applyEvent: ApplyEvent = {
    case (State.Uninitialized(), r: Roster[Role], e: ProtocolInitialized) =>
      val initParams = getInitParams(e)
      State.Initialized(initParams) -> defineSelf(r, initParams.paramValueRequired(SELF_ID), Role.Owner)

    case (State.Initialized(params), _, CreatePublicIdentifierCompleted(did, verKey)) =>
      ctx.logger.debug(s"CreatePublicIdentifierCompleted: $did - $verKey")
      State.Created(State.Identity(did, verKey))

    case (s: State.Created, _, e: NeedsManualEndorsement) => State.Done(s.identity)
    case (s: State.Created, _, e: AskedForEndorsement)    => State.WaitingOnEndorser(e.ledgerPrefix, s.identity)
    case (s: State.WaitingOnEndorser, _, e: DIDWritten)  => State.Done(s.identity)
    case (s @ (_: State.Created | _:State.WaitingOnEndorser), _, e: IssuerSetupFailed)    => State.Error(e.error)
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized(), _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (init: State.Initialized, _, Create(ledgerPrefix, endorser)) => writeDIDToLedger(init, ledgerPrefix, endorser)
    case (s: State.WaitingOnEndorser, _, m: EndorsementResult) => handleEndorsementResult(m, s)

    //******** Query *************
    case (init: State.Initialized, _, CurrentPublicIdentifier()) => checkPublicIdentifier(init)
    case (State.Created(d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (State.WaitingOnEndorser(l,d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (State.Done(d), _, CurrentPublicIdentifier()) => ctx.signal(PublicIdentifier(d.did, d.verKey))
    case (_, _, CurrentPublicIdentifier()) => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    case (s: State, _, msg: Control) =>
      throw new Exception(s"Unrecognized state, message combination: ${s}, $msg")
  }

  private def checkPublicIdentifier(init: State.Initialized): Unit = {
    init.parameters.paramValue(MY_ISSUER_DID) match {
      case Some(issuerDid) if issuerDid.nonEmpty => ctx.signal(ProblemReport(s"$identifierAlreadyCreatedErrorMsg: $issuerDid"))
      case _ => ctx.signal(ProblemReport(identifierNotCreatedProblem))
    }
  }

  private def writeDIDToLedger(init: State.Initialized, ledgerPrefix: String, endorserDID: Option[String]): Unit = {
    ctx.logger.debug(s"Creating DID/Key pair for Issuer Identifier/Keys for ledger prefix: $ledgerPrefix with endorser: ${endorserDID.getOrElse("default")}")
    init.parameters.paramValue(MY_ISSUER_DID) match {
      case Some(issuerDid) if issuerDid.nonEmpty => problemReport(new Exception(s"${identifierAlreadyCreatedErrorMsg}: ${issuerDid}"))
      case _ => ctx.wallet.newDid(Some(ledgerPrefix)) {
        case Success(keyCreated) =>
          ctx.apply(CreatePublicIdentifierCompleted(keyCreated.did, keyCreated.verKey))
          val fqSubmitterDID = ctx.ledger.fqDID(keyCreated.did, false)
          ctx.endorser.withCurrentEndorser(ledgerPrefix) {
            case Success(Some(endorser)) if endorserDID.isEmpty || endorserDID.contains(endorser.did) =>
              ctx.logger.info(s"registered endorser to be used for issuer endorsement (prefix: $ledgerPrefix): " + endorser)
              //no explicit endorser given/configured or the given/configured endorser matches an active endorser for the ledger prefix
              prepareTxnForEndorsement(fqSubmitterDID, prepareDidJson(keyCreated.did, keyCreated.verKey), endorser.did) {
                case Success(txn) =>
                  ctx.endorser.endorseTxn(txn, ledgerPrefix) {
                    case Success(_) => ctx.apply(AskedForEndorsement(keyCreated.did, ledgerPrefix, ledgerPrefix))
                    case Failure(e) => problemReport(e)
                  }
                case Failure(e) => problemReport(e)
              }
            case other => {
              ctx.logger.info(s"no active/matched endorser found to be used for issuer endorsement (prefix: $ledgerPrefix): " + other)
              //any failure while getting active endorser, or no active endorser or active endorser is NOT the same as given/configured endorserDID
              handleNeedsEndorsement(fqSubmitterDID, fqSubmitterDID, keyCreated.verKey, endorserDID.getOrElse(init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse("")), ledgerPrefix)
            }
          }
        case Failure(e) => problemReport(new Exception(s"$didCreateErrorMsg, reason: ${e.toString}"))
      }
    }
  }

  private def handleNeedsEndorsement(submitterDID: DidStr,
                                     targetDID: DidStr,
                                     verkey: VerKeyStr,
                                     endorserDID: String,
                                     ledgerPrefix: String): Unit = {
    if (endorserDID.nonEmpty) {
      prepareTxnForEndorsement(submitterDID, prepareDidJson(targetDID, verkey), endorserDID) {
        case Success(ledgerRequest) =>
          ctx.signal(PublicIdentifierCreated(PublicIdentifier(targetDID, verkey), NeedsEndorsement(ledgerRequest)))
          ctx.apply(NeedsManualEndorsement(targetDID, verkey, ledgerPrefix))
        case Failure(e) =>
          problemReport(e)
      }
    } else {
      problemReport(new Exception("No default endorser defined"))
    }
  }

  private def prepareDidJson(targetDid: String, verkey: String): String = {
    // This assumes an indy ledger, as the txnSpecificParams for VDRTools prepare DID Cheqd API have not been finalized
    s"{\"dest\": \"$targetDid\", \"verkey\": \"$verkey\"}"
  }

  private def prepareTxnForEndorsement(fqSubmitterDID: FqDID,
                                       didJson: String,
                                       endorserDid: DidStr)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    if (endorserDid.nonEmpty) {
      ctx.ledger
        .prepareDidTxn(
          didJson,
          fqSubmitterDID,
          Option(endorserDid)) {
          case Success(pt: PreparedTxn) =>
            ctx.wallet.sign(pt.bytesToSign, pt.signatureType, Option(fqSubmitterDID)) {
              case Success(smr: SignedMsgResult) =>
                //NOTE: what about other endorsementSpecType (cheqd etc)
                if (pt.isEndorsementSpecTypeIndy) {
                  val txn = IndyLedgerUtil.buildIndyRequest(pt.txnBytes, Map(extractUnqualifiedDidStr(fqSubmitterDID) -> Base58Util.encode(pt.txnBytes)))
                  handleResult(Success(txn))
                } else {
                  handleResult(Failure(new RuntimeException("endorsement spec type not supported: " + pt.endorsementSpec)))
                }
              case Failure(ex) =>
                handleResult(Failure(ex))
            }
          case Failure(ex) =>
            handleResult(Failure(ex))
        }
    } else {
      handleResult(Failure(new Exception("No default endorser defined")))
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

  private def getInitParams(params: ProtocolInitialized): Parameters = {
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )
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
  val identifierAlreadyCreatedErrorMsg = "Public identifier has already been created"
}