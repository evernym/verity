package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.AuthorizedKeys.KeyId
import com.evernym.verity.actor.agent.relationship.Endpoints.EndpointId
import com.evernym.verity.protocol.engine.{DID, VerKey}

case class DidDoc(did: DID, authorizedKeys: AuthorizedKeys = AuthorizedKeys(), endpoints: Endpoints = Endpoints.empty) {

  validate ()

  def validate(): Unit = {
    endpoints.endpointsToAuthKeys.values.toSet.flatten.foreach { ak =>
      if (! authorizedKeys.keys.exists(_.keyId == ak)) {
        throw new RuntimeException("auth key referenced from 'endpoints' not exists in 'authorizedKeys'")
      }
    }
  }

  /**
   * adds/replaces (old tags will be replaced with provided ones) given key in 'authorizedKeys'
   * @param newAuthKey
   * @return
   */
  private def updatedWithNewAuthKey(newAuthKey: AuthorizedKeyLike): DidDoc = {
    val otherAuthKeys = authorizedKeys.keys.filter(_.keyId != newAuthKey.keyId)
    copy(authorizedKeys = AuthorizedKeys(otherAuthKeys :+ newAuthKey))
  }

  /**
   * merges (new tags will be added to existing tags) given key in 'authorizedKeys'
   * @param newAuthKey
   * @return
   */
  private def updatedWithMergedAuthKey(newAuthKey: AuthorizedKeyLike): DidDoc = {
    val (matchedAuthKeys, otherAuthKeys) =
      authorizedKeys
        .keys
        .partition(ak => ak.hasSameKeyIdAs(newAuthKey) || ak.hasSameVerKeyAs(newAuthKey))

    val (matchedWithSameKeyIdAndVerKey, matchedWithDifferentKeyIdAndVerKey) = matchedAuthKeys.partition { ak =>
      ak.verKeyOpt.contains(ak.keyId)
    }

    val updatedAuthKey = matchedWithDifferentKeyIdAndVerKey.find(emak => emak.keyId != emak.verKey).map { emak =>
      emak.addTags(newAuthKey.tags)
    }.getOrElse {
      newAuthKey.addTags(matchedWithSameKeyIdAndVerKey.flatMap(_.tags).toSet)
    }

    copy(authorizedKeys = AuthorizedKeys(otherAuthKeys :+ updatedAuthKey))
  }

  /**
   * adds/replaces (old tags will be replaced with provided ones) a new "LegacyAuthorizedKey" in 'authorizedKeys'
   *
   * @param keyId
   * @param tags
   * @return
   */
  def updatedWithNewAuthKey(keyId: KeyId, tags: Set[Tag]): DidDoc = {
    updatedWithNewAuthKey(LegacyAuthorizedKey(keyId, tags))
  }

  /**
   * adds/replaces (old tags will be replaced with provided ones) a new "AuthorizedKey" in 'authorizedKeys'
   *
   * @param keyId
   * @param verKey
   * @param tags
   * @return
   */
  def updatedWithNewAuthKey(keyId: KeyId, verKey: VerKey, tags: Set[Tag]): DidDoc = {
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
  def updatedWithMergedAuthKey(keyId: KeyId, verKey: VerKey, tags: Set[Tag]): DidDoc = {
    updatedWithMergedAuthKey(AuthorizedKey(keyId, verKey, tags))
  }

  /**
   * adds/updates given endpoint in 'endpoints'
   *
   * @param endpoint
   * @return
   */
  def updatedWithEndpoint(endpoint: EndpointLike, authKeyIds: Set[KeyId]): DidDoc = {
    if (authKeyIds.isEmpty && ! endpoints.endpointsToAuthKeys.contains(endpoint.id))
      throw new RuntimeException("at least one auth key id require to be associated with the given endpoint")
    authKeyIds.foreach(checkIfAuthKeyExists)
    copy(endpoints = endpoints.addOrUpdate(endpoint, authKeyIds))
  }

  private def checkIfAuthKeyExists(keyId: KeyId): Unit = {
    if (! authorizedKeys.keys.exists(ak => ak.keyId == keyId))
      throw new RuntimeException(s"authorized key '$keyId' doesn't exists")
  }

  def updatedWithRemovedEndpointById(id: EndpointId): DidDoc = {
    copy(endpoints = endpoints.remove(id))
  }

  def existsAuthedVerKey(verKey: VerKey): Boolean = authorizedKeys.safeVerKeys.contains(verKey)
}