package com.evernym.verity.http.route_handlers.open.models

case class ReadinessStatus(status: Boolean = false,
                           akkaStorageStatus: String = "",
                           walletStorageStatus: String = "",
                           blobStorageStatus: String = "")