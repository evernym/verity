package com.evernym.integrationtests.e2e.demo.scripts

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{HttpEntity, HttpMethod, HttpMethods, HttpRequest, MediaTypes}
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.http.common.AkkaHttpMsgSendingSvc
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.testkit.TestAppConfig
import org.json.JSONObject

import scala.concurrent.Future


class IBMInviterDemoScript extends AkkaHttpMsgSendingSvc(new TestAppConfig())(ActorSystem()) {

  val urlParam = UrlParam("https://agency.interop.ti.verify-creds.com")
  val mainAuth = Option("admin", "adminpw")   //confirm this with IBM if these are correct user name and password
  var agentId: String = _
  var agentPwd: String = _

  def agentAuth: Option[(String, String)] = Option(agentId, agentPwd)

  def handleRespFut(respFut: Future[Either[HandledErrorException, String]], f: String => Unit = {_ => }): Unit = {
    println("\nwaiting for response (don't execute any new command before you see response) ...")
    respFut.map {
      case Right(resp) =>
        println("response: " + resp)
        f(resp)
      case Left(e)     =>
        println("ERROR response: " + e.getErrorMsg)
    }.map { _ =>
      println("\n<<hit Enter to bring back scala prompt>>")
    }
  }

  def provisionAgent(_agentId: String, _agentPwd: String): Unit = {
    agentId = _agentId
    agentPwd = _agentPwd
    val json = s"""{"id":"$agentId","pass":"$agentPwd"}"""
    val respFut = sendGeneralMsgToRemoteEndpoint(json, mainAuth)(urlParam.copy(pathOpt=Option("api/v1/agents")))
    handleRespFut(respFut)
  }

  def createInvitation(): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint(s"""{"direct_route": true}""",
      agentAuth)(urlParam.copy(pathOpt=Option("api/v1/connection_invitations")))
    handleRespFut(respFut, { resp =>
      val jsonObject = new JSONObject(resp)
      val url = jsonObject.getString("url")
      println(s"\ninvitation url: $url")
    })
  }

  def checkConnection(id: String): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("", agentAuth,
      method = HttpMethods.GET)(urlParam.copy(pathOpt=Option(s"api/v1/connection_invitations/$id")))
    handleRespFut(respFut)
  }

  def listConnections(): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("", agentAuth,
      method = HttpMethods.GET)(urlParam.copy(pathOpt=Option(s"api/v1/connection_invitations")))
    handleRespFut(respFut)
  }

  def acceptInvitation(invitation: String): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint(s"""{"url": "$invitation"}""",
      agentAuth)(urlParam.copy(pathOpt=Option("api/v1/connections")))
    handleRespFut(respFut, { resp =>
      val jsonObject = new JSONObject(resp)
      val remote = jsonObject.getString("remote")
      println(s"\nremote: $remote")
    })
  }

  def listCredentials(): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("", agentAuth,
      method = HttpMethods.GET)(urlParam.copy(pathOpt=Option(s"api/v1/credentials")))
    handleRespFut(respFut)
  }

  def acceptCredential(id: String): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("""{"state":"accepted"}""", agentAuth,
      method = HttpMethods.PATCH)(urlParam.copy(pathOpt=Option(s"api/v1/credentials/$id")))
    handleRespFut(respFut)
  }

  def listProofRequests(): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("", agentAuth,
      method = HttpMethods.GET)(urlParam.copy(pathOpt=Option(s"api/v1/verifications")))
    handleRespFut(respFut)
  }

  def generateProof(id: String): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("""{"state":"proof_generated"}""", agentAuth,
      method = HttpMethods.PATCH)(urlParam.copy(pathOpt=Option(s"api/v1/verifications/$id")))
    handleRespFut(respFut)
  }

  def shareProof(id: String): Unit = {
    val respFut = sendGeneralMsgToRemoteEndpoint("""{"state":"proof_shared"}""", agentAuth,
      method = HttpMethods.PATCH)(urlParam.copy(pathOpt=Option(s"api/v1/verifications/$id")))
    handleRespFut(respFut)
  }

  def sendGeneralMsgToRemoteEndpoint(payload: String,
                                     basicAuth: Option[(String, String)]=None,
                                     method: HttpMethod = HttpMethods.POST)
                                    (implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    println("host: " + s"${up.host}")
    println("path: " + s"/${up.path}")
    val req = HttpRequest(
      method = method,
      uri = s"/${up.path}",
      entity = HttpEntity(MediaTypes.`application/json`, payload)
    )
    val reqAfterAddingAuth = basicAuth.map { case (userName, userPwd) =>
      req.addCredentials(BasicHttpCredentials.apply(userName, userPwd))
    }.getOrElse(req)

    sendRequest(reqAfterAddingAuth).flatMap { response =>
      val prp = performResponseParsing[String]
      prp(response)
    }
  }
}
