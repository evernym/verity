package com.evernym.verity.actor

import akka.pattern.ask
import akka.actor.{Actor, ActorRef}
import akka.cluster.sharding.ShardRegion.EntityId
import akka.util.Timeout
import com.evernym.verity.AgentId

import scala.concurrent.Future

abstract class RegionHelper[T]() {
  protected def region: ActorRef
  def id: T

  def !(msg: Any)(implicit sender: ActorRef = Actor.noSender):Unit = {
    region.tell(prepare(msg), sender)
  }

  def tell(msg: Any, sender: ActorRef):Unit = {
    region.tell(prepare(msg), sender)
  }


  def ?(msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    region.ask(prepare(msg))(timeout, sender)

  def prepare(msg: Any): Any
}

case class agentRegion(id: EntityId, region: ActorRef) extends RegionHelper[AgentId]() {
  def prepare(msg: Any) = ForIdentifier(id, msg)
}
