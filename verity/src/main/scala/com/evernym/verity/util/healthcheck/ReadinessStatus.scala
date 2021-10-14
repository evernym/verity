package com.evernym.verity.util.healthcheck

case class ReadinessStatus(status: Boolean = false, rds: String = "", dynamoDB: String = "", storageAPI: String = "")
