package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.wallet.VerKeyResult
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.util.{DIDDoc, ServiceFormatted, ServiceFormatter}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.util.Base58Util

import scala.util.{Failure, Success, Try}

object InviteUtil {
  def withServiced(agencyVerKey: Option[VerKeyStr], ctx: ProtocolContextApi[_, _, _, _, _, _])
                  (handler: Try[Vector[ServiceFormatted]] =>Unit): Unit = {
    (agencyVerKey, ctx.getRoster.selfId) match {
      case (Some(agencyVerKey), Some(did)) =>
        ctx.wallet.verKey(did) {
          case Success(VerKeyResult(verKey: VerKeyStr)) =>
            handler(Success(
              DIDDoc(
                did,
                verKey,
                ctx.serviceEndpoint,
                Vector(verKey, agencyVerKey)
              ).toDIDDocFormatted
                .service
            ))
          case Failure(ex) =>
            handler(Failure(ex))
        }
      case (None, _) =>
        handler(Failure(new Exception("no agency verKey")))
      case _ =>
        handler(Failure(new Exception("no self id")))
    }
  }

  def buildInviteWithThreadedId(protoRef: ProtoRef,
                                relationshipId: DidStr,
                                threadId: ThreadId,
                                agentName: Option[String],
                                logoUrl: Option[String],
                                publicDid: Option[DidStr],
                                service: Vector[ServiceFormatted],
                                attachment: AttachmentDescriptor,
                                goalCode: Option[String],
                                goal: Option[String],
                                serviceKeyDidFormat: Boolean
                               ): OutOfBandInvitation = {
    val id = buildThreadedInviteId(protoRef, relationshipId, threadId)
    OutOfBandInvitation(
      agentName.getOrElse(""),
      goalCode,
      goal,
      Vector(attachment),
      if (serviceKeyDidFormat) for (s <- service) yield ServiceFormatter(s).toDidKeyFormat() else service,
      logoUrl,
      publicDid.map("did:sov:"+_),
      id
    )
  }


  case class ThreadedInviteIdDecoded(ver: String, protoRefStr: String, relationshipId: DidStr, threadId: ThreadId)
  val delimiter = '$'
  val encodeVer = "v1"
  /*
  Builds an encoded ID for invites that contain encoded data about the started protocol for the invite. The idea is
  for the this encoded data to be opaque but the data is not a secret.
   */
  def buildThreadedInviteId(protoRef: ProtoRef,
                            relationshipId: DidStr,
                            threadId: ThreadId): String = {
    val preCodedId = s"$encodeVer$delimiter$protoRef$delimiter$relationshipId$delimiter$threadId"
    val encoded = Base58Util.encode(preCodedId.getBytes())
    s"${encoded.substring(0, 7)}-${encoded.substring(7, 16)}-${encoded.substring(16, 25)}-${encoded.substring(25, 34)}-${encoded.substring(34)}"
  }

  def isThreadedInviteId(id: String): Boolean = {
    id.charAt(7) == '-' &&
      Base58Util.decode(id.replace("-", ""))
        .map(new String(_))
        .map(_.startsWith(encodeVer+delimiter))
        .getOrElse(false)
  }

  def parseThreadedInviteId(id: String): Try[ThreadedInviteIdDecoded] = {
    Try(id)
      .flatMap{ x =>
        if (x.charAt(7) == '-')
          Success(x)
        else
          Failure(new Exception("id is not a ThreadedInviteId"))
      }
      .map(_.replace("-", ""))
      .flatMap(Base58Util.decode)
      .map(new String(_))
      .map(_.split(delimiter))
      .flatMap{ x =>
        if (x.length == 4)
          Success(x)
        else
          Failure(new Exception("Unable to extract 4 elements from ThreadedInviteId"))
      }
      .map{x =>
        ThreadedInviteIdDecoded(x(0), x(1), x(2), x(3))
      }
  }

}
