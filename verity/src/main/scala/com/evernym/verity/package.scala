package com.evernym

import akka.actor.{Actor, ActorRef}
import com.evernym.verity.protocol.engine.{DID, MsgId}

import scala.concurrent.Future

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
