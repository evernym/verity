package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor
import com.evernym.verity.protocol.engine.{DID, DIDDoc, ProtocolContextApi, ServiceFormatted, VerKey}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.util.OptionUtil.toTry

import scala.util.Try

object InviteUtil {
  def buildServiced(agencyVerKey: Option[VerKey], ctx: ProtocolContextApi[_, _, _, _, _, _]): Try[Vector[ServiceFormatted]] = {
    for(
      agencyVerkey    <- toTry(agencyVerKey);
      did             <- toTry(ctx.getRoster.selfId);
      verkey          <- ctx.wallet.verKey(did);
      serviceEndpoint <- Try(ctx.serviceEndpoint);
      routingKeys <- Try(Vector(verkey, agencyVerkey))
    ) yield DIDDoc(
      did,
      verkey,
      serviceEndpoint,
      routingKeys)
      .toDIDDocFormatted
      .service
  }

  def buildInvite(
                   agentName: Option[String],
                   logoUrl: Option[String],
                   publicDid: Option[DID],
                   service: Try[Vector[ServiceFormatted]],
                   attachment: Try[AttachmentDescriptor]): Try[OutOfBandInvitation] = {
    for(
      service         <- service;
      offerAttachment <- attachment
    ) yield OutOfBandInvitation(
      agentName.getOrElse(""),
      "issue-vc",
      "To issue a credential",
      Vector(offerAttachment),
      service,
      logoUrl,
      publicDid.map(s"did:sov:"+_)
    )
  }

}
