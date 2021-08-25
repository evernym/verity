package com.evernym.verity.protocol.engine.msg

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{ParameterStored, Parameters}

/**
 * This message is sent only when protocol is being created/initialized for first time
 *
 * @param params - Set of Parameter (key & value) which protocol needs
 */

case class Init(params: Parameters) extends Control {
  def parametersStored: Set[ParameterStored] = params.initParams.map(p => ParameterStored(p.name, p.value))
}
