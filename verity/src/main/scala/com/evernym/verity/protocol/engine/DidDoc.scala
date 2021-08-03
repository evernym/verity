package com.evernym.verity.protocol.engine

import com.evernym.verity.util2.ServiceEndpoint
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.did.methods.DIDKey


//TODO: this should be reconciled with existing DidDoc in Relationship

object DidDocConstants {
  final val DID_CONTEXT = "https://w3id.org/did/v1"
}

// This is the according to the community.
case class PublicKeyFormatted(id: String, `type`: String = "Ed25519VerificationKey2018", controller: String, publicKeyBase58: VerKeyStr)

case class ServiceFormatted(id: String, `type`: String, recipientKeys: Vector[VerKeyStr], routingKeys: Option[Vector[VerKeyStr]], serviceEndpoint: String){
  def routingKeys_! : Vector[VerKeyStr] = routingKeys.getOrElse(Vector.empty)
}

case class ServiceFormatter(service: ServiceFormatted) {
  val recipientKeys : Vector[DidStr] = for (key <- service.recipientKeys) yield new DIDKey(key).toString
  val routingKeys : Vector[DidStr] = for (key <- service.routingKeys.getOrElse(Vector.empty)) yield new DIDKey(key).toString
  def toDidKeyFormat(): ServiceFormatted = ServiceFormatted(service.id, service.`type`, recipientKeys, Some(routingKeys), service.serviceEndpoint)
}

case class DIDDocFormatted(`@context`: String = DidDocConstants.DID_CONTEXT, id: DidStr, publicKey: Vector[PublicKeyFormatted], service: Vector[ServiceFormatted]) {
  def toDIDDoc: DIDDoc = {
    if (publicKey.isEmpty) {
      throw new RuntimeException("publicKey should not be empty")
    }
    val serviceEntry = service.headOption.getOrElse(throw new RuntimeException("at least one service is required"))
    val endpoint = serviceEntry.serviceEndpoint
    val routingKeys = serviceEntry.routingKeys_!.map { rk =>
      val (_, index) = {
        val splitted = rk.split("#")
        (splitted.head, splitted.tail.lastOption.map(_.toInt))
      }
      index match {
        case Some(idx) => publicKey(idx-1).publicKeyBase58
        case None      => rk
      }
    }
    DIDDoc(id, publicKey(0).publicKeyBase58, endpoint, routingKeys)
  }
}

case class DIDDoc(id: DidStr, verkey: VerKeyStr, endpoint: ServiceEndpoint, routingKeys: Vector[VerKeyStr]) {
  def getDID: DidStr = id
  def getVerkey: VerKeyStr = verkey
  def getEndpoint: ServiceEndpoint = endpoint
  def toDIDDocFormatted: DIDDocFormatted = {
    // only one verkey for now
    val publicKey = Vector(PublicKeyFormatted(id = s"$getDID#keys-1", controller = getDID, publicKeyBase58 = getVerkey))
    //TODO-aries-interop: what is the right service type below (IndyAgent?)
    val service = Vector(ServiceFormatted(s"$id;indy", "IndyAgent", Vector(verkey), Option(routingKeys), getEndpoint))
    DIDDocFormatted(publicKey = publicKey, id = getDID, service = service)
  }
}
