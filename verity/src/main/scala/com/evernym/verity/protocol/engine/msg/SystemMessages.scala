package com.evernym.verity.protocol.engine.msg

import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.protocol.engine.{DomainId, PackagingContext, StorageId}
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
case class SetSponsorRel(sponsorRel: SponsorRel) extends InternalSystemMsg

/**
 * The sponsorRel given to the protocol
 *
 * @param policy data retention policy
 */
case class SetDataRetentionPolicy(policy: Option[String]) extends InternalSystemMsg

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