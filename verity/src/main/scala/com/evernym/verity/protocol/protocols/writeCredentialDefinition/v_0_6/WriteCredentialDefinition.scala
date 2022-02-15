package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerRejectException, LedgerUtil, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{CredDefCreatedResult, SignedMsgResult}
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.Role.Writer
import com.evernym.verity.vdr.{FQCredDefId, FQDid, PreparedTxn, SubmittedTxn}

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
    case (_: State.Processing, _, e: CredDefWritten) => State.Done(e.credDefId)
    case (_: State.Processing, _, e: WriteFailed) => State.Error(e.error)
  }

  def writeCredDef(m: Write, init: State.Initialized): Unit = {
    ctx.apply(RequestReceived(m.name, m.schemaId))

    try {
      val tag = m.tag.getOrElse("latest")
      val revocationDetails = m.revocationDetails.map(_.toString).getOrElse("{}")

      val submitterDID = _submitterDID(init)
      val fqSubmitterId = ctx.ledger.fqID(submitterDID)
      ctx.ledger.resolveSchema(ctx.ledger.fqSchemaId(m.schemaId)) {
        case Success (schema) =>
          ctx.wallet.createCredDef(
            fqSubmitterId,
            schema.json,
            tag,
            sigType=None,
            revocationDetails=Some(revocationDetails)
          ) {
            case Success(credDefCreated: CredDefCreatedResult) =>
              val fqCredDefId = ctx.ledger.fqCredDefId(credDefCreated.credDefId)

              writeCredDefToLedger(submitterDID, fqCredDefId, credDefCreated.credDefJson) {
                case Success(SubmittedTxn(_)) =>
                  ctx.apply(CredDefWritten(fqCredDefId))
                  ctx.signal(StatusReport(fqCredDefId))
                case Failure(e: LedgerRejectException) if missingVkOrEndorserErr(submitterDID, e) =>
                  ctx.logger.info(e.toString)
                  val fqEndorserDID = ctx.ledger.fqID(m.endorserDID.getOrElse(init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse("")))
                  if (fqEndorserDID.nonEmpty) {
                    prepareTxnForEndorsement(fqSubmitterId, fqCredDefId, credDefCreated.credDefJson, fqEndorserDID) {
                      case Success(data: TxnForEndorsement) =>
                        ctx.signal(NeedsEndorsement(fqCredDefId, data))
                        ctx.apply(AskedForEndorsement(fqCredDefId, data))
                      case Failure(e) => problemReport(e)
                    }
                  } else {
                    problemReport(new Exception("No default endorser defined"))
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

  private def writeCredDefToLedger(submitterDID: FQDid,
                                   fqCredDefId: FQCredDefId,
                                   credDefJson: String)
                                  (handleResult: Try[SubmittedTxn] => Unit): Unit = {
    ctx.ledger
      .prepareCredDefTxn(
        credDefJson,
        fqCredDefId,
        ctx.ledger.fqID(submitterDID),
        None) {
        case Success(pt: PreparedTxn) =>
          ctx.wallet.sign(pt.bytesToSign, pt.signatureSpec, signerDid = Option(submitterDID)) {
            case Success(smr: SignedMsgResult) =>
              ctx.ledger.submitTxn(pt, smr.signatureResult.signature, Array.empty)(handleResult)
            case Failure(ex) =>
              handleResult(Failure(ex))
          }
        case Failure(ex) =>
          handleResult(Failure(ex))
      }
  }

  private def prepareTxnForEndorsement(submitterDID: FQDid,
                                       fqCredDefId: FQCredDefId,
                                       credDefJson: String,
                                       fqEndorserDid: FQDid)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    ctx.ledger
      .prepareCredDefTxn(
        credDefJson,
        fqCredDefId,
        ctx.ledger.fqID(submitterDID),
        Option(fqEndorserDid)) {
        case Success(pt: PreparedTxn) =>
          ctx.wallet.sign(pt.bytesToSign, pt.signatureType, signerDid = Option(submitterDID)) {
            case Success(smr: SignedMsgResult) =>
              if (LedgerUtil.isIndyNamespace(pt.namespace)) {
                val txn = LedgerUtil.buildIndyRequest(pt.txnBytes, Map(ctx.ledger.fqID(submitterDID) -> smr.signatureResult.toBase64))
                handleResult(Success(txn))
              } else {
                handleResult(Failure(new RuntimeException("endorsement spec not supported:" + pt.endorsementSpec)))
              }
            case Failure(ex) =>
              handleResult(Failure(ex))
          }
        case Failure(ex) =>
          handleResult(Failure(ex))
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
    e.msg.contains(s"verkey for $did cannot be found") || e.msg.contains("Not enough ENDORSER signatures")

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