package com.evernym.verity.actor.persistence

import com.evernym.verity.config.ConfigConstants._

/**
 * base class for all singleton child actor (which is created under 'SingletonParentCommon' actor)
 */
trait SingletonChildrenPersistentActor extends BasePersistentTimeoutActor {

  override val defaultReceiveTimeoutInSeconds: Int = 3600
  override val entityCategory: String = PERSISTENT_SINGLETON_CHILDREN
}
