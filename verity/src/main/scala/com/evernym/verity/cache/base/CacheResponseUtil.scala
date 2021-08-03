package com.evernym.verity.cache.base

import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InternalServerErrorException, NotFoundErrorException}
import com.evernym.verity.util2.Status.{AGENT_NOT_YET_CREATED, DATA_NOT_FOUND, UNHANDLED}
import com.evernym.verity.actor.agent.agency.AgencyInfo
import com.evernym.verity.actor.agent.msgrouter.ActorAddressDetail
import com.evernym.verity.actor.agent.user.AgentConfigs
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.DidStr

trait CacheResponseUtil {

  def data: Map[String, Any]

  def get[T](key: String): Option[T] = data.get(key).map(_.asInstanceOf[T])

  def getReq[T](key: String): T = get(key).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("value not found for given key" + key)))

  def getConfigs: AgentConfigs = AgentConfigs(data.map(e => ConfigDetail(e._1, e._2.toString)).toSet)

  def getAgencyInfoOpt(forDID: DidStr): Option[AgencyInfo] = {
    data.get(forDID) match {
      case Some(ad: AgencyInfo) => Some(ad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyInfoReq(forDID: DidStr): AgencyInfo = {
    getAgencyInfoOpt(forDID).getOrElse(
      throw new InternalServerErrorException(UNHANDLED.statusCode, Option("agency info not found DID: " + forDID)))
  }

  def getActorAddressDetailOpt(forDID: DidStr): Option[ActorAddressDetail] = {
    data.get(forDID) match {
      case Some(aad: ActorAddressDetail) => Some(aad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyDIDOpt: Option[DidStr] = {
    data.get(AGENCY_DID_KEY) match {
      case Some(ad: String) => Some(ad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyDIDReq: String = {
    getAgencyDIDOpt match {
      case Some(ad: String) => ad
      case None => throw new NotFoundErrorException(AGENT_NOT_YET_CREATED.statusCode, Option(AGENT_NOT_YET_CREATED.statusMsg))
    }
  }

}

