package com.evernym.verity.config

import com.typesafe.config.ConfigFactory

object TypeSafeConfigExperiments extends App {
  val t = """
            |    "verity" : {
            |      #************************
            |      # Cg5Cd33KWJ9rJ9XyRBnni5
            |      #************************
            |      msg-template.agent-specific {
            |        Cg5Cd33KWJ9rJ9XyRBnni5 {
            |          sms-msg-template-invite-url = "hi"
            |          sms-msg-template-offer-conn-msg = "bye"
            |          sms-offer-template-deeplink-url = "nevermore"
            |        }
            |      }
            |      agent.authentication.keys {
            |        Cg5Cd33KWJ9rJ9XyRBnni5: [Eicc6uRQHpotSxvp75ShqyMRgy9dM5ZbNkxoRdNPgNCM, Aicc6uRQHpotSxvp75ShqyMRgy9dM5ZbNkxoRdNPgNCM]
            |      }
            |      retention-policy {
            |        Cg5Cd33KWJ9rJ9XyRBnni5 {
            |          undefined-fallback {
            |            expire-after-days = "3 days"
            |          }
            |          "basicmessage[1.0]" {
            |             expire-after-days = "3 days"
            |          }
            |        }
            |      }
            |      #************************
            |      # 69bJGDneUaZvpyhr5CheeW
            |      #************************
            |      msg-template.agent-specific {
            |        69bJGDneUaZvpyhr5CheeW {
            |          sms-msg-template-invite-url = "hi"
            |          sms-msg-template-offer-conn-msg = "bye"
            |          sms-offer-template-deeplink-url = "nevermore"
            |        }
            |      }
            |      agent.authentication.keys {
            |        69bJGDneUaZvpyhr5CheeW: [3ok4Xgpkx7ELWt4cd4JKzdW2YnEaWPx3D6B471oWyoUE, 9ok4Xgpkx7ELWt4cd4JKzdW2YnEaWPx3D6B471oWyoUE]
            |      }
            |      retention-policy {
            |        69bJGDneUaZvpyhr5CheeW {
            |          undefined-fallback {
            |            expire-after-days = "3 days"
            |          }
            |          "basicmessage[1.0]" {
            |           expire-after-days = "3 days"
            |          }
            |        }
            |      }
            |    }
            |
    |""".stripMargin

  val c = ConfigFactory.parseString(t)
  println(c.toString)
}
