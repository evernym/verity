package com.evernym.verity.protocol.engine.msg

import com.evernym.verity.protocol.engine.{DomainId, PackagingContext}
import com.evernym.verity.protocol.{InternalSystemMsg, SystemMsg}

/**
  * The DomainId given to the protocol
  *
  * @param id
  */
case class GivenDomainId(id: DomainId) extends InternalSystemMsg

/**
  * This message is sent only when an persistence failure happens and `eventPersistenceFailure` is invoked
  * @param cause - the specific throwable exception
  * @param event - the event which cause the persistence failure (this event was not properly persisted)
  */
case class PersistenceFailure(cause: Throwable, event: Any) extends SystemMsg

case class StoreThreadContext(pd: PackagingContext,
                              senderOrder: Option[Int] = None,
                              receivedOrder: Option[Map[String, Int]] = None) extends InternalSystemMsg