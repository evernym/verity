package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.protocol._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{InitParamBase, Protocol, ProtocolContextApi, Roster}
import com.evernym.verity.protocol.protocols.deaddrop.Role.Empty
import com.evernym.verity.util.Base64Util.{getBase64Decoded, getBase64Encoded}
import com.google.protobuf.ByteString
import org.apache.commons.codec.digest.DigestUtils
import org.hyperledger.indy.sdk.crypto.Crypto

import scala.util.{Failure, Success}


class DeadDropProtocol(val ctx: ProtocolContextApi[DeadDropProtocol, Role, DeadDropProtoMsg, DeadDropEvt, DeadDropState, String])
  extends Protocol[DeadDropProtocol, Role, DeadDropProtoMsg, DeadDropEvt, DeadDropState, String](DeadDropProtoDef)
{

  def initialize(params: Seq[InitParamEvt]): Roster[Role] = {
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  def setRole(selfRole: Role): Roster[Role] = {

    val assignments = {
      val self = Map(selfRole -> ctx.getRoster.selfIndex_!)
      val other = selfRole match {
        case Persister() => Map.empty
        case Retriever() => Map(Persister() -> ctx.getRoster.otherIndex())
        case Empty => Map.empty
        case _: RoleMessage => Map.empty // This a type possibility but would not make sense here
      }
      self ++ other
    }
    ctx.getRoster.withAssignment(assignments.toSeq:_*)
  }

  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  def mainHandleControl: (DeadDropState, Control) ?=> Any = {
    case (_: DeadDropState.Uninitialized, Init(params)) =>
      ctx.apply(Initialized(params.initParams.map(p => InitParamEvt(p.name, p.value)).toSeq))

      //TODO: will this be a control or protocol message?
    case (_: DeadDropState.Initialized, sd: StoreData) =>
      ctx.apply(RoleSet(Persister()))
      storeData(sd)

    case (_: DeadDropState.Ready, sd: StoreData) =>
      storeData(sd)

    case (_: DeadDropState.Initialized, gd: GetData ) =>
      ctx.apply(RoleSet(Retriever()))
      ctx.send( Retrieve(gd.recoveryVerKey, gd.address, gd.locator, gd.locatorSignature))

    case x => throw new MatchError(s"Not handling a CTR message appropriately: $x")
  }

  def storeData(sd: StoreData): Unit = {
    ctx.storeSegment(sd.payload.address, Item(sd.payload.address, ByteString.copyFrom(sd.payload.data)))

    //TODO: fix this (how to send back ack msg to the cloud agent)
    //ctx.send(Ack())
  }

  override def handleProtoMsg: (DeadDropState, Option[Role], DeadDropProtoMsg) ?=> Any = {

    //TODO: what about checking roles Retriever?

    case (_, _, gd: Retrieve) =>

      val address = DigestUtils.sha256Hex(gd.recoveryVerKey + gd.locator)

     //TODO: do we need to send specific failure messages or generic error?
      if (address != gd.address) {
        throw new InvalidPayloadAddress
      }

      if (! Crypto.cryptoVerify(gd.recoveryVerKey, gd.locator.getBytes, gd.locatorSignature).get) {
        throw new AuthorizationFailed
      }

      ctx.withSegment[Item](gd.address) {
        case Success(item: Option[Item]) =>
          val ddEntry = item.map(i => DeadDropEntry(i.address, getBase64Encoded(i.data.toByteArray)))
          ctx.send(DeadDropRetrieveResult(ddEntry))
        case Failure(exception) =>
          throw exception
      }

    case (_, Some(Persister()), ddlr: DeadDropRetrieveResult) =>
      // TODO: ???? I am the dead-drop protocol and I got the data
      // where do I go from there, who do I send the data back to
      // some other protocol ? raise a decision point ? something else ?
      ctx.apply(
        ItemRetrieved(ddlr.entry.map(ddp =>
          Item(
            ddp.address,
            ByteString.copyFrom(getBase64Decoded(ddp.data)))
          )
        )
      )
    case x => throw new MatchError(s"Not handling a PROTO message appropriately $x")
  }

  override def applyEvent: ApplyEvent = {
    case (DeadDropState.Uninitialized() , _, Initialized(params))   => ( DeadDropState.Initialized(), initialize(params) )

    case (DeadDropState.Initialized()   , _, RoleSet(role))         => ( DeadDropState.Ready(), setRole(role) )

    case (_, _, ItemRetrieved(entry))  =>  DeadDropState.ItemRetrieved(entry.map(e => DeadDropPayload(e.address, e.data.toByteArray)))
  }

}

class InvalidPayloadAddress extends RuntimeException("invalid payload address")
class AuthorizationFailed extends RuntimeException("authorization failed")