package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.did.VerKey
import com.evernym.verity.util.OptionUtil

import scala.language.implicitConversions

trait AuthorizedKeysLike {

  def keys: Seq[AuthorizedKey]

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
    AuthorizedKeys(filtered)
  }

  def safeAuthKeys: Vector[AuthorizedKeyLike] = safeAuthorizedKeys.keys.toVector

  def filterByKeyIds(keyIds: Iterable[KeyId]): Seq[AuthorizedKey] = {
    val keySeq = keyIds.toSeq
    keys.filter(ak => keySeq.contains(ak.keyId))
  }

  def filterByTags(tags: Tags*): Seq[AuthorizedKey] =
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
  def tags: Set[Tags]
  protected def givenVerKey: VerKey

  /*
  * It is possible that during event recovery the verKey is not known,
  * but by the time it is complete it will be known and it will be replaced with a copy with the verkey,
  * This is caused because the wallet information is not available (until full event recovery)
  */
  def verKey: VerKey = verKeyOpt.getOrElse(
    throw new UnsupportedOperationException("'Authorized' was not given a 'verKey'")
  )

  def verKeyOpt: Option[VerKey] = OptionUtil.blankOption(givenVerKey)

  def containsVerKey(vk: VerKey): Boolean = verKeyOpt.contains(vk)
  def hasSameVerKeyAs(otherAuthKey: AuthorizedKeyLike): Boolean = verKeyOpt == otherAuthKey.verKeyOpt
  def hasSameKeyIdAs(otherAuthKey: AuthorizedKeyLike): Boolean = keyId == otherAuthKey.keyId
}