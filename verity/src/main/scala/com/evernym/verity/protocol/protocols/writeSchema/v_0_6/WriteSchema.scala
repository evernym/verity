package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.endorser.ENDORSEMENT_RESULT_SUCCESS_CODE
import com.evernym.verity.protocol.engine.asyncapi.ledger.{IndyLedgerUtil, LedgerRejectException, TxnForEndorsement}
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
import com.evernym.verity.vdr.{FqDID, FqSchemaId, PreparedTxn, SubmittedTxn}

import scala.util.{Failure, Success, Try}

class WriteSchema(val ctx: ProtocolContextApi[WriteSchema, Role, Msg, Any, WriteSchemaState, String])
  extends Protocol[WriteSchema, Role, Msg, Any, WriteSchemaState, String](WriteSchemaDefinition) {

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
      val fqSubmitterDID = ctx.ledger.fqDID(_submitterDID(init))
      ctx.wallet.createSchema(fqSubmitterDID, m.name, m.version, seqToJson(m.attrNames)) {
        case Success(schemaCreated: SchemaCreatedResult) =>
          val fqSchemaId = ctx.ledger.fqSchemaId(schemaCreated.schemaId, Option(fqSubmitterDID))
          writeSchemaToLedger(fqSubmitterDID, fqSchemaId, schemaCreated.schemaJson) {
            case Success(SubmittedTxn(_)) =>
              ctx.apply(SchemaWritten(fqSchemaId))
              ctx.signal(StatusReport(fqSchemaId))
            case Failure(e: LedgerRejectException) if missingVkOrEndorserErr(fqSubmitterDID, e) =>
              ctx.logger.info(e.toString)
              val fqEndorserDID = ctx.ledger.fqDID(m.endorserDID.getOrElse(init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse("")))
              //NOTE: below code is assuming verity is only supporting indy ledgers,
              // it should be changed when verity starts supporting different types of ledgers
              val indyLedgerPrefix = ctx.ledger.getIndyDefaultLegacyPrefix()
              ctx.endorser.withCurrentEndorser(indyLedgerPrefix) {
                case Success(Some(endorser)) if fqEndorserDID.isEmpty || fqEndorserDID.contains(endorser.did) =>
                  ctx.logger.info(s"registered endorser to be used for schema endorsement (ledger prefix: $indyLedgerPrefix): " + endorser)
                  //no explicit endorser given/configured or the given/configured endorser is matching with the active endorser
                  prepareTxnForEndorsement(fqSubmitterDID, fqSchemaId, schemaCreated.schemaJson, endorser.did) {
                    case Success(ledgerRequest) =>
                      ctx.endorser.endorseTxn(ledgerRequest, indyLedgerPrefix) {
                        case Failure(exception) => problemReport(exception)
                        case Success(value) => ctx.apply(AskedForEndorsement(schemaCreated.schemaId, ledgerRequest))
                      }
                    case Failure(e) => problemReport(e)
                  }

                case other =>
                  ctx.logger.info(s"no active/matched endorser found to be used for schema endorsement (ledger prefix: $indyLedgerPrefix): " + other)
                  //any failure while getting active endorser, or no active endorser or active endorser is NOT the same as given/configured endorserDID
                  prepareTxnForEndorsement(fqSubmitterDID, fqSchemaId, schemaCreated.schemaJson, fqEndorserDID) {
                    case Success(data: TxnForEndorsement) =>
                      ctx.signal(NeedsEndorsement(fqSchemaId, data))
                      ctx.apply(AskedForEndorsement(fqSchemaId, data))
                    case Failure(e) =>
                      problemReport(e)
                  }
              }
            case Failure(e) =>
              problemReport(e)
          }
        case Failure(e) =>
          problemReport(e)
      }
    } catch {
      case e: Exception => problemReport(e)
    }
  }

  private def writeSchemaToLedger(fqSubmitterDID: FqDID,
                                  fqSchemaId: FqSchemaId,
                                  schemaJson: String)
                                 (handleResult: Try[SubmittedTxn] => Unit): Unit = {
    ctx.ledger
      .prepareSchemaTxn(
        schemaJson,
        fqSchemaId,
        ctx.ledger.fqDID(fqSubmitterDID),
        None) {
        case Success(pt: PreparedTxn) =>
          //NOTE: once new version of issuer-setup protocol (which supports fq DID) is created/used
          // the `signerDid` logic in below call will require change
          ctx.wallet.sign(pt.bytesToSign, pt.signatureType, signerDid = Option(extractUnqualifiedDidStr(fqSubmitterDID))) {
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
                                       fqSchemaId: FqSchemaId,
                                       schemaJson: String,
                                       fqEndorserDid: FqDID)
                                      (handleResult: Try[TxnForEndorsement] => Unit): Unit = {
    if (fqEndorserDid.nonEmpty) {
      ctx.ledger
        .prepareSchemaTxn(
          schemaJson,
          fqSchemaId,
          ctx.ledger.fqDID(fqSubmitterDID),
          Option(fqEndorserDid)) {
          case Success(pt: PreparedTxn) =>
            //TODO (VE-3368): will signing api won't allow fq identifier (see below)
            ctx.wallet.sign(pt.bytesToSign, pt.signatureType, signerDid = Option(extractUnqualifiedDidStr(fqSubmitterDID))) {
              case Success(smr: SignedMsgResult) =>
                //TODO (VE-3368): what about other endorsementSpecType (cheqd etc)
                if (pt.isEndorsementSpecTypeIndy) {
                  val txn = IndyLedgerUtil.buildIndyRequest(pt.txnBytes, Map(ctx.ledger.fqDID(fqSubmitterDID) -> smr.signatureResult.toBase64))
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

  private def _submitterDID(init: State.Initialized): DidStr =
    init
      .parameters
      .initParams
      .find(_.name.equals(MY_ISSUER_DID))
      .map(_.value)
      .getOrElse(throw MissingIssuerDID)

  private def missingVkOrEndorserErr(did: DidStr, e: LedgerRejectException): Boolean =
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
