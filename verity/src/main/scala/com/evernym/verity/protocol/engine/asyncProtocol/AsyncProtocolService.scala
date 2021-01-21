package com.evernym.verity.protocol.engine.asyncProtocol

trait AsyncProtocolService[M] {
  type AsyncHandler = M => Unit
}
