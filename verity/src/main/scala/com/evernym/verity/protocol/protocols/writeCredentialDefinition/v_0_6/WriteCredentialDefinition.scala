package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.asyncapi.ledger.{IndyLedgerUtil, LedgerRejectException, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{CredDefCreatedResult, SignedMsgResult}
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.Role.Writer
import com.evernym.verity.vdr.VDRUtil.extractUnqualifiedDidStr
import com.evernym.verity.vdr.{CredDefId, FqCredDefId, FqDID, PreparedTxn, SubmittedTxn}

import scala.util.{Failure, Success, Try}


class WriteCredDef(val ctx: ProtocolContextApi[WriteCredDef, Role, Msg, Any, CredDefState, String])
  extends Protocol[WriteCredDef, Role, Msg, Any, CredDefState, String](CredDefDefinition) {

  override def handleProtoMsg: (CredDefState, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  def mainHandleControl: (CredDefState, Option[Role], Control) ?=> Any = {
    case (_, _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (s: State.Initialized, _, m: Write) => writeCredDef(m, s)
    case (s: State.WaitingOnEndorser, _, m: EndorsementResult) => handleEndorsementResult(m, s)
    case _ => ctx.signal(ProblemReport("Unexpected message in current state"))
  }

  override def applyEvent: ApplyEvent = {
    case (_, _, e: ProtocolInitialized) =>
      (State.Initialized(getInitParams(e)), initialize(e.parameters))
    case (_: State.Initialized, _, e: RequestReceived) =>
      (
        State.Processing(e.name, e.schemaId),
        ctx.getRoster.withAssignment(Writer() -> ctx.getRoster.selfIndex_!)
      )
    case (_: State.Processing, _, e: AskedForEndorsement) => State.WaitingOnEndorser(e.credDefId, e.credDefJson)
    case (s @ (_: State.Processing | _: State.WaitingOnEndorser), _, e: CredDefWritten) => State.Done(e.credDefId)
    case (s @ (_: State.Processing | _: State.WaitingOnEndorser), _, e: WriteFailed) => State.Error(e.error)
  }

  def writeCredDef(m: Write, init: State.Initialized): Unit = {
    ctx.apply(RequestReceived(m.name, m.schemaId))

    try {
      val tag = m.tag.getOrElse("latest")
      val revocationDetails = m.revocationDetails.map(_.toString).getOrElse("{}")
      val nonFqSubmitterId = _submitterDID(init)
      val fqSubmitterId = ctx.ledger.fqDID(nonFqSubmitterId)
      ctx.ledger.resolveSchema(ctx.ledger.fqSchemaId(m.schemaId, None)) {
        case Success (schema) =>
          ctx.wallet.createCredDef(
            nonFqSubmitterId,
            schema.json,
            tag,
            sigType=None,
            revocationDetails=Some(revocationDetails)
          ) {
            case Success(credDefCreated: CredDefCreatedResult) =>
              val credDefId = credDefCreated.credDefId
              writeCredDefToLedger(fqSubmitterId, credDefId, credDefCreated.credDefJson) {
                case Success(SubmittedTxn(_)) =>
                  ctx.apply(CredDefWritten(credDefId))
                  ctx.signal(StatusReport(credDefId))
                case Failure(e: LedgerRejectException) if missingVkOrEndorserErr(fqSubmitterId, e) =>
                  ctx.logger.info(e.toString)
                  val endorserDID = m.endorserDID.getOrElse(init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse(""))
                  //NOTE: below code is assuming verity is only supporting indy ledgers,
                  // it should be changed when verity starts supporting different types of ledgers
                  val ledgerPrefix = ctx.ledger.vdrUnqualifiedLedgerPrefix()
                  ctx.endorser.withCurrentEndorser(ledgerPrefix) {
                    case Success(Some(endorser)) if endorserDID.isEmpty || endorserDID.contains(endorser.did) =>
                      ctx.logger.info(s"registered endorser to be used for creddef endorsement (ledger prefix: $ledgerPrefix): " + endorser)
                      //no explicit endorser given/configured or the given/configured endorser is matching with the active endorser
                      prepareTxnForEndorsement(fqSubmitterId, credDefId, credDefCreated.credDefJson, endorser.did) {
                        case Success(ledgerRequest) =>
                          ctx.endorser.endorseTxn(ledgerRequest, ledgerPrefix) {
                            case Success(_) =>
                              ctx.apply(AskedForEndorsement(credDefCreated.credDefId, ledgerRequest))
                            case Failure(e) =>
                              problemReport(new Exception(e))
                          }
                        case Failure(e) => problemReport(new Exception(e))
                      }
                    case other =>
                      ctx.logger.info(s"no active/matched endorser found to be used for creddef endorsement (ledger prefix: $ledgerPrefix): " + other)
                      //no active endorser or active endorser is NOT the same as given/configured endorserDID
                      prepareTxnForEndorsement(fqSubmitterId, credDefId, credDefCreated.credDefJson, endorserDID) {
                        case Success(data: TxnForEndorsement) =>
                          ctx.signal(NeedsEndorsement(credDefId, data))
                          ctx.apply(AskedForEndorsement(credDefId, data))
                        case Failure(e) => problemReport(e)
                      }
                  }
                case Failure(e) => problemReport(e)
              }
            case Failure(e) => problemReport(e)
          }
        case Failure(e) =>
          problemReport(e)
      }
    } catch {
      case e: Exception => problemReport(e)
    }
  }

  private def writeCredDefToLedger(fqSubmitterDID: FqDID,
                                   fqCredDefId: FqCredDefId,
                                   credDefJson: String)
                                  (handleResult: Try[SubmittedTxn] => Unit): Unit = {
    ctx.ledger
      .prepareCredDefTxn(
        credDefJson,
        fqCredDefId,
        fqSubmitterDID,
        None) {
        case Success(pt: PreparedTxn) =>
          signPreparedTxn(pt, fqSubmitterDID) {
            case Success(smr: SignedMsgResult) =>
              ctx.ledger.submitTxn(pt, smr.signatureResult.signature, Array.empty)(handleResult)
            case Failure(ex) =>
              handleResult(Failure(ex))
          }
        case Failure(ex) =>
          handleResult(Failure(ex))
      }
  }

  private def prepareTxnForEndorsement(fqSubmitterDID: FqDID,
                                       credDefId: CredDefId,
                                       credDefJson: String,
                                       endorserDid: FqDID)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    if (endorserDid.nonEmpty) {
      ctx.ledger
        .prepareCredDefTxn(
          credDefJson,
          credDefId,
          fqSubmitterDID,
          Option(endorserDid)) {
          case Success(pt: PreparedTxn) =>
            signPreparedTxn(pt, fqSubmitterDID) {
              case Success(smr: SignedMsgResult) =>
                //NOTE: what about other endorsementSpecType (cheqd etc)
                if (pt.isEndorsementSpecTypeIndy) {
                  val txn = IndyLedgerUtil.buildIndyRequest(pt.txnBytes, Map(extractUnqualifiedDidStr(fqSubmitterDID) -> smr.signatureResult.toBase58))
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

  def handleEndorsementResult(m: EndorsementResult, e: State.WaitingOnEndorser): Unit = {
    if (m.code == ENDORSEMENT_RESULT_SUCCESS_CODE) {
      ctx.apply(CredDefWritten(e.credDefId))
      ctx.signal(StatusReport(e.credDefId))
    } else {
      problemReport(new RuntimeException(s"error during endorsement => code: ${m.code}, description: ${m.description}"))
    }
  }

  //NOTE: handling legacy and new fqSubmitterDID (as legacy one would NOT have been created with namespace prefix)
  private def signPreparedTxn(pt: PreparedTxn,
                              fqSubmitterDID: FqDID)
                             (handleResult: Try[SignedMsgResult] => Unit): Unit = {
    ctx.wallet.sign(pt.bytesToSign, pt.signatureType, signerDid = Option(fqSubmitterDID)) {
      case Success(resp) =>
        handleResult(Try(resp))
      case Failure(_) =>
        ctx.wallet.sign(pt.bytesToSign, pt.signatureType, signerDid = Option(extractUnqualifiedDidStr(fqSubmitterDID)))(handleResult)
    }
  }

  def problemReport(e: Throwable): Unit = {
    ctx.logger.warn(e.toString)
    ctx.apply(WriteFailed(Option(e.getMessage).getOrElse("unknown error")))
    ctx.signal(ProblemReport(e.toString))
  }

  def _submitterDID(init: State.Initialized): DidStr =
    init
    .parameters
    .initParams
    .find(_.name.equals(MY_ISSUER_DID))
    .map(_.value)
    .getOrElse(throw MissingIssuerDID)

  def missingVkOrEndorserErr(did: DidStr, e: LedgerRejectException): Boolean =
    e.msg.contains("Not enough ENDORSER signatures") ||
      e.msg.contains(s"verkey for $did cannot be found") ||
      e.msg.contains(s"verkey for ${extractUnqualifiedDidStr(did)} cannot be found")

  def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  def getInitParams(params: ProtocolInitialized): Parameters =
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )
}