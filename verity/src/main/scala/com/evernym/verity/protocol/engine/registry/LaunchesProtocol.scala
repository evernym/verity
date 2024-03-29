package com.evernym.verity.protocol.engine.registry

import com.evernym.verity.did.didcomm.v1.messages.{MsgType, TypedMsgLike}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry.Entry

/**
  * Contains common code for anything that launches protocols.
  *
  */
trait LaunchesProtocol {

  // LEGACY: The V0_1 version of PinstId resolution requires a contextual id. This value would
  // change depending on if it was a Pairwise actor or not. For Pairwise actors it would use the
  // pairwiseDID but would change agent wide DID for UserAgent or AgencyAgent. This will be required
  // until we chane remove the V0_1 resolver.
  def contextualId: Option[String] = None

  /**
    * The agent-wide identifier for the self-sovereignty domain. This value should be
    * unique and consistent for all elements of Agent using protocols.
    *
    * This value is required for all protocol scopes since it namespaces the protocol to
    * single owning Identity
    *
    * @return
    */
  def domainId: DomainId

  type ControllerProviderInputType

  type ProtoReg = ProtocolRegistry[ControllerProviderInputType]

  protected def protocolRegistry: ProtoReg

  /**
   * implementing class can override this function if needed
   *
   * mostly this is helpful if same protocol instances needs to be launched
   * from different context for same logical agent
   * @param protoDef protocol definition
   * @return
   */
  protected def getPinstId(protoDef: ProtoDef): Option[PinstId] = None

  def resolvePinstId(protoDef: ProtoDef,
                     resolver: PinstIdResolver,
                     relationshipId: Option[RelationshipId],
                     threadId: ThreadId,
                     msg: Option[TypedMsgLike]=None): PinstId = {
    resolver.resolve(
      protoDef,
      domainId,
      relationshipId,
      Option(threadId),
      msg.flatMap(protoDef.protocolIdSuffix),
      contextualId
    )
  }

  protected def pinstIdForMsg_!(tms: TypedMsgLike,
                                relationshipId: Option[RelationshipId],
                                threadId: ThreadId): PinstIdPair = {
    val entry = protocolRegistry.entryForMsg_!(tms)
    PinstIdPair(pinstIdForProtoDef(tms, relationshipId, threadId, entry.protoDef, entry.pinstIdResol), entry.protoDef)
  }

  protected def pinstIdForUntypedMsg_![A](m: A,
                                          relationshipId: Option[RelationshipId],
                                          threadId: ThreadId): PinstIdPair = {
    val tmsgPair = typedMsgPair(m)
    registry.PinstIdPair(pinstIdForProtoDef(tmsgPair.typedMsg, relationshipId, threadId,
      tmsgPair.entry.protoDef, tmsgPair.entry.pinstIdResol), tmsgPair.entry.protoDef)
  }

  private def pinstIdForProtoDef(msg: TypedMsgLike,
                                 relationshipId: Option[RelationshipId],
                                 threadId: ThreadId,
                                 protoDef: ProtoDef,
                                 resolver: PinstIdResolver): PinstId = {
    getPinstId(protoDef).getOrElse(resolvePinstId(protoDef, resolver, relationshipId, threadId, Option(msg)))
  }

  protected def typedMsg(m: Any): TypedMsgLike = {
    typedMsgPair(m).typedMsg
  }

  private def typedMsgPair(m: Any): TypedMsgPair = {
    val entry = protocolRegistry.entryForUntypedMsg_!(m)
    val msgFamily = entry.protoDef.msgFamily
    val typedMsg = try {
      msgFamily.typedMsg(m)
    } catch {
      case _: RuntimeException =>
        //TODO: this is for those protocols whose msg family is not fully defined
        TypedMsg(m, MsgType(msgFamily.qualifier, msgFamily.name, msgFamily.version, m.getClass.getSimpleName))
    }
    registry.TypedMsgPair(typedMsg, entry)
  }
}

case class PinstIdPair(id: PinstId, protoDef: ProtoDef)
case class TypedMsgPair(typedMsg: TypedMsgLike, entry: Entry[_])
