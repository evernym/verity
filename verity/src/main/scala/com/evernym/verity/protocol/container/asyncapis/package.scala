package com.evernym.verity.protocol.container

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.container.actor.AsyncAPIContext

package object asyncapis {

  trait BaseAsyncAccessImpl {
    implicit val asyncAPIContext: AsyncAPIContext

    lazy val appConfig: AppConfig = asyncAPIContext.appConfig
    lazy val context: ActorContext = asyncAPIContext.senderActorContext

    implicit val timeout: Timeout = asyncAPIContext.timeout
    implicit val senderActorRef: ActorRef = asyncAPIContext.senderActorRef
  }
}
