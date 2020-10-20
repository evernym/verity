//package com.evernym.verity.actor.agent.state
//
//import com.evernym.verity.actor.agent.agency.SponsorRel
//
//
///**
// * A trait meant to be mixed into the state object of an agent
// *
// * optional sponsor id (associated with agent provisioning)
// */
//trait OptHasSponsorRel {
//  private var _sponsorRel: Option[SponsorRel] = None
//  def sponsorRel: Option[SponsorRel] = _sponsorRel
//  def setSponsorRel(rel: SponsorRel): Unit = {
//    _sponsorRel = _sponsorRel match {
//      case None => Some(rel)
//      case Some(x) => throw new UnsupportedOperationException(s"sponsor relationship can't be overridden: $x")
//    }
//  }
//}
//
//trait HasSponsorRel {
//  type StateType <: OptHasSponsorRel
//  def state: StateType
//}
