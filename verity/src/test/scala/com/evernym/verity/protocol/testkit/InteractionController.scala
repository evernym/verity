package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.{Driver, SignalEnvelope}
import com.evernym.verity.util.MsgUtil

class InteractionController(inType: SimpleControllerProviderInputType) extends PassiveController {

  def ~ :Control => Unit = control

  def control(ctl: Control): Unit = {
    val env = MsgUtil.encloseCtl(ctl, inType.threadId)
    inType.system.handleControl(env, inType.myDid)
  }
}

trait PassiveController extends Driver {
  private var receivedSignals: Vector[SignalEnvelope[_]] = Vector.empty

  override def signal[A]: SignalHandler[A] = {
    case se => receivedSignals = receivedSignals :+ se; None
  }

  def takeNext(): Option[SignalEnvelope[_]] = {
    val result = receivedSignals.headOption
    receivedSignals = receivedSignals.drop(1)
    result
  }

  def takeAll(): Vector[SignalEnvelope[_]] = {
    val result = receivedSignals
    receivedSignals = Vector.empty
    result
  }

  def clear(): Unit = receivedSignals = Vector.empty

}