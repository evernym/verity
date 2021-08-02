package com.evernym.verity.protocol.engine

import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.protocol.legacy.services.ProtocolServices
import com.evernym.verity.util2.Exceptions

trait ProtocolTypes[P,R,M,E,S,I] {
  type Proto = Protocol[P,R,M,E,S,I]
  type ProtoDef = ProtocolDefinition[P,R,M,E,S,I]
  type Services = ProtocolServices[M,E,I]
}

/**
  * Protocol Containers hold one and only one protocol instance
  */
trait ProtocolContainer[P,R,M,E,S,I]
  extends ProtocolTypes[P,R,M,E,S,I]
    with ProtocolContext[P,R,M,E,S,I] {

  def pinstId: PinstId

  def definition: ProtoDef
  def eventRecorder: RecordsEvents
  def sendsMsgs: SendsMsgs
  def driver: Option[Driver]

  def createServices: Option[Services]

  def requestInit(): Unit

  //TODO: had to use lazy to get around some failures, we may wanna come back to it
  lazy val _services: Option[Services] = createServices

  lazy val protocol: Protocol[P,R,M,E,S,I] = definition.create(this)

  def protoRef: ProtoRef = definition.msgFamily.protoRef

  /**
    * Recovers if events exist in recorder, otherwise initializes.
    */
  def recoverOrInit(): Unit = {
    val stateAndEvents = eventRecorder.recoverState(pinstId)
    state = stateAndEvents._1.asInstanceOf[S]
    val pendingEvents = stateAndEvents._2 // Events to be applied after the state
    if (pendingEvents.nonEmpty) {
      pendingEvents foreach applyRecordedEvent
      logger.debug(s"$protocolIdForLog events got applied" )
    } else {
      requestInit()
      logger.debug(s"$protocolIdForLog requested init protocol" )
    }
  }

  def submitInitMsg(parameters: Set[Parameter]): Unit = {
    try {
      val init = definition.createInitMsg(Parameters(parameters))
      submit(init)
    } catch {
      case e: Exception =>
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def protocolIdForLog: String = s"[${definition.msgFamily.protoRef}-$pinstId]"
}
