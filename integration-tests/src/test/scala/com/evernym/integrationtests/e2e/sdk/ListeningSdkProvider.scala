package com.evernym.integrationtests.e2e.sdk

import java.util.concurrent.LinkedBlockingDeque
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.testkit.listener.Listener
import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.verity.sdk.handlers.Handlers
import com.evernym.verity.sdk.utils.Context
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.time.{Millis, Span}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.language.postfixOps

trait MsgReceiver extends Eventually {
  def shouldExpectMsg(): Boolean = true
  def shouldCheckMsg(): Boolean = true

  def logger: Logger

  val unCheckedMsgs: mutable.Map[String, JSONObject] = mutable.Map()

  final def expectMsgOnly(expectedName: String)
                         (implicit scenario: Scenario): Unit = {
    expectMsg(expectedName, None){ _ => ()}
  }

  // Don't return the message but instead will run the check function that
  // can be used for asserting things about the message. Do not assert outside
  // of the check function so that the ManualSdkProvider which don't provide
  // messages can still work.
  final def expectMsg(expectedName: String,
                      max: Option[Duration] = None)
                     (check: JSONObject => Unit)
                     (implicit scenario: Scenario): Unit = {
    assertAndExpectMsg(Some(expectedName), max)(check)
  }

  final def checkMsg(max: Option[Duration] = None)
                    (check: JSONObject => Unit)
                    (implicit scenario: Scenario): Unit = {
    assertAndExpectMsg(None, max)(check)
  }

  protected def assertName(expectedName: String, msg: JSONObject): Boolean = {
    try {
      msg.getString(`@TYPE`)
        .endsWith(expectedName)
    } catch {
      case _: Exception =>
        false
    }
  }

  private def assertMsg(expectedName: Option[String], msg: JSONObject, check: JSONObject => Unit): Option[JSONObject] = {
    if (shouldCheckMsg()) {
      if (expectedName.forall(assertName(_, msg))) {
        try {
          check(msg)
        }
        catch {
          case t: Throwable =>
            logger.warn("check on message failed: " + t.getMessage)
            throw t
        }
        Some(msg)
      } else None
    } else Some(msg)
  }

  private def assertAndExpectMsg(expectedName: Option[String],
                                 max: Option[Duration] = None)
                                (check: JSONObject => Unit)
                                (implicit scenario: Scenario): Unit = {
    if (shouldExpectMsg()) {
      val waitTimeout = max.getOrElse(scenario.timeout)

      eventually(timeout(waitTimeout), Interval(Span(200, Millis))) {
        unCheckedMsgs
          .find(o => assertMsg(expectedName, o._2, check).isDefined)
          .map { m =>
            unCheckedMsgs.remove(m._1)
            m
          }
          .orElse {
            val m = expectMsg(waitTimeout)
            val mPair = (m.getString("@id"), m)
            unCheckedMsgs.put(mPair._1, mPair._2)
            None
          }
          .orElse {
            throw new Exception(
              s"msg not received yet --  (expecting: $expectedName) -- Know messages ${unCheckedMsgs}"
            )
          }
      }
    }
  }

  protected def expectMsg(max: Duration): JSONObject
  def context: Context
}

trait ListeningSdkProvider extends MsgReceiver {
  protected var listener: Listener = _
  private val queue: LinkedBlockingDeque[JSONObject] = new LinkedBlockingDeque[JSONObject]()

  def logger: Logger

  def receiveMsg(msg: JSONObject): Unit = queue.offerFirst(msg)

  def expectMsg(max: Duration): JSONObject = {
    val m = Option {
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    }

    sdkConfig.receiveSpacing.foreach{ d =>
      Thread.sleep(d.toMillis)
    }
    m.getOrElse(throw new Exception(s"timeout ($max) during expectMsg while waiting for message"))
  }

  def sdkConfig: SdkConfig
  def port: Int = sdkConfig.port.getOrElse(throw new Exception("Port must be defined for ListeningSdkProvider"))
  def endpointUrl: String = {
    sdkConfig
      .endpoint
      .getOrElse(throw new Exception("Endpoint must be defined for ListeningSdkProvider"))
      .url
  }

  def context: Context

  def startListener(): Unit = {
    val handles = new Handlers()

    handles.addDefaultHandler({msg: JSONObject =>
      receiveMsg(msg)
      if (assertName("status-report", msg))
        logger.info(s"ListenerSdkProvider: status-report received: ${msg.toString}")
    })

    listener = new Listener(port, { encryptMsg  =>
      handles.handleMessage(context, encryptMsg)
    })

    listener.listen()
  }

  def stopListener: Unit = {
    listener.stop()
  }
}
