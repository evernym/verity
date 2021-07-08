package com.evernym.verity.actor.agent.agency

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.msgrouter.PackedMsgRouteParam
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_FAMILY_ROUTING, MSG_TYPE_FORWARD, MSG_TYPE_FWD}
import com.evernym.verity.agentmsg.msgfamily.routing.{FwdMsgHelper, FwdReqMsg}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants.{MFV_0_5, MFV_1_0, MSG_FAMILY_NAME_0_5, MTV_1_0}
import com.evernym.verity.protocol.engine.MsgFamily.{COMMUNITY_QUALIFIER, EVERNYM_QUALIFIER}
import com.evernym.verity.util.{PackedMsgWrapper, ReqMsgContext, Util}

import scala.concurrent.Future
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.wallet.PackedMsg

/**
 * handles incoming packed (anon crypted) messages
 */
trait AgencyPackedMsgHandler extends ResourceUsageCommon {

  def agentActorContext: AgentActorContext

  def handleUnpackedMsg(pmw: PackedMsgWrapper)(implicit amw: AgentMsgWrapper): Future[Any] = {

    // This is the function that ultimately gets called to do the work. The rest of
    // processSealedMsg just figures out what parameters to give, and what context to pass to it.
    def handleFwdMsg(fwdMsg: FwdReqMsg)(implicit reqMsgContext: ReqMsgContext): Future[Any] = {
      // flow diagram: fwd + ctl + proto + legacy, step 4 -- Called after seeing "Forward" in plaintext.
      agentActorContext.agentMsgRouter.execute(
        PackedMsgRouteParam(fwdMsg.`@fwd`, PackedMsg(fwdMsg.`@msg`), reqMsgContext))
    }

    implicit val rmc: ReqMsgContext = pmw.reqMsgContext.withMsgPackFormat(amw.msgPackFormat)

    addUserResourceUsage(RESOURCE_TYPE_ENDPOINT,
      "POST_agency_msg", rmc.clientIpAddressReq, None)
    amw.headAgentMsgDetail match {
      // TODO: we need to support another possible qualifier, "http://didcomm.org/".
      // See https://github.com/hyperledger/aries-rfcs/blob/master/features/0348-transition-msg-type-to-https/README.md.
      // This is a tech debt that will quickly make us fail to be interoperable with
      // the community; they are poised to begin step 2 as of July 2020.
      case MsgFamilyDetail(EVERNYM_QUALIFIER | COMMUNITY_QUALIFIER, MSG_FAMILY_NAME_0_5, MFV_0_5, MSG_TYPE_FWD, Some(MTV_1_0), _) =>
        handleFwdMsg(FwdMsgHelper.buildReqMsg)
      // TODO: It looks to me like we may be routing incorrectly here. We are expecting an exact
      // match for a message family version (the string "1.0"), instead of using semantic versioning
      // rules where we route to the nearest handler with a semantically compatible version less than
      // or equal to the one we support.
      case MsgFamilyDetail(EVERNYM_QUALIFIER | COMMUNITY_QUALIFIER, MSG_FAMILY_ROUTING, MFV_1_0, MSG_TYPE_FWD | MSG_TYPE_FORWARD, _, _) =>
        handleFwdMsg(FwdMsgHelper.buildReqMsg)

      case MsgFamilyDetail(EVERNYM_QUALIFIER | COMMUNITY_QUALIFIER, MSG_FAMILY_NAME_0_5, MFV_0_5, MSG_TYPE_FWD, Some(_), _) =>
        Future.failed(Util.handleUnsupportedMsgType(amw.headAgentMsgDetail.getTypeDetail.toString))
      case _ =>
        Future.failed(new BadRequestErrorException(UNSUPPORTED_MSG_TYPE.statusCode,
          Option(amw.headAgentMsg.msgFamilyDetail.getTypeDetail.toString)))
    }
  }
}
