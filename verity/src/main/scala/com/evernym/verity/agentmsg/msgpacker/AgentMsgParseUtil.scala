package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.constants.Constants.`@MSG`
import com.evernym.verity.util2.Exceptions.MissingReqFieldException
import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.actor.wallet.UnpackedMsg
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.MsgCodecException
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.pairwise.CreateMsgReqMsg_MFV_0_5
import com.evernym.verity.agentmsg.msgfamily.{BundledMsg_MFV_0_5, LegacyTypedMsg}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, VALID_MESSAGE_TYPE_REG_EX_DID, VALID_MESSAGE_TYPE_REG_EX_HTTP, msgQualifierFromQualifierStr}
import com.evernym.verity.protocol.engine.{MissingReqFieldProtocolEngineException, MsgBase}
import com.evernym.verity.util.MessagePackUtil
import org.json.JSONObject

import scala.reflect.ClassTag

object AgentMsgParseUtil {

  /**
   * extract message family detail from given agent json string message
   * @param jsonString agent json message
   * @return
   */
  def msgFamilyDetail(jsonString: String): MsgFamilyDetail = {
    val jsonFields = convertTo[Map[String, Any]](jsonString)
    val map = jsonFields.map(e => e._1 -> Option(e._2).map(_.toString).orNull)
    val msgTypeString = map.get(`@TYPE`)
    msgTypeString match {
      case Some(VALID_MESSAGE_TYPE_REG_EX_DID(_, msgQualifier, msgFamily, msgFamilyVersion, msgType)) =>
        MsgFamilyDetail(msgQualifierFromQualifierStr(msgQualifier), msgFamily, msgFamilyVersion, msgType, None)
      case Some(VALID_MESSAGE_TYPE_REG_EX_HTTP(_, msgQualifier, msgFamily, msgFamilyVersion, msgType)) =>
        MsgFamilyDetail(msgQualifierFromQualifierStr(msgQualifier), msgFamily, msgFamilyVersion, msgType, None)
      case _ => buildMsgFamilyDetailForLegacyMsgs(jsonString)
    }
  }

  /**
   * prepares AgentMsg from given json message
   * @param jsonMsg agent json message
   * @param family optional, message family detail
   * @return
   */
  def agentMsg(jsonMsg: String, family: Option[MsgFamilyDetail] = None): AgentMsg = {
    val mfd = family.getOrElse(msgFamilyDetail(jsonMsg))
    val mtfv = if (mfd.isLegacyMsg) {
      TypeFormat.LEGACY_TYPE_FORMAT
    } else {
      TypeFormat.STANDARD_TYPE_FORMAT
    }
    AgentMsg(jsonMsg, mfd, mtfv)
  }

  def parse(unpackedMsg: UnpackedMsg, parseParam: ParseParam=ParseParam()): AgentBundledMsg = {
    val extractedMsg = extractMsg(unpackedMsg.msgString, parseParam)
    val msgFamilyDetail = extractedMsg.msgFamilyDetail
    val (parsedMsgs, usesLegacyBundledMsgs) = {
      if (parseParam.parseBundledMsgs && msgFamilyDetail.msgName == BUNDLED
        && msgFamilyDetail.familyVersion == MFV_0_5) {
        val bundledMsgs = convertTo[BundledMsg_MFV_0_5](extractedMsg.jsonStr)
        val unpackedMsgs = bundledMsgs.bundled.map { bm =>
          MessagePackUtil.convertPackedMsgToJsonString(bm)
        }
        (unpackedMsgs.map(agentMsg(_, None)), true)
      } else {
        (List(extractedMsg.jsonStr).map(agentMsg(_, Some(msgFamilyDetail))), false)
      }
    }
    AgentBundledMsg(
      parsedMsgs,
      unpackedMsg.senderVerKey,
      unpackedMsg.recipVerKey,
      if (extractedMsg.hadLegacyGenMsgWrapper) Some(extractedMsg.msgFamilyDetail) else None,
      extractedMsg.hadLegacyGenMsgWrapper,
      usesLegacyBundledMsgs
    )
  }

  def convertTo[T: ClassTag](msg: String): T = {
    val nm = DefaultMsgCodec.fromJson[T](msg)
    //TODO: may wanna find any better solution than how we are validating the native msg below
    nm match {
      case mb: MsgBase => mb.validate()
      case _ =>
    }
    nm
  }

