package com.evernym.verity.util2

import akka.actor.{Actor, ActorRef}
import com.evernym.verity.config.ConfigUtil.MAX_RETENTION_POLICY
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.typesafe.config.ConfigException

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

package object verity {

  type Version = String
  type AgentId = String

  type ServiceEndpoint = String
  type SenderDID = DID
  type SenderOrder = Int

  type SenderActorRef = ActorRef
  type MsgTypeFormatVersion = String

  type Signature = Array[Byte]
  type Base64Encoded = String

  type RouteId = String

  type ReqId = String
  type ReqMsgId = MsgId
  type RespMsgId = MsgId

  trait ShardActorObject {
    def !(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender)
    def ?(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Future[Any]
  }

  trait ActorObject {
    def !(msg: Any)(implicit sender: ActorRef = Actor.noSender)
    def ?(msg: Any)(implicit sender: ActorRef = Actor.noSender): Future[Any]
  }

}

case class RetentionPolicy(configString: String, elements: PolicyElements)

object PolicyElements {
  def apply(expireAfterDays: String, expireAfterTerminalState: Boolean): PolicyElements = {
    Try (Duration(expireAfterDays)) match {
      case Success(ed) => PolicyElements(ed, expireAfterTerminalState)
      case Failure(e)  =>
        throw new ConfigException.BadValue(expireAfterDays, s"Couldn't parse $expireAfterDays with exception: $e")
    }
  }
}

case class PolicyElements(expiryDuration: Duration, expireAfterTerminalState: Boolean) {
  val expiryDaysStr = s"${expiryDuration.toDays}d"
  if (expiryDuration.toDays > MAX_RETENTION_POLICY)
    throw new ConfigException.BadValue(
      expiryDaysStr, s"Data Retention Policy must be less than $MAX_RETENTION_POLICY, found policy: $expiryDaysStr"
    )
}