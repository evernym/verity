package com.evernym.verity.protocol.actor

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.{AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME, AGENCY_AGENT_REGION_ACTOR_NAME}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.agency.AgencyIdUtil
import com.evernym.verity.actor.agent.msghandler.incoming.SignalMsgFromDriver
import com.evernym.verity.actor.agent.msghandler.outgoing.SendSignalMsg
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.cache.Cache
import com.evernym.verity.config.AppConfig
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{Driver, PinstId, ProtoRef, ProtocolRegistry, SignalEnvelope}
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

/**
  * A base Driver for Drivers in an Akka actor system
  * @param cp: an ActorDriverConstructionParameter
  */
abstract class ActorDriver(cp: ActorDriverGenParam)
  extends Driver
    with ShardRegionNames
    with HasAppConfig
    with HasActorResponseTimeout
    with AgencyIdUtil
    with MsgTraceProvider {

  val system: ActorSystem = cp.system
  val appConfig: AppConfig = cp.config

  private def protoRegion(protoRef: ProtoRef): ActorRef = {
    ClusterSharding.get(system).shardRegion(ActorProtocol.buildTypeName(protoRef))
  }

  def sendToProto(protoRef: ProtoRef, pinstId: PinstId, cmd: Any): Unit = {
    protoRegion(protoRef) ! ForIdentifier(pinstId, cmd)
  }

  def sendToAgencyAgent(msg: Any): Unit = {
    getAgencyDID(cp.generalCache).foreach { agencyDID =>
      cp.agentMsgRouter.execute(InternalMsgRouteParam(agencyDID, msg))
    }
  }

  /**
    * Sends the given 'msg' to the agent actor (called a forwarder)
    * who originally sent/forwarded the incoming message to the protocol
    * and then that protocol sent the signal msg to this driver
    * from where this method is being called
    *
    * @param msg
    * @tparam A
    * @return
    */
  def sendToForwarder[A](msg: Any): Option[Control] = {
    cp.msgForwarder.forwarder.foreach( _ ! msg)
    None
  }

  /**
   * these signal messages will be sent to agent actor to be processed
   * @param se
   * @tparam A
   * @return
   */
  def processSignalMsg[A](se: SignalEnvelope[A]): Option[Control] = {
    val result = sendToForwarder(SignalMsgFromDriver(se.signalMsg, se.threadId, se.protoRef, se.pinstId))
    trackProgress(se)
    result
  }

  /**
   * these signal messages are sent to edge agent
   * @param sig
   * @tparam A
   * @return
   */
  def sendSignalMsg[A](sig: SignalEnvelope[A]): Option[Control] = {
    val outMsg = SendSignalMsg(sig.signalMsg, sig.threadId, sig.protoRef, sig.pinstId, sig.requestMsgId)
    val result = sendToForwarder(outMsg)
    trackProgress(sig)
    result
  }

  def trackProgress[A](sig: SignalEnvelope[A]): Unit = {
    val protoDef = cp.protocolRegistry.find(sig.protoRef).map(_.protoDef).get
    MsgProgressTracker.recordProtoMsgStatus(protoDef, sig.pinstId, s"sent-to-agent-actor",
      sig.requestMsgId.getOrElse(UUID.randomUUID().toString), outMsg = Option(sig.signalMsg))
  }

  lazy val userRegion: ActorRef = ClusterSharding.get(cp.system).shardRegion(userAgentRegionName)
  lazy val agencyRegion: ActorRef = ClusterSharding.get(system).shardRegion(AGENCY_AGENT_REGION_ACTOR_NAME)
  lazy val agencyPairwiseRegion: ActorRef = ClusterSharding.get(system).shardRegion(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME)

}

case class ActorDriverGenParam(system: ActorSystem,
                               config: AppConfig,
                               protocolRegistry: ProtocolRegistry[ActorDriverGenParam],
                               generalCache: Cache, agentMsgRouter: AgentMsgRouter,
                               msgForwarder: MsgForwarder)

