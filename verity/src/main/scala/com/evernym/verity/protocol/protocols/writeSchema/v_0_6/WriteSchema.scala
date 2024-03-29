package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.asyncapi.vdr.{IndyLedgerUtil, VdrRejectException, TxnForEndorsement}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{SchemaCreatedResult, SignedMsgResult}
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.events.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.Role.Writer
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.State.{Done, Error, Initialized, Processing}
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vdr.VDRUtil.extractUnqualifiedDidStr
import com.evernym.verity.vdr.{FqDID, PreparedTxn, SchemaId, SubmittedTxn}
import com.typesafe.scalalogging.Logger

import scala.util.{Failure, Success, Try}

class WriteSchema(val ctx: ProtocolContextApi[WriteSchema, Role, Msg, Any, WriteSchemaState, String])
  extends Protocol[WriteSchema, Role, Msg, Any, WriteSchemaState, String](WriteSchemaDefinition) {
  val logger: Logger = getLoggerByClass(getClass)

  override def handleProtoMsg: (WriteSchemaState, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  override def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  def mainHandleControl: (WriteSchemaState, Option[Role], Control) ?=> Any = {
    case (_, _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (s: State.Initialized, _, m: Write) => writeSchema(m, s)
    case (s: State.WaitingOnEndorser, _, m: EndorsementResult) => handleEndorsementResult(m, s)
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
    case (_: Processing, _, e: AskedForEndorsement)    => State.WaitingOnEndorser(e.schemaId, e.schemaJson)
    case (s @ (_: Processing | _:State.WaitingOnEndorser), _, e: SchemaWritten)  => Done(e.schemaId)
    case (s @ (_: Processing | _:State.WaitingOnEndorser), _, e: WriteFailed)    => Error(e.error)
  }

  def writeSchema(m: Write, init: State.Initialized): Unit = {
    try {
      ctx.apply(RequestReceived(m.name, m.version, m.attrNames))
      val submitterDID = _submitterDID(init)
      val fqSubmitterDID = ctx.vdr.fqDID(submitterDID, force = true)
      ctx.wallet.createSchema(ctx.vdr.fqDID(submitterDID, force = false), m.name, m.version, seqToJson(m.attrNames)) {
        case Success(schemaCreated: SchemaCreatedResult) =>
          val schemaId = schemaCreated.schemaId
          writeSchemaToLedger(fqSubmitterDID, schemaId, schemaCreated.schemaJson) {
            case Success(SubmittedTxn(resp)) =>
              ctx.apply(SchemaWritten(schemaId))
              ctx.signal(StatusReport(schemaId))
            case Failure(e: VdrRejectException) if missingVkOrEndorserErr(fqSubmitterDID, e) =>
              ctx.logger.info(e.toString)
              val endorserDID = m.endorserDID.getOrElse(init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse(""))
              //NOTE: below code is assuming verity is only supporting indy ledgers,
              // it should be changed when verity starts supporting different types of ledgers
              val ledgerPrefix = ctx.vdr.unqualifiedLedgerPrefix()
              ctx.endorser.withCurrentEndorser(ledgerPrefix) {
                case Success(Some(endorser)) if endorserDID.isEmpty || endorserDID.contains(endorser.did) =>
                  ctx.logger.info(s"registered endorser to be used for schema endorsement (ledger prefix: $ledgerPrefix): " + endorser)
                  //no explicit endorser given/configured or the given/configured endorser is matching with the active endorser
                  prepareTxnForEndorsement(fqSubmitterDID, schemaId, schemaCreated.schemaJson, endorser.did) {
                    case Success(ledgerRequest) =>
                      ctx.endorser.endorseTxn(ledgerRequest, ledgerPrefix) {
                        case Failure(exception) => problemReport(exception)
                        case Success(value) => ctx.apply(AskedForEndorsement(schemaCreated.schemaId, ledgerRequest))
                      }
                    case Failure(e) => problemReport(e)
                  }

                case other =>
                  ctx.logger.info(s"no active/matched endorser found to be used for schema endorsement (ledger prefix: $ledgerPrefix): " + other)
                  //any failure while getting active endorser, or no active endorser or active endorser is NOT the same as given/configured endorserDID
                  prepareTxnForEndorsement(fqSubmitterDID, schemaId, schemaCreated.schemaJson, endorserDID) {
                    case Success(data: TxnForEndorsement) =>
                      ctx.signal(NeedsEndorsement(schemaId, data))
                      ctx.apply(AskedForEndorsement(schemaId, data))
                    case Failure(e) => problemReport(e)
                  }
              }
            case Failure(e) => problemReport(e)
          }
        case Failure(e) => problemReport(e)
      }
    } catch {
      case e: Exception => problemReport(e)
    }
  }

  private def writeSchemaToLedger(fqSubmitterDID: FqDID,
                                  schemaId: SchemaId,
                                  schemaJson: String)
                                 (handleResult: Try[SubmittedTxn] => Unit): Unit = {
    ctx.vdr
      .prepareSchemaTxn(
        schemaJson,
        schemaId,
        fqSubmitterDID,
        None) {
        case Success(pt: PreparedTxn) =>
          signPreparedTxn(pt, fqSubmitterDID) {
            case Success(smr: SignedMsgResult) =>
              ctx.vdr.submitTxn(pt, smr.signatureResult.signature, Array.empty)(handleResult)
            case Failure(ex) => handleResult(Failure(ex))
          }
        case Failure(ex) => handleResult(Failure(ex))
      }
  }

  private def prepareTxnForEndorsement(fqSubmitterDID: FqDID,
                                       schemaId: SchemaId,
                                       schemaJson: String,
                                       endorserDid: DidStr)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    if (endorserDid.nonEmpty) {
      ctx.vdr
        .prepareSchemaTxn(
          schemaJson,
          schemaId,
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
    }  else {
      handleResult(Failure(new Exception("No default endorser defined")))
    }
  }

  private def handleEndorsementResult(m: EndorsementResult, woe: State.WaitingOnEndorser): Unit = {
    if (m.code == ENDORSEMENT_RESULT_SUCCESS_CODE) {
      ctx.apply(SchemaWritten(woe.schemaId))
      ctx.signal(StatusReport(woe.schemaId))
    } else {
      problemReport(new RuntimeException(s"error during endorsement => code: ${m.code}, description: ${m.description}"))
    }
  }

  private def problemReport(e: Throwable): Unit = {
    ctx.logger.error(e.toString)
    ctx.apply(WriteFailed(Option(e.getMessage).getOrElse("unknown error")))
    ctx.signal(ProblemReport(e.toString))
  }

  //NOTE: handling legacy and new fqSubmitterDID (as legacy one would NOT have been created in wallet with namespace prefix)
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

  private def _submitterDID(init: State.Initialized): DidStr =
    init
      .parameters
      .initParams
      .find(_.name.equals(MY_ISSUER_DID))
      .map(_.value)
      .getOrElse(throw MissingIssuerDID)

  private def missingVkOrEndorserErr(did: DidStr, e: VdrRejectException): Boolean =
    e.msg.contains("Not enough ENDORSER signatures") ||
      e.msg.contains(s"verkey for $did cannot be found") ||
      e.msg.contains(s"verkey for ${extractUnqualifiedDidStr(did)} cannot be found")


  private def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  private def getInitParams(params: ProtocolInitialized): Parameters =
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )
}
