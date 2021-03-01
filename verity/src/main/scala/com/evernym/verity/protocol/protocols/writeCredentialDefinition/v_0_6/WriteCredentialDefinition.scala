package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.actor.wallet.CredDefCreated
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.actor.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncService.ledger.LedgerRejectException
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.Role.Writer

import scala.util.{Failure, Success}

trait Event

class WriteCredDef(val ctx: ProtocolContextApi[WriteCredDef, Role, Msg, Any, CredDefState, String])
  extends Protocol[WriteCredDef, Role, Msg, Any, CredDefState, String](CredDefDefinition) {

  override def handleProtoMsg: (CredDefState, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)

  }

  def mainHandleControl: (CredDefState, Option[Role], Control) ?=> Any = {
    case (_, _, c: Init) => ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))
    case (s: State.Initialized, _, m: Write) => writeCredDef(m, s)
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
      ctx.ledger.getSchema(m.schemaId) {
        case Success (getSchemaResp) =>
          val schemaJson = DefaultMsgCodec.toJson(getSchemaResp.schema)
          ctx.wallet.createCredDef(
            submitterDID,
            schemaJson,
            tag,
            sigType=None,
            revocationDetails=Some(revocationDetails)
          ) {
            case Success(credDefCreated: CredDefCreated) =>
              ctx.ledger.writeCredDef(submitterDID, credDefCreated.credDefJson) {
                case Success(_) =>
                  ctx.apply(CredDefWritten(credDefCreated.credDefId))
                  ctx.signal(StatusReport(credDefCreated.credDefId))
                case Failure(e: LedgerRejectException) if missingVkOrEndorserErr(submitterDID, e) =>
                  ctx.logger.warn(e.toString)
                  val endorserDID = init.parameters.paramValue(DEFAULT_ENDORSER_DID).getOrElse("")
                  if (endorserDID.nonEmpty) {
                    ctx.ledger.prepareCredDefForEndorsement(submitterDID, credDefCreated.credDefJson, endorserDID) {
                      case Success(ledgerRequest) =>
                        ctx.signal(NeedsEndorsement(credDefCreated.credDefId, ledgerRequest.req))
                        ctx.apply(AskedForEndorsement(credDefCreated.credDefId, ledgerRequest.req))
                      case Failure(e) => problemReport(e)
                    }
                  } else {
                    problemReport(new Exception("No default endorser defined"))
                  }
                case Failure(e) => problemReport(e)
              }
            case Failure(e) => problemReport(e)
          }
        case Failure(e) => problemReport(e)
      }
    } catch {
      case e: Exception => problemReport(e)
    }
  }

  def problemReport(e: Throwable): Unit = {
    ctx.logger.warn(e.toString)
    ctx.apply(WriteFailed(Option(e.getMessage).getOrElse("unknown error")))
    ctx.signal(ProblemReport(e.toString))
  }

  def _submitterDID(init: State.Initialized): DID =
    init
    .parameters
    .initParams
    .find(_.name.equals(MY_ISSUER_DID))
    .map(_.value)
    .getOrElse(throw MissingIssuerDID)

  def missingVkOrEndorserErr(did: DID, e: LedgerRejectException): Boolean =
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