package com.evernym.verity.actor

import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, ThreadId}

package object agent {

  /*
    For now, this is only used for testing purposes
   */
  case class GetPinstId(msg: Any, threadId: ThreadId=DEFAULT_THREAD_ID)

  case class ActorEndpointDetail(regionTypeName: String, entityId: String)

  type AttrName = String
  type AttrValue = String
}
