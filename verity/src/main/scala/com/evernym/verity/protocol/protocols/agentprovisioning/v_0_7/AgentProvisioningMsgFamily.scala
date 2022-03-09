package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_AGENT_CREATED
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.{Control, SponsorRel}
import com.evernym.verity.protocol.engine.Constants.{MFV_0_7, MSG_FAMILY_AGENT_PROVISIONING, MSG_TYPE_CREATE_AGENT}
import com.evernym.verity.protocol.engine.util.DbcUtil.requireNotNull
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.TimeUtil._
import com.typesafe.config.{ConfigObject, ConfigRenderOptions}

import scala.jdk.CollectionConverters._
import scala.concurrent.duration.Duration
import scala.util.Try

object AgentProvisioningMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_AGENT_PROVISIONING
  override val version: MsgFamilyVersion = MFV_0_7

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    MSG_TYPE_CREATE_AGENT  -> classOf[CreateCloudAgent],
    "create-edge-agent"    -> classOf[CreateEdgeAgent],
    MSG_TYPE_AGENT_CREATED -> classOf[AgentCreated],
    "problem-report"       -> classOf[ProblemReport]
  )
  override val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    "provision"                   -> classOf[ProvisionCloudAgent],
    "provision-edge"              -> classOf[ProvisionEdgeAgent],
    "give-sponsor-details"        -> classOf[GiveSponsorDetails],
    "no-sponsor-needed"           -> classOf[NoSponsorNeeded],
    "needs-token"                 -> classOf[InvalidToken],
    "already-provisioned"         -> classOf[AlreadyProvisioned],
    "complete-agent-provisioning" -> classOf[CompleteAgentProvisioning]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[IdentifySponsor]    -> "sponsor-needed",
    classOf[NeedsCloudAgent]    -> "provisioning-needed",
    classOf[NeedsEdgeAgent]     -> "needs-edge-agent"
  )

  sealed trait Role
  object Requester extends Role
  object Provisioner extends Role

  /**
    * Common Types
    */
  trait ProvisioningToken {
    def sponseeId: String
    def sponsorId: String
    def nonce: Nonce
    def timestamp: IsoDateTime
    def sig: Base64Encoded
    def sponsorVerKey: VerKeyStr
  }
