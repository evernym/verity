package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.external_api_access.{AccessRight, AccessSign}
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.{Msg, Requester, Role, Tokenizer}

object TokenizerDefinition
  extends ProtocolDefinition[Tokenizer,Role,Msg,Any,TokenizerState,String] {

  val msgFamily: MsgFamily = TokenizerMsgFamily

  override val roles: Set[Role] = Set(Requester, Tokenizer)

  override lazy val initParamNames: Set[String] = Set(SELF_ID, OTHER_ID)

  override val requiredAccess: Set[AccessRight] = Set(AccessSign)

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def create(context: ProtocolContextApi[Tokenizer, Role, Msg, Any, TokenizerState, String]):
  Protocol[Tokenizer, Role, Msg, Any, TokenizerState, String] = {
    new Tokenizer(context)
  }

  override def initialState: TokenizerState = State.Uninitialized()

}
