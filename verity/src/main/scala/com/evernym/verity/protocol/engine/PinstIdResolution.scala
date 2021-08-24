package com.evernym.verity.protocol.engine

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc16
import com.evernym.verity.util.HashUtil.{byteArray2RichBytes, safeMultiHash}
import com.typesafe.scalalogging.Logger

/**
  * Interface for resolving pinstIds
  */
trait PinstIdResolver {
  def resolve(protoDef: ProtoDef,
              domainId: DomainId,
              relationshipId: Option[RelationshipId],
              threadId: Option[ThreadId],
              protocolIdSuffix: Option[String] = None, //Used only by V0_1
              contextualId: Option[String] = None //Used only by V0_1
             ): PinstId
}

/**
  * Static algorithms for calculating the pinstId (Protocol Instance Id). These algorithms MUST be static and MUST
  * be modified through revision ONLY. This is because the pinstId is used as part of the persistenceId for our Akka
  * integration. Because of this, these algorithms must return the same value until they can be retired.
  *
  * DO NOT MODIFY WITH DEEP AND CAREFUL CONSIDERATION (It is likely not a good idea)
  */

object PinstIdResolution {
  val logger: Logger = getLoggerByName("PinstIdResolution")

  val unknownRelationshipId: String = "RelationshipId is unknown but required to resolve a PinstId for " +
                                        s"${Scope.Relationship} Scope"
  val unknownThreadId: String = s"ThreadId is unknown but required to resolve a PinstId for ${Scope.Adhoc} Scope"
  val unknownContextualId: String = "Cannot use PinstIdResolver.DEPRECATED_V0_1 without contextualId, see note on contextualId"
  val protocolIdSuffixUsed: String = "protocolIdSuffix was not None - protocolIdSuffix is deprecated SHOULD only " +
                                        "be used by protocols using DEPRECATED_V0_1 will not be used in pinstId calculations"
  val unknownProtoRef: String = "The ProtoRef is required for all V0.2 pinstId resolutions. pinstId is un-calculable" +
                                "without the ProtoRef"
  val unknownDomainId: String = "The domainId is required for all V0.2 pinstId resolutions. pinstId is un-calculable" +
                                 "without the domainId"

  /**
    * Original algorithm. Deeply flowed and only allows a limited set of protocols to run. But as of now
    * most protocols use it and need it.
    */
  val DEPRECATED_V0_1: PinstIdResolver = new PinstIdResolver {
    override def resolve(protoDef: ProtoDef,
                         domainId: DomainId,
                         relationshipId: Option[RelationshipId],
                         threadId: Option[ThreadId],
                         protocolIdSuffix: Option[String],
                         contextualId: Option[String] = None
                        ): PinstId = {
      val ctxId = contextualId.getOrElse(throw new RuntimeException(unknownContextualId))

      // pinstId was calculated as a two part process that ran in two parts of the system.
      // this algorithm simulates that by first calculating what was called the safeThreadId
      // and then the actual pinstId.

      //for agent scoped protocols, we should not use thread id
      //for backward compatibility though, we are making sure it ignores
      //incoming thread id and instead just use default thread id.

      //TODO shall we add "Relationship" scope as well to ignore thread id
      // (or use default thread id)?
      val threadIdToUse = Option(protoDef) match {
        case Some(pd) =>
          pd.scope match {
            case Scope.Agent => Option(DEFAULT_THREAD_ID)
            case _ => threadId
          }
        case None => threadId
      }

      val safeThreadId = safeMultiHash(
        SHA256_trunc16,
        ctxId,
        threadIdToUse.getOrElse(DEFAULT_THREAD_ID)
      ).hex

      safeMultiHash(
        SHA256_trunc16,
        safeThreadId,
        protocolIdSuffix.getOrElse("")
      ).hex
    }
  }

  /**
    * More complete algorithm that considers protocol scopes
    */
  val V0_2: PinstIdResolver = new PinstIdResolver {
    override def resolve(protoDef: ProtoDef,
                         domainId: DomainId,
                         relationshipId: Option[RelationshipId],
                         threadId: Option[ThreadId],
                         doNotUsed: Option[String],
                         doNotUsed2: Option[String] = None
                        ): PinstId = {

      // protocolIdSuffix should not be used and supplied by protocols that use
      // this resolution strategy.
      doNotUsed.foreach(_ => logger.error(protocolIdSuffixUsed))

      val protoHash = Option(protoDef)
          .getOrElse(throw new RuntimeException(unknownProtoRef))
          .msgFamily
          .protoRef
          .toHash

      val safeDomainId = Option(domainId)
          .getOrElse(throw new RuntimeException(unknownDomainId))

      if (protoDef.scope == null)
        throw new RuntimeException("Protocol scope must be defined")

      protoDef.scope match {
        case Scope.Agent => safeMultiHash(
          SHA256_trunc16,
          protoHash,
          safeDomainId
        ).hex

        case Scope.Relationship =>
          safeMultiHash(
            SHA256_trunc16,
            protoHash,
            safeDomainId,
            relationshipId.getOrElse(throw new RuntimeException(unknownRelationshipId))
          ).hex

        case Scope.Adhoc =>

          safeMultiHash(
            SHA256_trunc16,
            protoHash,
            safeDomainId,
            relationshipId.getOrElse(throw new RuntimeException(unknownRelationshipId)),
            threadId.getOrElse(throw new RuntimeException(unknownThreadId))
          ).hex

        case Scope.RelProvisioning =>
          safeMultiHash(
            SHA256_trunc16,
            protoHash,
            safeDomainId,
            threadId.getOrElse(throw new RuntimeException(unknownThreadId))
          ).hex


        case null => throw new RuntimeException("Protocol scope must be defined")
      }
    }
  }
}
