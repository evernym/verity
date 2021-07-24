package com.evernym.verity.actor.itemmanager

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream.Publish
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor.cluster_singleton.watcher.{AddItem, ForEntityItemWatcher, RemoveItem}
import com.evernym.verity.actor.node_singleton.SingletonProxyEvent

//a helper class to add/remove an entity to/from item manager
class ItemManagerEntityHelper(entityId: EntityId,
                              entityType: String,
                              system: ActorSystem[Nothing]) {

  var isAlreadyAdded = false

  def register(): Unit = {
    if (! isAlreadyAdded) {
      system.eventStream.tell(Publish(SingletonProxyEvent(ForEntityItemWatcher(AddItem(entityId, entityType)))))
      isAlreadyAdded = true
    }
  }

  def deregister(): Unit = {
    if (isAlreadyAdded) {
      system.eventStream.tell(Publish(SingletonProxyEvent(ForEntityItemWatcher(RemoveItem(entityId, entityType)))))
      isAlreadyAdded = false
    }
  }
}
