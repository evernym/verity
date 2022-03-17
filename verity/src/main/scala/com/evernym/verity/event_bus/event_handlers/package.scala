package com.evernym.verity.event_bus

import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.util2.RouteId

package object event_handlers {

  //constants
  val EVENT_TYPE = "type"
  val EVENT_DATA = "data"
  val DATA_FIELD_REQUEST_SOURCE = "request_source"

  //topics (TODO: name/pattern to be finalized/corrected)
  val TOPIC_ENDORSER_REGISTRY = "endorser-registry"
  val TOPIC_ENDORSEMENT_REQ_STATUS = "endorsement-request-status"

  //event types (TODO: name/pattern to be finalized/corrected)
  val TYPE_ENDORSER_ACTIVE = "endorser_active"
  val TYPE_ENDORSER_INACTIVE = "endorser_inactive"
  val TYPE_ENDORSEMENT_COMPLETE = "endorsement_complete"

}


case class RequestSource(route: RouteId, pinstIdPair: PinstIdPair)