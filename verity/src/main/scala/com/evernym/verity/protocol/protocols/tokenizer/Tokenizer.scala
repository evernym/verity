package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.actor.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.Init
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.{AskForToken, GetToken, Msg, ProblemReport, PushToken, Requester, Role, SigningTokenErr, Tokenizer, Token => TokenMsg}
import com.evernym.verity.protocol.protocols.tokenizer.{Token => TokenEvt}
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.util.Util.getNewEntityId

import scala.util.{Failure, Success}

trait TokenizerEvt

class Tokenizer(val ctx: ProtocolContextApi[Tokenizer, Role, Msg, Any, TokenizerState, String])
  extends Protocol[Tokenizer,Role,Msg,Any,TokenizerState,String](TokenizerDefinition) {

  override def handleProtoMsg: (TokenizerState, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized | _: State.TokenCreated, _, m: GetToken) => withTokenEvt(m, generateToken)
    case (_: State.TokenRequested, Some(Tokenizer), m: TokenMsg) => withTokenEvt(m, receivedToken)
    case (_, Some(Tokenizer), m: ProblemReport) => ctx.apply(Failed(m.msg))
  }

  override def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  def mainHandleControl: (TokenizerState, Option[Role], Control) ?=> Any = {
    case (_: State.Uninitialized, None, c: Init) =>
      ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))

    case (_, None | Some(Requester), c: AskForToken) =>
      askForToken(c)
  }

  private def askForToken(c: AskForToken): Unit = {
    ctx.apply(RequestedToken(
      setter=Some(SetRoster(requester=_selfIdx, tokenizer = _otherIdx))
    ))
    ctx.send(GetToken(c.sponseeId, c.sponsorId, c.pushId))
  }

  override def applyEvent: ApplyEvent = {
    case (_,                        _, e: ProtocolInitialized   )                                =>
      (State.Initialized(getInitParams(e)), initialize(e.parameters))

    case (_, _ , RequestedToken(s)) => (
      State.TokenRequested(),
      setRoles(s.get)
    )

    case (_: State.Initialized | _: State.TokenCreated , _ , e: CreatedToken) => (
      State.TokenCreated(fromTokenEvt(e.token.get)),
      setRoles(e.setter.get)
    )

    case (_: State.TokenRequested, _, e: ReceivedToken) =>
      State.TokenReceived(fromTokenEvt(e.token.get))

    case (_, _, e: Failed) =>
      State.TokenFailed(e.err)
  }

  private def fromTokenEvt(e: TokenEvt): TokenMsg =
    TokenMsg(e.sponseeId, e.sponsorId, e.nonce, e.timestamp, e.sig, e.sponsorVerKey)

  def receivedToken(m: TokenMsg): Unit =
    ctx.apply(ReceivedToken(Some(m.asEvent)))

  def withTokenEvt[T](input: T, fn: T => Unit): Unit = {
    try {
      fn(input)
    } catch {
      case e: Exception => problemReport(e.getMessage)
    }
  }

  private def generateToken(m: GetToken): Unit = {
    val nonce = getNewEntityId
    val timestamp = TimeUtil.nowDateString
    ctx.wallet.sign((nonce + timestamp + m.sponseeId + m.sponsorId).getBytes) match {
      case Success(sig) =>
        val token = TokenMsg(m.sponseeId, m.sponsorId, nonce, timestamp, sig.toBase64, sig.verKey)
        ctx.apply(CreatedToken(
          setter=Some(SetRoster(requester=_otherIdx, tokenizer=_selfIdx)),
          token=Some(token.asEvent)
        ))
        ctx.send(PushToken(token, m.pushId))
      case Failure(ex) =>
        ctx.logger.error(ex.toString)
        problemReport(SigningTokenErr.err)
    }
  }

  def setRoles(r: SetRoster): Roster[Role] = ctx.getRoster.withAssignment(
    Requester -> r.requester,
    Tokenizer -> r.tokenizer,
  )

  def getInitParams(params: ProtocolInitialized): Parameters =
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )

  def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  def problemReport(logErr: String, optMsg: Option[String]=None): Unit = {
    ctx.logger.error(logErr)
    ctx.apply(Failed(logErr))
    //TODO: eventually, a problem report should be able to be sent. Because of the push com method we are using now, its not
  }

  def _isRequester(setter: SetRoster): Boolean = setter.requester == _selfIdx
  def _otherIdx: ParticipantIndex = ctx.getRoster.otherIndex(ctx.getRoster.selfIndex_!)
  def _selfIdx: ParticipantIndex = ctx.getRoster.selfIndex_!
}
