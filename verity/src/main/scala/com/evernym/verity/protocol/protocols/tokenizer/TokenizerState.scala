
package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.protocol.engine.Parameters
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.{Token => TokenMsg}

sealed trait TokenizerState

object State {

  case class Uninitialized()                       extends TokenizerState
  case class Initialized(parameters: Parameters)   extends TokenizerState
  case class TokenRequested()                      extends TokenizerState
  case class TokenCreated(token: TokenMsg)         extends TokenizerState
  case class TokenReceived(token: TokenMsg)        extends TokenizerState
  case class TokenFailed(err: String)              extends TokenizerState

}
