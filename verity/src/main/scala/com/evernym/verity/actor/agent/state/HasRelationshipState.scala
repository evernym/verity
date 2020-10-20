package com.evernym.verity.actor.agent.state

import com.evernym.verity.actor.agent.relationship.Endpoints._
import com.evernym.verity.actor.agent.relationship.{DidDoc, EndpointADTUntyped, EndpointId, HasRelationship, KeyId, KeyIds, Relationship, Tags}
import com.evernym.verity.actor.agent.user.{ComMethodDetail, ComMethodsPackaging, CommunicationMethods}
import com.evernym.verity.protocol.engine.VerKey

import scala.language.implicitConversions

trait RelationshipState extends HasRelationship {

  /**
   * initial relationship object
   * @return
   */
  def initialRel: Relationship

  private var _relationship: Relationship = initialRel
  def relationship: Relationship = _relationship

  /**
   * updates my did doc with given did doc
   * @param didDoc new/updated did doc
   * @return updated relationship
   */
  def updatedWithNewMyDidDoc(didDoc: DidDoc): Relationship =
    relationship.copy(myDidDoc = Option(didDoc))

  /**
   * key Id of the authorized key which is "controlled" by "this" agent.
   * as there can/will be more than 1 authorized keys (mostly in SelfRelationship)
   * there will a need to identify the one which is controlled by "this" agent
   */
  private var _thisAgentKeyId: Option[KeyId] = None
  def setThisAgentKeyId(keyId: KeyId): Unit = _thisAgentKeyId = Option(keyId)
  def thisAgentKeyId: Option[KeyId] = _thisAgentKeyId

  /**
   *
   * @param rel relationship object
   */
  def setRelationship(rel: Relationship): Unit = {
    _relationship = rel
  }

  def updateRelationship(rel: Relationship): Unit = {
    _relationship = rel
  }

  private def updateWithNewMyDidDoc(updatedDidDoc: DidDoc): Unit = {
    val updatedRel = updatedWithNewMyDidDoc(updatedDidDoc)
    updateRelationship(updatedRel)
  }

  def addNewAuthKeyToMyDidDoc(keyId: KeyId, verKey: VerKey, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithNewAuthKey(keyId, verKey, tags))
  }

  def mergeAuthKeyToMyDidDoc(keyId: KeyId, verKey: VerKey, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithMergedAuthKey(keyId, verKey, tags))
  }

  def addNewAuthKeyToMyDidDoc(keyId: KeyId, tags: Set[Tags]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithNewAuthKey(keyId, tags))
  }

  def addOrUpdateEndpointToMyDidDoc(endpoint: EndpointADTUntyped, authKeyIds: Set[KeyId]): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithEndpoint(endpoint, authKeyIds))
  }

  def removeEndpointById(id: EndpointId): Unit = {
    updateWithNewMyDidDoc(relationship.myDidDoc_!.updatedWithRemovedEndpointById(id))
  }

  def comMethodsByTypes(types: Seq[Int], withSponsorId: Option[String]): CommunicationMethods = {
    val endpoints = if (types.nonEmpty) myDidDoc_!.endpoints_!.filterByTypes(types: _*) else myDidDoc_!.endpoints_!.endpoints
    val comMethods = endpoints.map { ep =>
      val authKeyIds = myDidDoc_!.endpoints_!.endpointsToAuthKeys.getOrElse(ep.id, KeyIds())
      val verKeys = myDidDoc_!.authorizedKeys_!.safeAuthorizedKeys
        .filterByKeyIds(authKeyIds)
        .map(_.verKey).toSet
      val cmp = ep.packagingContext.map(pc => ComMethodsPackaging(pc.packVersion, verKeys))
      ComMethodDetail(ep.`type`, ep.value, cmp)
    }
    CommunicationMethods(comMethods.toSet, withSponsorId)
  }

  def myAuthVerKeys: Set[VerKey] =
    relationship.myDidDoc.map(_.authorizedKeys_!.safeVerKeys).getOrElse(Set.empty)

  def theirAuthVerKeys: Set[VerKey] =
    relationship.theirDidDoc.map(_.authorizedKeys_!.safeVerKeys).getOrElse(Set.empty)

  def thoseAuthVerKeys: Set[VerKey] =
    relationship.thoseDidDocs.flatMap(_.authorizedKeys_!.safeVerKeys).toSet

  def allAuthVerKeys: Set[VerKey] = myAuthVerKeys ++ theirAuthVerKeys ++ thoseAuthVerKeys

}

trait HasRelationshipState {
  type StateType <: RelationshipState
  def state: StateType
}
