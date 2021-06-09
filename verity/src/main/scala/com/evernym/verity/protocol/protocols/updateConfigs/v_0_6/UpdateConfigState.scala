package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

trait Event

sealed trait State
object State {
  case class Initial() extends State
}