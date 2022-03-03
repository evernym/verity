package com.evernym.verity.actor.cluster_singleton

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.persistence.SingletonPersistentAgentActorBase
import com.evernym.verity.actor.{ActorMessage, MappingAdded}
import com.evernym.verity.config.ConfigConstants
import com.evernym.verity.constants.ActorNameConstants._

import scala.concurrent.ExecutionContext


/**
 * this actor was designed to store any constant (key value pair)
 * which once initialized via code and then doesn't change
 *
 * right now it is only used to store agency's DID
 *
 * @param agentActorContext
 */
class KeyValueMapper(executionContext: ExecutionContext)
                    (implicit val agentActorContext: AgentActorContext)
  extends KeyValueMapperBase {

  override val receiveSpecificEvent: Receive = {
    case _ =>
  }

  override val receiveSpecificCmd: Receive = LoggingReceive.withLabel("receiveSpecificCmd") {
    case _ =>
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

object KeyValueMapper{
  val name: String = KEY_VALUE_MAPPER_ACTOR_NAME
  def props(executionContext: ExecutionContext)(implicit agentActorContext: AgentActorContext) =
    Props(new KeyValueMapper(executionContext))
}

trait KeyValueMapperBase extends SingletonPersistentAgentActorBase {

  lazy val secretConfigKeyName: String = ConfigConstants.SECRET_KEY_VALUE_MAPPER

  var mapping: Map[String, String] = Map.empty

  override val receiveBaseEvent: Receive = {
    case ma: MappingAdded =>
      mapping += (ma.key -> ma.value)
  }

  override val receiveBaseCmd: Receive = LoggingReceive.withLabel("receiveBaseCmd") {
    case am: AddMapping =>
      writeApplyAndSendItBack(MappingAdded(am.key, am.value))

    case gv: GetValue => sender() ! mapping.get(gv.key)
  }
}

//cmd
case class AddMapping(key: String, value: String) extends ActorMessage
case class GetValue(key: String) extends ActorMessage

