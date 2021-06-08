package com.evernym.verity.protocol.testkit

import com.evernym.verity.actor.agent.relationship.Relationship
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.msg.{SetDataRetentionPolicy, SetDomainId, SetStorageId}

/**
  * It's important to test the actual objects we use in `main` as much as
  * possible. This is created specifically to be able to replace protocol
  * messages for testing purposes. Please limit how much the Test version
  * of [[SimpleProtocolSystem]] deviates from the original.
  */
class TestSimpleProtocolSystem() extends SimpleProtocolSystem with OutMsgReplacer {

  override def initProvider(rel: Relationship, initValues: Map[String, String] = Map.empty): InitProvider =
    new TestSystemInitProvider(Option(rel), initValues)

  override def handleOutMsg[A](env: Envelope1[A]): Unit = {
    val newEnv = replacer(env)
    super.handleOutMsg(newEnv)
  }
}

class TestSystemInitProvider(rel: Option[Relationship]=None, values: Map[String, String] = Map.empty) extends InitProvider {

  def request(c: InMemoryProtocolContainer[_,_,_,_,_,_]): Unit = requestInit(c)

  def requestInit(c: InMemoryProtocolContainer[_,_,_,_,_,_]): Unit = {
    val params = Parameters (
      c.definition.initParamNames map { param =>
        values.get(param)
        .map(Parameter(param, _))
        .getOrElse {
          param match {
            case SELF_ID => Parameter(SELF_ID, rel.map(_.myDid_!).getOrElse("self-id"))
            case OTHER_ID => Parameter(OTHER_ID, rel.map(_.theirDid_!).getOrElse("other-id"))
            // Please do not add values here, ProtocolTestKitLike allows for custom init parameters
          }
        }
      }
    )

    if (params.initParams.nonEmpty){
      c.submit(c.definition.createInitMsg(params))
      c.submit(SetStorageId(c.system.didRouter.get(c.participantId).domainId))
      c.submit(SetDataRetentionPolicy(params.initParams.find(_.name == DATA_RETENTION_POLICY).map(_.value)))
    }

  }
}

trait OutMsgReplacer {

  type Replacer = Envelope1[_] => Envelope1[_]

  var replacer: Replacer = identity

  def setReplacer(replacer: Replacer): Unit = {
    this.replacer = replacer
  }

  def clearReplacer(): Unit = {
    this.replacer = identity
  }

  def withReplacer[T](replacer: Replacer)(block: => T): T = {
    val saveReplacer = this.replacer
    setReplacer(replacer)
    try block
    finally setReplacer(saveReplacer)
  }

  def identity(in: Envelope1[_]): Envelope1[_] = in

}
