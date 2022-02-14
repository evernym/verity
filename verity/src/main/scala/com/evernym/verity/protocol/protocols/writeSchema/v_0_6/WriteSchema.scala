package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerRejectException, LedgerUtil, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{SchemaCreatedResult, SignedMsgResult}
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.Role.Writer
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.State.{Done, Error, Initialized, Processing}
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vdr.{PreparedTxn, SubmittedTxn}

import scala.util.{Failure, Success, Try}

class WriteSchema(val ctx: ProtocolContextApi[WriteSchema, Role, Msg, Any, WriteSchemaState, String])
  extends Protocol[WriteSchema, Role, Msg, Any, WriteSchemaState, String](WriteSchemaDefinition) {

  override def handleProtoMsg: (WriteSchemaState, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  def mainHandleControl: (WriteSchemaState, Option[Role], Control) ?=> Any = {
    case (_, _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (s: State.Initialized, _, m: Write) =>
      writeSchema(m, s)
    case _ => ctx.signal(ProblemReport("Unexpected message in current state"))
  }

  override def applyEvent: ApplyEvent = {
    case (_, _, e: ProtocolInitialized) =>
      (State.Initialized(getInitParams(e)), initialize(e.parameters))
    case (_: Initialized, _, e: RequestReceived) =>
      (
        Processing(e.name, e.version, e.attrs),
        ctx.getRoster.withAssignment(Writer() -> ctx.getRoster.selfIndex_!)
      )
    case (_: Processing, _, e: AskedForEndorsement) => State.WaitingOnEndorser(e.schemaId, e.schemaJson)
    case (_: Processing, _, e: SchemaWritten) => Done(e.schemaId)
    case (_: Processing, _, e: WriteFailed) => Error(e.error)
  }

  def writeSchema(m: Write, init: State.Initialized): Unit = {
    ctx.apply(RequestReceived(m.name, m.version, m.attrNames))
    val submitterDID = _submitterDID(init)
    ctx.wallet.createSchema(submitterDID, m.name, m.version, seqToJson(m.attrNames)) {
      case Success(schemaCreated: SchemaCreatedResult) =>
        writeSchemaToLedger(submitterDID, schemaCreated.schemaId, schemaCreated.schemaJson) {
          case Success(SubmittedTxn(_)) =>
            ctx.apply(SchemaWritten(schemaCreated.schemaId))
            ctx.signal(StatusReport(schemaCreated.schemaId))
          case Failure(e: LedgerRejectException) if missingVkOrEndorserErr(submitterDID, e) =>
            ctx.logger.info(e.toString)
            val endorserDID = m.endorserDID.getOrElse(
              init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse("")
            )
            if (endorserDID.nonEmpty) {
              prepareTxnForEndorsement(submitterDID, endorserDID, schemaCreated.schemaId, schemaCreated.schemaJson) {
                case Success(data: TxnForEndorsement) =>
                  ctx.signal(NeedsEndorsement(schemaCreated.schemaId, data))
                  ctx.apply(AskedForEndorsement(schemaCreated.schemaId, data))
                case Failure(e) =>
                  problemReport(e)
              }
            } else {
              problemReport(new Exception("No default endorser defined"))
            }
          case Failure(e) =>
            problemReport(e)
        }
      case Failure(e) =>
        problemReport(e)
    }
  }

  private def writeSchemaToLedger(submitterDID: DidStr,
                                  schemaId: String,
                                  schemaJson: String)
                                 (handleResult: Try[SubmittedTxn] => Unit): Unit = {
    ctx.ledger
      .prepareSchemaTxn(
        schemaJson,
        ctx.ledger.fqID(schemaId),
        ctx.ledger.fqID(submitterDID),
        None) {
        case Success(pt: PreparedTxn) =>
          ctx.wallet.sign(pt.bytesToSign, pt.signatureType) {
            case Success(smr: SignedMsgResult) =>
              ctx.ledger.submitTxn(pt, smr.signatureResult.signature, Array.empty)(handleResult)
            case Failure(ex) =>
              handleResult(Failure(ex))
          }
        case Failure(ex) =>
          handleResult(Failure(ex))
      }
  }

  private def prepareTxnForEndorsement(submitterDID: DidStr,
                                       endorser: String,
                                       schemaId: String,
                                       schemaJson: String)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    ctx.ledger
      .prepareSchemaTxn(
        schemaJson,
        ctx.ledger.fqID(schemaId),
        ctx.ledger.fqID(submitterDID),
        Option(ctx.ledger.fqID(endorser))) {
        case Success(pt: PreparedTxn) =>
          ctx.wallet.sign(pt.bytesToSign, pt.signatureType) {
            case Success(smr: SignedMsgResult) =>
              if (LedgerUtil.isIndyNamespace(pt.namespace)) {
                val txn = LedgerUtil.buildIndyRequest(pt.txnBytes, Map(submitterDID -> smr.signatureResult.toBase64))
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
    ctx.logger.error(e.toString)
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
