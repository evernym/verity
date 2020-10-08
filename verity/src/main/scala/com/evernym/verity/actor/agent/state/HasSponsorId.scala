package com.evernym.verity.actor.agent.state


/**
 * A trait meant to be mixed into the state object of an agent
 *
 * optional sponsor id (associated with agent provisioning)
 */
trait OptSponsorId {
  private var _sponseeId: Option[String] = None
  def sponseeId: Option[String] = _sponseeId
  def setSponseeId(id: String): Unit =
    _sponseeId = validateId(id, _sponseeId)

  private var _sponsorId: Option[String] = None
  def sponsorId: Option[String] = _sponsorId
  def setSponsorId(id: String): Unit =
    _sponsorId = validateId(id, _sponsorId)

  def validateId(id: String, pVal: Option[String]): Option[String] =
    pVal match {
      case None => if (id == "") None else Some(id)
      case _    => throw new UnsupportedOperationException("id can't be overridden")
    }
}

trait HasSponsorId {
  type StateType <: OptSponsorId
  def state: StateType
}
