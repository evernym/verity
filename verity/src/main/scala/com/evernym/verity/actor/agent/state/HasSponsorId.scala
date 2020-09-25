package com.evernym.verity.actor.agent.state


/**
 * A trait meant to be mixed into the state object of an agent
 *
 * optional sponsor id (associated with agent provisioning)
 */
trait OptSponsorId {
  private var _sponsorId: Option[String] = None
  def sponsorId: Option[String] = _sponsorId
  def setSponsorId(id: String): Unit = {
    _sponsorId match {
      case None => _sponsorId = Option(id)
      case _    => throw new UnsupportedOperationException("sponsor id can't be overridden")
    }
  }
}

trait HasSponsorId {
  type StateType <: OptSponsorId
  def state: StateType
}
