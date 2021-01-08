package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY, OWNER_AGENT_KEY, RECIP_KEY, RECOVERY_KEY}

trait TagLike {
  def tagName: String = "Unrecognized"
  def metaToString: String = ""

  override def toString: String = tagName
}

trait TagLikeCompanion {
  def fromString(v: String): TagLike = {
    v.trim match {
      case AGENT_KEY_TAG.tagName      => AGENT_KEY_TAG
      case EDGE_AGENT_KEY.tagName     => EDGE_AGENT_KEY
      case CLOUD_AGENT_KEY.tagName    => CLOUD_AGENT_KEY
      case OWNER_AGENT_KEY.tagName    => OWNER_AGENT_KEY
      case RECIP_KEY.tagName          => RECIP_KEY
      case RECOVERY_KEY.tagName       => RECOVERY_KEY
      case v                          => throw new UnknownTag("invalid msg pack format found: " + v)
    }
  }
}

trait AgentKeyTag extends TagLike {
  override val tagName = "AGENT_KEY"
}
trait EdgeAgentKeyTag extends TagLike {
  override val tagName = "EDGE_AGENT_KEY"
}
trait CloudAgentKeyTag extends TagLike {
  override val tagName = "CLOUD_AGENT_KEY"
}
trait RecipKeyTag extends TagLike {
  override val tagName = "RECIP_KEY"
}
trait RecoveryKeyTag extends TagLike {
  override val tagName = "RECOVERY_KEY"
}
trait OwnerAgentKeyTag extends TagLike {
  override val tagName = "OWNER_AGENT_KEY"
}

class UnknownTag(message: String) extends Exception(message)
case object InvalidMetadata extends RuntimeException