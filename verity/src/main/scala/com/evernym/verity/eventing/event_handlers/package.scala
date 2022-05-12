package com.evernym.verity.eventing


package object event_handlers {

  //constants
  val CLOUD_EVENT_TYPE = "type"
  val CLOUD_EVENT_DATA = "data"
  val DATA_FIELD_REQUEST_SOURCE = "requestsource"


  //TODO: below names may need to be finalized/corrected

  //SSI ENDORSEMENT REQ topic and related events (published by verity and consumed by endorser service)
  val TOPIC_REQUEST_ENDORSEMENT = "public.request.endorsement"
  val EVENT_ENDORSEMENT_REQ_V1 = "request.endorsement.v1"

  //SSI ENDORSEMENT topic and related events (published by endorser service and consumed by verity service)
  val TOPIC_SSI_ENDORSEMENT = "public.event.ssi.endorsement"
  val EVENT_ENDORSEMENT_COMPLETE_V1 = "endorsement.endorsement-complete.v1"

  //SSI ENDORSER topic and related events (published by endorser service and consumed by verity service)
  val TOPIC_SSI_ENDORSER = "public.event.ssi.endorser"
  val EVENT_ENDORSER_ACTIVATED_V1 = "endorser.endorser-activated.v1"
  val EVENT_ENDORSER_DEACTIVATED_V1 = "endorser.endorser-deactivated.v1"

}
