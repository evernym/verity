package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.AuthorizedKeys.KeyId
import com.evernym.verity.protocol.engine.VerKey

import scala.language.implicitConversions

object AuthorizedKeys {
  type KeyId = String
  def apply(keys: AuthorizedKeyLike*): AuthorizedKeys = AuthorizedKeys(keys.toVector)
  //  def apply(keys: (KeyId, VerKey)*): AuthorizedKeys = AuthorizedKeys(keys.map(AuthorizedKey.apply).toVector)
}

case class AuthorizedKeys(keys: Vector[AuthorizedKeyLike] = Vector.empty) {

  validate()

  def validate(): Unit = {

    //shouldn't be more than 1 key with same 'verKey'
    val ids = keys.map(ak => ak.verKeyOpt.getOrElse(ak.keyId))
    if (ids.toSet.size != ids.size) {
      throw new RuntimeException("duplicate auth keys not allowed")
    }

    //shouldn't be more than 1 key with same 'keyId'
    val keyIds = keys.map(ak => ak.keyId)
    if (keyIds.toSet.size != keyIds.size) {
      throw new RuntimeException("duplicate auth keys not allowed")
    }
  }

  /**
   * filters out LegacyAuthorizedKey (which doesn't have 'verKey')
   * @return
   */
  def safeAuthorizedKeys: AuthorizedKeys = {
    val filtered = keys.filter(ak => ak.verKeyOpt.isDefined)
    copy(keys = filtered)
  }

  def safeAuthKeys: Vector[AuthorizedKeyLike] = safeAuthorizedKeys.keys

  def filterByKeyIds(keyIds: KeyId*): Vector[AuthorizedKeyLike] =
    keys.filter(ak => keyIds.toVector.contains(ak.keyId))

  def filterByTags(tags: Tag*): Vector[AuthorizedKeyLike] =
    keys.filter(ak => ak.tags.intersect(tags.toSet).nonEmpty)

  def filterByVerKeys(verKeys: VerKey*): Vector[AuthorizedKeyLike] =
    safeAuthKeys.filter(ak => verKeys.toVector.contains(ak.verKey))

  def findByVerKey(verKey: VerKey): Option[AuthorizedKeyLike] =
    safeAuthKeys.find(ak => ak.verKeyOpt.contains(verKey))

  /**
   * it returns ver key for 'AuthorizedKey' (ignores LegacyAuthorizedKey)
   * @return
   */
  def safeVerKeys: Set[VerKey] = safeAuthKeys.map(_.verKey).toSet
}

object AuthorizedKeyLike {
  def apply(key: (KeyId, VerKey)): AuthorizedKeyLike = AuthorizedKeyLike(key._1, key._2)
  implicit def tuple2AuthzKey(key: (KeyId, VerKey)): AuthorizedKeyLike = apply(key)
}

trait AuthorizedKeyLike {
  def keyId: KeyId
  def tags: Set[Tag]
  def verKey: VerKey

  def verKeyOpt: Option[VerKey] = Option(verKey)

  def containsVerKey(vk: VerKey): Boolean = verKeyOpt.contains(vk)
  def hasSameVerKeyAs(otherAuthKey: AuthorizedKeyLike): Boolean = verKeyOpt == otherAuthKey.verKeyOpt
  def hasSameKeyIdAs(otherAuthKey: AuthorizedKeyLike): Boolean = keyId == otherAuthKey.keyId

  def addTags(newTags: Set[Tag]): AuthorizedKeyLike
}
case class AuthorizedKey(keyId: KeyId, verKey: VerKey, tags: Set[Tag]) extends AuthorizedKeyLike {
  def addTags(newTags: Set[Tag]): AuthorizedKeyLike = copy(tags = tags ++ newTags)
}

/**
 * this would be used during event recovery as in good amount of cases,
 * by that time, the wallet information is not available (until full event recovery)
 * so, idea is that during recovery we'll create 'LegacyAuthorizedKey' (without ver key)
 * and post all event recovery it will be replaced by 'AuthorizedKey' (with ver key)
 *
 * @param keyId agent key id
 */
case class LegacyAuthorizedKey(keyId: KeyId, tags: Set[Tag]) extends AuthorizedKeyLike {
  override def verKeyOpt: Option[VerKey] = None
  def verKey: String = throw new UnsupportedOperationException("'LegacyAuthorizedKey' doesn't support 'verKey'")
  def addTags(newTags: Set[Tag]): AuthorizedKeyLike = copy(tags = tags ++ newTags)
}
