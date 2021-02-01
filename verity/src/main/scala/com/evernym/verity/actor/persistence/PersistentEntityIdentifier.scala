package com.evernym.verity.actor.persistence

import akka.persistence.PersistentActor
import com.evernym.verity.actor.base.EntityIdentifier

trait PersistentEntityIdentifier extends EntityIdentifier { this: PersistentActor =>
  //NOTE: don't remove/change below three vals else it won't be backward compatible
  override lazy val persistenceId: String = actorId
}
