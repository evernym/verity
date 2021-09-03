package com.evernym.verity.protocol.engine.msg

import com.evernym.verity.protocol.engine.context.PackagingContext
import com.evernym.verity.protocol.engine.{DomainId, StorageId}
import com.evernym.verity.protocol.{InternalSystemMsg, SystemMsg}

/**
  * The DomainId given to the protocol
  *
  * @param id
  */
case class SetDomainId(id: DomainId) extends InternalSystemMsg

/**
 * The sponsorRel given to the protocol
 *
 * @param sponsorRel
 */
case class SetSponsorRel(sponsorId: String, sponseeId: String) extends InternalSystemMsg

/**
 * The sponsorRel given to the protocol
 *
 * @param configStr data retention policy config string
 */
case class SetDataRetentionPolicy(configStr: Option[String]) extends InternalSystemMsg

/**
 * set the unique storage id to be used with segmented state
 *
 * @param id
 */
case class SetStorageId(id: StorageId) extends InternalSystemMsg

/**
  * This message is sent only when an persistence failure happens and `eventPersistenceFailure` is invoked
  * @param cause - the specific throwable exception
  * @param event - the event which cause the persistence failure (this event was not properly persisted)
  */
case class PersistenceFailure(cause: Throwable, event: Any) extends SystemMsg

case class UpdateThreadContext(pd: PackagingContext,
                               senderOrder: Option[Int] = None,
                               receivedOrder: Option[Map[String, Int]] = None) extends InternalSystemMsg