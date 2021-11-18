package com.evernym.verity.actor.agent

import akka.actor.{Actor, ActorRef}
import com.evernym.verity.actor.HasAppConfig
import com.evernym.verity.constants.ActorNameConstants.SINGLETON_PARENT_PROXY
import com.evernym.verity.util.Util.getActorRefFromSelection

trait HasSingletonParentProxy { this: Actor with HasAppConfig =>
  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)
}
