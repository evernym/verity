package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.util2.HasWalletExecutionContextProvider
import com.evernym.verity.actor.wallet.{GetVerKeyOpt, GetVerKeyOptResp}
import com.evernym.verity.actor.agent.AuthKey
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{LegacyRoutingDetail, RoutingDetail}
import com.evernym.verity.util.Util.buildAgencyEndpoint
import com.evernym.verity.vault.AgentWalletAPI

import scala.concurrent.{ExecutionContext, Future}


case class DidDocBuilder(executionContext: ExecutionContext, didDoc: DidDoc = DidDoc())
                        (implicit didDocBuilderParam: DidDocBuilderParam) {

  private implicit def futureWalletExecutionContext: ExecutionContext = executionContext


  def withDid(did: DID): DidDocBuilder = {
    copy(didDoc = didDoc.copy(did = did))
  }

  def withAuthKey(authKeyId: KeyId,
                  authVerKey: VerKey,
                  tags: Set[Tags] = Set.empty): DidDocBuilder = {
    withAuthKeyRoutingDetail(authKeyId, authVerKey, tags, None)
  }

  def withAuthKeyAndEndpointDetail(authKeyId: KeyId,
                                   authVerKey: VerKey,
                                   tags: Set[Tags] = Set.empty,
                                   routingDetail: Either[LegacyRoutingDetail, RoutingDetail]): DidDocBuilder = {
    withAuthKeyRoutingDetail(authKeyId, authVerKey, tags, Some(routingDetail))
  }

  private def withAuthKeyRoutingDetail(authKeyId: KeyId,
                                       authVerKey: VerKey,
                                       tags: Set[Tags] = Set.empty,
                                       routingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]]=None): DidDocBuilder = {
    val newAuthKey = prepareAuthorizedKey(authKeyId, authVerKey, tags)
    withNewAuthorizedKeys(Seq(newAuthKey))
      .withThisAgentEndpoint(authKeyId)
      .withTheirAgentEndpoint(authKeyId, routingDetail)
  }

  private def withThisAgentEndpoint(keyId: KeyId): DidDocBuilder = {
    val newEndpoints: Seq[EndpointADTUntyped] =
      if (didDocBuilderParam.thisAgentKeyId.contains(keyId)) {
        val agencyEndpoint = buildAgencyEndpoint(didDocBuilderParam.appConfig)
        Seq(RoutingServiceEndpoint(agencyEndpoint.toString, authKeyIds = Seq(keyId)))
      } else Seq.empty
    withNewEndpoints(newEndpoints)
  }

  private def withTheirAgentEndpoint(keyId: KeyId,
                                     routingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]]=None): DidDocBuilder = {
    val newEndpoints: Option[EndpointADTUntyped] = routingDetail.map {
      case Left(lrd: LegacyRoutingDetail) =>
        LegacyRoutingServiceEndpoint(
          lrd.agencyDID, lrd.agentKeyDID, lrd.agentVerKey, lrd.agentKeyDlgProofSignature, Seq(keyId))
      case Right(rd: RoutingDetail)       =>
        RoutingServiceEndpoint(rd.endpoint, rd.routingKeys, Seq(keyId))
    }
    withNewEndpoints(newEndpoints.map(e => Seq(e)).getOrElse(Seq.empty))
  }

  private def withNewAuthorizedKeys(newAuthKeys: Seq[AuthorizedKey]): DidDocBuilder = {
    val currentAuthKeys = didDoc.authorizedKeys.map(_.keys).getOrElse(Seq.empty)
    val updatedAuthKeys = currentAuthKeys.filter(cak => ! newAuthKeys.map(_.keyId).contains(cak.keyId)) ++ newAuthKeys
    withAuthorizedKeys(updatedAuthKeys)
  }

  private def withNewEndpoints(newEndpoints: Seq[EndpointADTUntyped]): DidDocBuilder = {
    val newEndpointsADT = newEndpoints.map(EndpointADT(_))
    val currentEndpoints = didDoc.endpoints.map(_.endpoints).getOrElse(Seq.empty)
    val updatedEndpoints = currentEndpoints.filter(ce => ! newEndpointsADT.map(_.id).contains(ce.id)) ++ newEndpointsADT
    withEndpoints(updatedEndpoints)
  }

  private def withAuthorizedKeys(authKeys: Seq[AuthorizedKey]): DidDocBuilder = {
    copy(didDoc = didDoc.copy(authorizedKeys = Option(AuthorizedKeys(authKeys))))
  }

  private def withEndpoints(endpoints: Seq[EndpointADT]): DidDocBuilder = {
    copy(didDoc = didDoc.copy(endpoints = Option(Endpoints(endpoints))))
  }

  private def prepareAuthorizedKey(keyId: KeyId,
                                   verKey: VerKey,
                                   agentKeyTags: Set[Tags] = Set.empty): AuthorizedKey = {
    val verKeyOpt = Option(verKey)
    verKeyOpt match {
      case Some(vk) if vk.trim.nonEmpty => AuthorizedKey(keyId, vk, agentKeyTags)
      case _                            => AuthorizedKey(keyId, tags = agentKeyTags)
    }
  }

  /**
   * updates given did doc's authorized keys (LegacyAuthorizedKey to AuthorizedKey if there are any)
   * @return updated did doc
   */
  def updatedDidDocWithMigratedAuthKeys(explicitlyAddedAuthKeys: Set[AuthKey], agentWalletAPI: AgentWalletAPI): Future[DidDoc] = {
    val currentAuthKeys = didDoc.authorizedKeys_!.keys
    val currentEndpoints = didDoc.endpoints_!

    Future.traverse(currentAuthKeys) { ak =>
      authorizedKeyMapper(explicitlyAddedAuthKeys, agentWalletAPI)(ak)
    }.map { migratedAuthKeys =>

      //auth keys which MAY be duplicate of another auth keys
      val potentialDupAuthKeys = migratedAuthKeys.filter(ak => ak.verKeyOpt.contains(ak.keyId))

      //find out which auth key has a duplicate auth key in the list
      //result: Vector[(authKey, Option[duplicate AuthKey])]
      val result = migratedAuthKeys.map { curAuthKey =>
        val otherMatchedAuthKey =
          potentialDupAuthKeys
            .find { mak =>
              mak.keyId != curAuthKey.keyId &&
                mak.verKeyOpt == curAuthKey.verKeyOpt
            }
        val curAuthKeyUpdatedWithTags = otherMatchedAuthKey.map(mak => curAuthKey.addAllTags(mak.tags)).getOrElse(curAuthKey)
        (curAuthKeyUpdatedWithTags, otherMatchedAuthKey)
      }

      //remove duplicate auth keys
      val authKeysToRetain = result.filter(r => !result.exists(_._2.exists(_.keyId == r._1.keyId))).map(_._1)

      //updated endpoints
      val updatedEndpoints = currentEndpoints.endpoints.map { ce =>
        val updatedAuthKeys = ce.authKeyIds.map { ak =>
          result.find(e => e._2.exists(_.keyId == ak)).map(_._1.keyId).getOrElse(ak)
        }
        ce.updateAuthKeyIds(updatedAuthKeys.toSet.toSeq)
      }

      //update the did doc with new data
      withEndpoints(updatedEndpoints)
        .withAuthorizedKeys(authKeysToRetain)
        .didDoc
    }
  }

  /**
   * updates auth keys with empty verKey with actual verKey
   *
   * @return
   */
  private def authorizedKeyMapper(authKeys: Set[AuthKey], agentWalletAPI: AgentWalletAPI):
  PartialFunction[AuthorizedKey, Future[AuthorizedKey]] = {
    case key: AuthorizedKeyLike if Option(key.keyId).exists(_.nonEmpty) && key.verKeyOpt.isEmpty =>
      authKeys.find(_.keyId == key.keyId).map { ak =>
        Future.successful(key.copy(givenVerKey = ak.verKey))
      }.getOrElse {
        agentWalletAPI.walletAPI.executeAsync[GetVerKeyOptResp](GetVerKeyOpt(key.keyId))(agentWalletAPI.walletAPIParam).map { gvkrOpt =>
          AuthorizedKey(key.keyId, gvkrOpt.verKey.getOrElse(""), key.tags)
        }
      }
    case other                    => Future.successful(other)
  }
}

/**
 * relationship util parameter which contains wallet cache
 * which is used to get ver key from the agentKeyDID
 *
 * @param appConfig app config
 */
case class DidDocBuilderParam(appConfig: AppConfig, thisAgentKeyId: Option[KeyId])