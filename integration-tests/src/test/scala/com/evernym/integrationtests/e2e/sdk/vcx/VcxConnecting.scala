package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.verity.util.{Base64Util, MsgUtil}
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.{UndefinedConnections_1_0, UndefinedOutOfBand_1_0}
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.verity.sdk.protocols.connecting.v1_0.ConnectionsV1_0
import com.evernym.verity.sdk.protocols.outofband.v1_0.OutOfBandV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject

import scala.compat.java8.FutureConverters
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

trait VcxConnecting
  extends VcxHolds {

  var connectingHandle : Integer = 0;
  var outOfBandHandle : Integer = 0;

  def connecting_1_0(sourceId: String, label: String, inviteUrl: String): ConnectionsV1_0 = {
    new UndefinedConnections_1_0 {
      override def accept(context: Context): Unit = {
        val decoded = Base64Util.getBase64Decoded(inviteUrl.split("c_i=", 2).last)
        val invite = new JSONObject(new String(decoded))
        val updatedInvite = invite
          .put("@id", MsgUtil.newMsgId)
        connectingHandle = ConnectionApi.vcxCreateConnectionWithInvite(sourceId, updatedInvite.toString).get()
        ConnectionApi.vcxConnectionConnect(connectingHandle, "{}").get()
        val did = ConnectionApi.connectionGetPwDid(connectingHandle).get()
        addConnectionHandle(did -> connectingHandle)
      }

      override def status(context: Context): Unit = {
        ConnectionApi.vcxConnectionUpdateState(connectingHandle).get()
        val futureGetState: Integer = ConnectionApi.connectionGetState(connectingHandle).get()
        assert(futureGetState == 4)
      }
    }
  }

  def connectingWithOutOfBand_1_0(sourceId: String, label: String, inviteUrl: String): ConnectionsV1_0 = {
    new UndefinedConnections_1_0 {
      override def accept(context: Context): Unit = {
        val decoded = Base64Util.getBase64Decoded(inviteUrl.split("oob=", 2).last)
        val invite = new JSONObject(new String(decoded))
        val updatedInvite = invite
          .put("@id", MsgUtil.newMsgId)
        outOfBandHandle = ConnectionApi.vcxCreateConnectionWithOutofbandInvite(sourceId, updatedInvite.toString).get()
        ConnectionApi.vcxConnectionConnect(outOfBandHandle, "{}").get()
        val did = ConnectionApi.connectionGetPwDid(outOfBandHandle).get()
        addConnectionHandle(did -> outOfBandHandle)
      }

      override def status(context: Context): Unit = {
        ConnectionApi.vcxConnectionUpdateState(outOfBandHandle).get()
        val futureGetState: Integer = ConnectionApi.connectionGetState(outOfBandHandle).get()
        assert(futureGetState == 4)
      }
    }
  }


  def outOfBand_1_0(threadId: String, inviteUrl: String): OutOfBandV1_0 = {
    new UndefinedOutOfBand_1_0 {
      override def handshakeReuse(context: Context): Unit = {
        val decoded = Base64Util.getBase64Decoded(inviteUrl.split("oob=", 2).last)
        val invite = new JSONObject(new String(decoded))
        ConnectionApi.connectionSendReuse(outOfBandHandle, invite.toString).get()
      }
    }
  }
}

object VcxConnecting {
}
