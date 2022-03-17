package com.evernym.verity.event_bus

package object event_handlers {

  //topics (TODO: name/pattern to be finalized)
  val TOPIC_ENDORSER_REGISTRY = "endorser-registry"

  //event types
  val TYPE_ENDORSER_ACTIVE = "endorser_active"
  val TYPE_ENDORSER_INACTIVE = "endorser_inactive"
}
