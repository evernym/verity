package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.did.{DID, VerKey}
import scalapb.lenses.{Lens, Mutation, Updatable}

trait DidDocLike { this: Updatable[DidDoc] =>
  def did: DID

  def authorizedKeys: Option[AuthorizedKeys]
  def `authorizedKeys_!`: AuthorizedKeys = authorizedKeys
    .getOrElse(throw new RuntimeException("authorizedKeys is not defined"))

  def endpoints: Option[Endpoints]
  def `endpoints_!`: Endpoints = endpoints
    .getOrElse(throw new RuntimeException("endpoints is not defined"))

  def update(ms: (Lens[DidDoc, DidDoc] => Mutation[DidDoc])*): DidDoc

  validate()

  def validate(): Unit = {
    endpoints.map(_.endpoints).getOrElse(Seq.empty).flatMap(_.authKeyIds).foreach { ak =>
      if (! authorizedKeys_!.keys.exists(_.keyId == ak)) {
        throw new RuntimeException("auth key referenced from 'endpoints' not exists in 'authorizedKeys'")
      }
    }
  }

  def existsAuthedVerKey(verKey: VerKey): Boolean = authorizedKeys.exists(_.safeVerKeys.contains(verKey))

  /**
   * adds/replaces (old tags will be replaced with provided ones) given key in 'authorizedKeys'
   * @param newAuthKey
   * @return
   */
  def updatedWithNewAuthKey(newAuthKey: AuthorizedKey): DidDoc = {
    val otherAuthKeys = authorizedKeys
      .map{
        _.keys
          .filter(_.keyId != newAuthKey.keyId)
      }
      .getOrElse(Seq.empty)
    update(_.authorizedKeys := AuthorizedKeys(otherAuthKeys :+ newAuthKey))
  }


  /**
   * merges (new tags will be added to existing tags) given key in 'authorizedKeys'
   * @param newAuthKey
   * @return
   */
  private def updatedWithMergedAuthKey(newAuthKey: AuthorizedKey): DidDoc = {
    val (matchedAuthKeys, otherAuthKeys) =
      authorizedKeys_!
        .keys
        .partition(ak => ak.hasSameKeyIdAs(newAuthKey) || ak.hasSameVerKeyAs(newAuthKey))

    val (matchedWithSameKeyIdAndVerKey, matchedWithDifferentKeyIdAndVerKey) = matchedAuthKeys.partition { ak =>
      ak.verKeyOpt.contains(ak.keyId)
    }

    val updatedAuthKey = matchedWithDifferentKeyIdAndVerKey.find(emak => emak.keyId != emak.verKey).map { emak =>
      emak.addAllTags(newAuthKey.tags)
    }.getOrElse {
      newAuthKey.addAllTags(matchedWithSameKeyIdAndVerKey.flatMap(_.tags))
    }

    update(_.authorizedKeys := AuthorizedKeys(otherAuthKeys :+ updatedAuthKey))
  }

  /**
   * adds/replaces (old tags will be replaced with provided ones) a new "LegacyAuthorizedKey" in 'authorizedKeys'
   *
   * @param keyId
   * @param tags
   * @return
   */
  def updatedWithNewAuthKey(keyId: KeyId, tags: Set[Tags]): DidDoc = {
    updatedWithNewAuthKey(AuthorizedKey(keyId, "", tags))
  }

  /**
   * adds/replaces (old tags will be replaced with provided ones) a new "AuthorizedKey" in 'authorizedKeys'
   *
   * @param keyId
   * @param verKey
   * @param tags
   * @return
   */
  def updatedWithNewAuthKey(keyId: KeyId, verKey: VerKey, tags: Set[Tags]): DidDoc = {
    updatedWithNewAuthKey(AuthorizedKey(keyId, verKey, tags))
  }

  /**
   * merge (new tags will be added to existing key) the given "AuthorizedKey" in 'authorizedKeys'
   *
   * @param keyId
   * @param verKey
   * @param tags
   * @return
   */
  def updatedWithMergedAuthKey(keyId: KeyId, verKey: VerKey, tags: Set[Tags]): DidDoc = {
    updatedWithMergedAuthKey(AuthorizedKey(keyId, verKey, tags))
  }


  /**
   * adds/updates given endpoint in 'endpoints'
   *
   * @param endpoint
   * @return
   */
  def updatedWithEndpoint(endpoint: EndpointADT): DidDoc = {
    val updatedEndpoints = endpoints.getOrElse(Endpoints()).upsert(endpoint)
    update(_.endpoints := updatedEndpoints)
  }

  /**
   * adds/updates given endpoint in 'endpoints'
   *
   * @param endpoint
   * @return
   */
  def updatedWithEndpoint(endpoint: EndpointADTUntyped): DidDoc = {
    updatedWithEndpoint(EndpointADT(endpoint))
  }

  def updatedWithRemovedEndpointById(id: EndpointId): DidDoc = {
    update(_.endpoints := endpoints_!.remove(id))
  }

}