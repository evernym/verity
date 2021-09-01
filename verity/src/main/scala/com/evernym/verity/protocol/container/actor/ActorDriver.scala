package com.evernym.verity.protocol.container.actor

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.{AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME, AGENCY_AGENT_REGION_ACTOR_NAME}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.{HasAppConfig, _}
import com.evernym.verity.actor.agent.agency.AgencyIdUtil
import com.evernym.verity.actor.agent.msghandler.incoming.{ProcessSignalMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing.SendSignalMsg
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{Driver, PinstId, ProtoRef, ProtocolRegistry, SignalEnvelope}
import com.evernym.verity.cache.base.Cache

import scala.concurrent.ExecutionContext

/**
  * A base Driver for Drivers in an Akka actor system
  * @param cp: an ActorDriverConstructionParameter
  */
abstract class ActorDriver(cp: ActorDriverGenParam, ec: ExecutionContext)
  extends Driver
    with ShardRegionNames
    with HasAppConfig
    with HasActorResponseTimeout
    with AgencyIdUtil {

  private implicit def executionContext: ExecutionContext = ec

  override def futureExecutionContext: ExecutionContext = ec
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
    sendToForwarder(ProcessSignalMsg(
      SignalMsgParam(se.signalMsg, Option(se.threadContextDetail.threadId)),
      se.protoRef, se.pinstId, se.threadContextDetail, se.requestMsgId))
  }

  /**
   * these signal messages are sent to edge agent
   * @param sig
   * @tparam A
   * @return
   */
  def sendSignalMsg[A](sig: SignalEnvelope[A]): Option[Control] = {
    val outMsg = SendSignalMsg(sig.signalMsg, sig.threadId, sig.protoRef, sig.pinstId, sig.threadContextDetail, sig.requestMsgId)
    sendToForwarder(outMsg)
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