  //Note: this is a workaround to handle CREATE_MSG little better in user agent pairwise
  // (mostly it will be there till we support A2A 1.0)
  private def buildMsgFamilyDetailForLegacyMsgs(jsonString: String): MsgFamilyDetail = {
    try {
      val typedMsg = convertTo[LegacyTypedMsg](jsonString)
      val typeDetail = typedMsg.`@type`
      val (familyQualifier, familyName, familyVersion, msgName) = (typeDetail.name, typeDetail.ver) match {
        case (MSG_TYPE_CREATE_MSG, MTV_1_0) =>
          val createMsgReq = convertTo[CreateMsgReqMsg_MFV_0_5](jsonString)
          val (msgFamilyName, msgFamilyVer, msgName) = createMsgReq.mtype match {
            case CREATE_MSG_TYPE_CONN_REQ | CREATE_MSG_TYPE_CONN_REQ_ANSWER |
                 CREATE_MSG_TYPE_REDIRECT_CONN_REQ | CREATE_MSG_TYPE_CONN_REQ_REDIRECTED =>
              (MSG_FAMILY_CONNECTING, MFV_0_5, createMsgReq.mtype)
            case _ =>
              (MSG_FAMILY_NAME_0_5, MFV_0_5, CREATE_MSG_TYPE_GENERAL)
          }
          (EVERNYM_QUALIFIER, msgFamilyName, msgFamilyVer, msgName)

        case (MSG_TYPE_CONNECT | MSG_TYPE_SIGN_UP | MSG_TYPE_CREATE_AGENT, MTV_1_0) =>
          (EVERNYM_QUALIFIER, MSG_FAMILY_AGENT_PROVISIONING, MFV_0_5, typeDetail.name)

        case (_, MTV_1_0) =>
          (EVERNYM_QUALIFIER, MSG_FAMILY_NAME_0_5, MFV_0_5, typeDetail.name) // TODO rajesh is this really what we want?
        case (_, _) =>
          (EVERNYM_QUALIFIER, MSG_FAMILY_NAME_0_5, typeDetail.ver, typeDetail.name)
      }
      MsgFamilyDetail(familyQualifier, familyName, familyVersion, msgName, Option(typeDetail.ver), isLegacyMsg = true)
    } catch {
      case _ @ ( _: MissingReqFieldException | _: MsgCodecException | _: MissingReqFieldProtocolEngineException) =>
        convertTo[BundledMsg_MFV_0_5](jsonString)
        MsgFamilyDetail(EVERNYM_QUALIFIER, MSG_FAMILY_NAME_0_5, MFV_0_5, BUNDLED, Option(MFV_0_5), isLegacyMsg = true)
    }
  }

  private def extractMsg(msgString: String, parseParam: ParseParam): ExtractedMsg = {
    //NOTE: there is a special scenario wherein if 'deployed agent service' is acting as an edge agent (verity application server)
    // and if it receives a msg (from other participant)
    // which was packed for it, by using existing libvcx msg pack api,
    // it generates agent msg wrapper with actual msg inside '@msg' attribute (an example is given below)
    // which doesn't seem to be right.
    // until we solve that problem, we'll have to make sure during msg parsing, it uses the correct msg
    // instead of the outer wrapper msg.
    // example wrapper msg:
    // {
    //    "@type":{"name":"MESSAGE","ver":"1.0","fmt":"json"},
    //    "@msg":"{
    //      \"@type\":\"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/OFFER\",
    //      \"@id\":\"0404bd2d-26a5-43ed-b2c6-5810ce28a81d\"
    //     }"
    // }


    val family = msgFamilyDetail(msgString)
    val insideMsgJsonObjectOpt = try {
      Option(new JSONObject(msgString))
    } catch {
      case _: RuntimeException =>
        None
    }

    insideMsgJsonObjectOpt match {
      case Some(insideMsgJsonObject) if insideMsgJsonObject.has(`@MSG`) && parseParam.useInsideMsgIfPresent &&
        family.familyName != MSG_FAMILY_ROUTING &&
        family.msgName != MSG_TYPE_SEND_REMOTE_MSG =>

        val insideMsg = insideMsgJsonObject.get(`@MSG`).toString
        val familyForInsideMsg = try {
          //if inside msg also have @type, use that to figure out exact msg family detail
          //this case is required for mobile protocols where the incoming msg looks like this:
          //{"@type":{"ver":"1.0","name":"MESSAGE"},"@msg":{"@type":"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/OFFER","@id":"111"}}
          //(to run that test: sbt "verity/multi-jvm:testOnly com.evernym.verity.integration.TicTacToe")
          msgFamilyDetail(insideMsg)
        } catch {
          case _ @ (_:MsgCodecException | _: MissingReqFieldException | _: MissingReqFieldProtocolEngineException) =>
            // 'insideMsg' is not self describing, use wrapping MsgFamily
            family
        }
        ExtractedMsg(insideMsg, familyForInsideMsg, hadLegacyGenMsgWrapper = true)
      case _ =>
        ExtractedMsg(msgString, family, hadLegacyGenMsgWrapper = false)
    }
  }

}

case class ExtractedMsg(jsonStr: String,
                        msgFamilyDetail: MsgFamilyDetail,
                        hadLegacyGenMsgWrapper: Boolean)
