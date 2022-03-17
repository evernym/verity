package com.evernym.verity.event_bus


package object event_handlers {

  //constants
  val CLOUD_EVENT_TYPE = "type"
  val CLOUD_EVENT_DATA = "data"
  val CLOUD_EVENT_DATA_FIELD_REQUEST_SOURCE = "request_source"


  //TODO: below names may need to be finalized/corrected

  //SSI ENDORSEMENT REQ topic and related events (published by verity and consumed by endorser service)
  val TOPIC_SSI_ENDORSEMENT_REQ = "event.ssi.endorsement_request"
  val EVENT_ENDORSEMENT_REQ = "endorsement.endorsement-request"

  //SSI ENDORSEMENT topic and related events (published by endorser service and consumed by verity service)
  val TOPIC_SSI_ENDORSEMENT = "event.ssi.endorsement"
  val EVENT_ENDORSEMENT_COMPLETE = "endorsement.endorsement-complete"

  //SSI ENDORSER topic and related events (published by endorser service and consumed by verity service)
  val TOPIC_SSI_ENDORSER = "event.ssi.endorser"
  val EVENT_ENDORSER_ACTIVATED = "endorser.endorser-activated"
  val EVENT_ENDORSER_DEACTIVATED = "endorser.endorser-deactivated"

}