/**
  *
  * @param sponseeId   An identifier a Sponsor uses to identify/reference the sponsee i.g. id used in the sponsor’s back end database.
  *
  * @param sponsorId   Identifier established when Sponsor registers with Evernym TE - persistent id for sponsor
  *
  * @param nonce      Randomly generated string
  *
  * @param timestamp  An RFC 3339 and ISO 8601 date and time string such as `1996-12-19T16:39:57-08:00`.
  *
  * @param sig        The sponsor signs the timeStamp + the nonce + sponseeId + sponsorId and encodes it to a base64 encoded string.
  *                   The Sponsor uses the signing keys associated with the verity registered verkey to perform the signature.
  *                   Ex. Base64Encode(Sign(nonce + timestamp + id))
  *
  * @param sponsorVerKey Sponsor’s verkey associated with the signing keys.
*/
  case class ProvisionToken(sponseeId: String,
                            sponsorId: String,
                            nonce: Nonce,
                            timestamp: IsoDateTime,
                            sig: Base64Encoded,
                            sponsorVerKey: VerKeyStr) extends ProvisioningToken {
    def asEvent(): TokenDetails = TokenDetails(
      requireNotNull(sponseeId, "sponseeId"),
      requireNotNull(sponsorId, "sponsorId"),
      requireNotNull(nonce, "nonce"),
      requireNotNull(timestamp, "timestamp"),
      requireNotNull(sig, "sig"),
      requireNotNull(sponsorVerKey, "sponsorVerKey")
    )
  }
  object ProvisionToken {
    def apply(s: TokenDetails): ProvisionToken =
      ProvisionToken(s.sponseeId, s.sponsorId, s.nonce, s.timestamp, s.sig, s.sponsorVerKey)
  }

  case class Keys(verKey: VerKeyStr)
  case class SponsorDetails(name: String,
                            id: String,
                            keys: List[Keys],
                            endpoint: String,
                            active: Boolean=false,
                            pushService: Option[SponsorPushService] = None,
                            pushMsgOverrides: String = "{}")
  case class SponsorPushService(service: String, host: String, path: String, key: String)
  object SponsorDetails {
    def apply(details: ConfigObject): SponsorDetails = {
      val config = details.toConfig
      val push = Try{
        val serviceConfig = config.getObject("push-service").toConfig
        SponsorPushService(
          serviceConfig.getString("service"),
          serviceConfig.getString("host"),
          serviceConfig.getString("path"),
          serviceConfig.getString("key"),
        )
      }.toOption

      val pushMsgOverrides = Try{
        config.getConfig("push-msg-overrides").root().render(ConfigRenderOptions.concise())
      }.getOrElse("{}")

      SponsorDetails(
        config.getString("name"),
        config.getString("id"),
        config.getObjectList("keys").asScala.toList.map(x => Keys(x.toConfig.getString("verKey"))),
        config.getString("endpoint"),
        config.getBoolean("active"),
        push,
        pushMsgOverrides
      )
    }
  }

  /**
    * Messages used in this protocol for Agent Provisioning
    * Types of messages are from the perspective of the 'sender' of the message
    */
  sealed trait Msg extends MsgBase
  case class RequesterKeys(fromDID: DidStr, fromVerKey: VerKeyStr) {
    def asEvent(): RequesterKeysOpt = RequesterKeysOpt(fromDID, fromVerKey)
  }
  object RequesterKeys {
    def apply(keys: Option[RequesterKeysOpt]): Option[RequesterKeys] =
      keys.map(x => RequesterKeys(x.did, x.verKey))
  }

  case class CreateCloudAgent(requesterKeys: RequesterKeys,
                              provisionToken: Option[ProvisionToken]) extends Msg
  case class CreateEdgeAgent(requesterVk: VerKeyStr,
                             provisionToken: Option[ProvisionToken])  extends Msg
  case class AgentCreated(selfDID: DidStr, agentVerKey: VerKeyStr)          extends Msg
  case class ProblemReport(msg: String=DefaultProblem.err)            extends Msg

  /**
    * Control messages
    */
  sealed trait Ctl extends Control with MsgBase
  trait Provision extends Ctl {
    def asCreateAgent(): Msg
    def provisionToken(): Option[ProvisionToken]
  }
  case class ProvisionCloudAgent(requesterKeys: RequesterKeys,
                                 provisionToken: Option[ProvisionToken])                                   extends Provision {
    def asCreateAgent(): CreateCloudAgent = CreateCloudAgent(requesterKeys, provisionToken)
  }

  case class ProvisionEdgeAgent(requesterVk: VerKeyStr, provisionToken: Option[ProvisionToken])               extends Provision {
    def asCreateAgent(): CreateEdgeAgent = CreateEdgeAgent(requesterVk, provisionToken)
  }

  case class GiveSponsorDetails(sponsor: Option[SponsorDetails], cacheUsedTokens: Boolean,
                                tokenWindow: Duration)                    extends Ctl
  case class InvalidToken()                                               extends Ctl
  case class AlreadyProvisioned(requesterVerKey: VerKeyStr)                  extends Ctl
  case class NoSponsorNeeded()                                            extends Ctl
  case class CompleteAgentProvisioning(selfDID: DidStr, agentVerKey: VerKeyStr) extends Ctl

  /**
    * Errors
    */
  trait ProvisioningException   extends Exception {
    def err: String
  }
  case object NoSponsor               extends ProvisioningException {
    def err = "Sponsor not found - possible sponsor deactivation"
  }
  case object SponsorInactive        extends ProvisioningException {
    def err = "Sponsor has an inactive status - cannot provision"
  }
  case object ProvisionTimeout        extends ProvisioningException {
    def err = "Timestamp provided has expired - possibly get new token from sponsor"
  }
  case object DuplicateProvisionedApp extends ProvisioningException {
    def err = "App has already provisioned for this token"
  }
  case object InvalidSignature        extends ProvisioningException {
    def err = "Signature is invalid - ex. Base64Encode(Sign(nonce + timestamp + sponseeId + sponsorId))"
  }
  case object InvalidSponsorVerKey    extends ProvisioningException {
    def err = "Token contains verkey which doesn't match sponsor's information"
  }
  case object InvalidTokenErr extends ProvisioningException {
    def err = "Token is invalid"
  }
  case object MissingToken extends ProvisioningException {
    def err = "Token is required for provisioning"
  }
  case object DefaultProblem extends ProvisioningException {
    def err = "Error creating agent"
  }
  case object AlreadyProvisionedProblem extends ProvisioningException {
    def err = "Already provisioned for given key"
  }

  /**
    * Driver Messages
    */
  sealed trait Signal
  sealed trait ProvisioningNeeded {
    def sponsorRel: Option[SponsorRel]
  }
  case class IdentifySponsor(provisionDetails: Option[ProvisionToken])
    extends Signal

  case class NeedsCloudAgent(requesterKeys: RequesterKeys,
                             sponsorRel: Option[SponsorRel]=None)
    extends Signal with ProvisioningNeeded

  case class NeedsEdgeAgent(requesterVk: VerKeyStr,
                            sponsorRel: Option[SponsorRel]=None)
    extends Signal with ProvisioningNeeded
}
