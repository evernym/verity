//package com.evernym.verity.protocol.protocols.tictactoe

//import com.evernym.verity.AgentActorContext
//import com.evernym.verity.protocol.{Protocol, ProtocolDefinition}
//import com.evernym.verity.actor.event.serializer.EventSerializer
//import com.evernym.verity.util.Util.getLoggerByName
//import com.typesafe.scalalogging.Logger
//
//Note: because all the proto events related to TicTacToe protocol is created in test scope
//those are not accessible in production code unless we change some code there
//so to be able to use test events, created this test generic actor which can use test events.


//TODO REMOVE THIS
//  We should be testing the production GenericProtocolActor... using this will give a false confidence in our tests
//class TestGenericProtocolActor[
//    P <: Protocol[P,R,M,E,S],
//    PD <: ProtocolDefinition[P,R,M,E,S],
//    R,M,E <Any,
//    S]
//  (override val agentActorContext: AgentActorContext, override val protoDef: PD)
//  extends GenericProtocolActor[P, PD,R,M,E,S](agentActorContext, protoDef) with TestEventSerializer {
//
//  override val eventSerializer: EventSerializer = TestSerializerWithDefaultEventMapping
//
//  override val logger: Logger = getLoggerByName("GenericProtocolActor")
//
//  override def protocolReceiveEvent: Receive = {
//    case evt: E =>
//      protocol.apply(evt)
//  }
//
//  override def protocolReceiveCommand: Receive = {
//    case msg: M =>
//      sender ! protocol.handleMsg(msg)
//  }
//}
