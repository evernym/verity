package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, EDGE_AGENT_KEY}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{LegacyRoutingDetail, RoutingDetail}
import com.evernym.verity.util.Util.buildAgencyEndpoint

object RelationshipUtil {

  /**
   * prepares "initial" version of their did doc for given domain DID
   *
   * @param relScopeDID DID of the relationship scope ('self', 'pairwise', 'anywise' etc)
   * @param agentKeyDID DID assigned/belong to agent key
   * @return
   */
  def prepareTheirDidDoc(relScopeDID: DID,
                         agentKeyDID: DID,
                         routingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]]=None)
                        (implicit relationshipUtilParam: RelUtilParam): DidDoc = {
    RelationshipUtil.buildTheirDidDoc(relScopeDID, agentKeyDID, routingDetail)
  }

  /**
   * prepares "initial" version of my did doc for given domain DID and agent key DID
   *
   * @param relScopeDID DID of the relationship scope ('self', 'pairwise', 'anywise' etc)
   * @param agentKeyDID DID assigned/belong to agent key
   * @param agentKeyTags tags associated with the auth key
   * @return
   */
  def prepareMyDidDoc(relScopeDID: DID,
                      agentKeyDID: DID,
                      agentKeyTags: Set[Tags])
                     (implicit relationshipUtilParam: RelUtilParam): DidDoc = {
    val agentAuthKey = prepareAuthorizedKey(agentKeyDID, agentKeyTags)
    val relScopeAuthKey = if (relScopeDID != agentKeyDID) Seq(prepareAuthorizedKey(relScopeDID, Set(EDGE_AGENT_KEY))) else Seq.empty
    val agentEndpoint = buildAgencyEndpoint(relationshipUtilParam.appConfig)
    val endpoints = if (relationshipUtilParam.thisAgentKeyId.contains(agentKeyDID)) {
      Endpoints.init(RoutingServiceEndpoint(agentEndpoint.toString, authKeyIds = Seq(agentAuthKey.keyId)))
    } else Endpoints.empty
    val keys = AuthorizedKeys(Seq(agentAuthKey) ++ relScopeAuthKey)
    DidDoc(relScopeDID, Some(keys), Some(endpoints))
  }

  /**
   * prepares "initial" version of their did doc for given domain DID
   *
   * @param relScopeDID DID of the relationship scope ('self', 'pairwise', 'anywise' etc)
   * @param agentKeyDID DID assigned/belong to agent key
   * @return
   */
  def buildTheirDidDoc(relScopeDID: DID,
                       agentKeyDID: DID,
                       routingDetail: Option[Either[LegacyRoutingDetail, RoutingDetail]]=None)
                      (implicit relationshipUtilParam: RelUtilParam): DidDoc = {
    val agentAuthKey = prepareAuthorizedKey(agentKeyDID, Set(AGENT_KEY_TAG))
    val relScopeAuthKey = if (relScopeDID != agentKeyDID) Seq(prepareAuthorizedKey(relScopeDID, Set(EDGE_AGENT_KEY))) else Seq.empty
    val endpointsOpt = routingDetail map {
      case Left(lrd: LegacyRoutingDetail) =>
        val ep = LegacyRoutingServiceEndpoint(
          lrd.agencyDID, lrd.agentKeyDID, lrd.agentVerKey, lrd.agentKeyDlgProofSignature, Seq(agentAuthKey.keyId))
        Endpoints.init(ep)
      case Right(rd: RoutingDetail)       =>
        Endpoints.init(RoutingServiceEndpoint(rd.endpoint, rd.routingKeys, Seq(agentAuthKey.keyId)))
    }
    val keys = AuthorizedKeys(Seq(agentAuthKey) ++ relScopeAuthKey)
    val endpoints = endpointsOpt.getOrElse(Endpoints.empty)
    DidDoc(relScopeDID, Some(keys), Some(endpoints))
  }

  /**
   * updates given did doc's authorized keys (LegacyAuthorizedKey to AuthorizedKey if there are any)
   * @param didDoc did document
   * @return updated did doc
   */
  def updatedDidDocWithMigratedAuthKeys(didDoc: Option[DidDoc])
                                       (implicit relationshipUtilParam: RelUtilParam): Option[DidDoc] = {
    didDoc.map { dd =>
      val currentAuthKeys = dd.authorizedKeys_!.keys
      val currentEndpoints = dd.endpoints_!

      val migratedAuthKeys = currentAuthKeys.map(authorizedKeyMapper(relationshipUtilParam.walletVerKeyCacheHelper))

      //find out which auth key has a duplicate auth key in the list
      //result: Vector[(authKey, Option[duplicate AuthKey])]
      val result = migratedAuthKeys.map { curAuthKey =>
        val otherMatchedAuthKey =
          migratedAuthKeys
            .find { mak =>
              mak.keyId != curAuthKey.keyId &&
                mak.verKeyOpt == curAuthKey.verKeyOpt &&
                mak.verKeyOpt.contains(mak.keyId)
            }
        val curAuthKeyUpdatedWithTags = otherMatchedAuthKey.map(mak => curAuthKey.addAllTags(mak.tags)).getOrElse(curAuthKey)
        (curAuthKeyUpdatedWithTags, otherMatchedAuthKey)
      }

      //remove duplicate auth keys
      val authKeysToRetain = result.filter(r => ! result.exists(_._2.exists(_.keyId == r._1.keyId))).map(_._1)

      //updated endpoints
      val updatedEndpoints = currentEndpoints.endpoints.map { ce =>
        val updatedAuthKeys = ce.authKeyIds.map { ak =>
          result.find(e => e._2.exists(_.keyId == ak)).map(_._1.keyId).getOrElse(ak)
        }
        ce.updateAuthKeyIds(updatedAuthKeys)
      }
      //update the did doc with new data
      dd.copy(
        authorizedKeys = Option(AuthorizedKeys(authKeysToRetain)),
        endpoints = Option(Endpoints(updatedEndpoints))
      )
    }
  }

  /**
   * it is assumed that there is only ver key associated with a DID
   * (which would become wrong assumption sooner or later)
   * @param did a DID
   * @return
   */
  private def getDidVerKey(did: DID, walletVerKeyCacheHelper: WalletVerKeyCacheHelper): Option[VerKey] =
    walletVerKeyCacheHelper.getVerKeyViaCache(did)

  private def prepareAuthorizedKey(agentKeyDID: DID, agentKeyTags: Set[Tags] = Set.empty)
                                  (implicit relationshipUtilParam: RelUtilParam): AuthorizedKey = {
    relationshipUtilParam.walletVerKeyCacheHelperOpt match {
      case Some(wc) if Option(agentKeyDID).exists(_.nonEmpty) =>
        //if code execution is coming to this block, that means this function is being called
        // either during 'post recovery' or as part of 'some message handling code' and
        // wallet information is available by this time and so prepare the
        // 'AuthorizedKey' is along with its ver key
        val verKeyOpt = getDidVerKey(agentKeyDID, wc)
        AuthorizedKey(agentKeyDID, verKeyOpt.getOrElse(""), agentKeyTags)
      case _     =>
        //if code execution is coming to this block, that means this function is being called
        // during recovery of the actor and few times (mostly in pairwise actors)
        // the wallet information is not available until post recovery,
        // so just create 'AuthorizedKey' with empty ver key and then after successful recovery,
        // 'relationship' object will be re-built with proper 'AuthorizedKey'
        AuthorizedKey(agentKeyDID, tags = agentKeyTags)
    }
  }

  /**
   * maps one type of authorized key to different type of authorized key
   * currently, maps LegacyAuthorizedKey to AuthorizedKey
   *
   * @return
   */
  private def authorizedKeyMapper(walletVerKeyCacheHelper: WalletVerKeyCacheHelper):
  PartialFunction[AuthorizedKey, AuthorizedKey] = {
    case key: AuthorizedKeyLike if Option(key.keyId).exists(_.nonEmpty) && key.verKeyOpt.isEmpty =>
      val verKeyOpt = getDidVerKey(key.keyId, walletVerKeyCacheHelper)
      AuthorizedKey(key.keyId, verKeyOpt.getOrElse(""), key.tags)
    case other                    => other
  }
}

/**
 * relationship util parameter which contains wallet cache
 * which is used to get ver key from the agentKeyDID
 *
 * @param appConfig app config
 * @param walletVerKeyCacheHelperOpt wallet ver key cache helper
 */
case class RelUtilParam(appConfig: AppConfig,
                        thisAgentKeyId: Option[KeyId],
                        walletVerKeyCacheHelperOpt: Option[WalletVerKeyCacheHelper]) {
  def walletVerKeyCacheHelper: WalletVerKeyCacheHelper =
    walletVerKeyCacheHelperOpt
      .getOrElse(throw new RuntimeException("wallet ver key cache helper not provided"))
}