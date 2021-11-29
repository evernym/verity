package com.evernym.verity.protocol.container

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.util.Timeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.ASYNC_OP_EXECUTOR_ACTOR_DISPATCHER_NAME
import com.evernym.verity.protocol.container.actor.{AsyncAPIContext, AsyncOpResp}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

package object asyncapis {

  trait BaseAsyncAccessImpl {
    implicit val asyncAPIContext: AsyncAPIContext

    lazy val appConfig: AppConfig = asyncAPIContext.appConfig
    lazy val context: ActorContext = asyncAPIContext.senderActorContext

    implicit val timeout: Timeout = asyncAPIContext.timeout
    implicit val senderActorRef: ActorRef = asyncAPIContext.senderActorRef
  }
}
