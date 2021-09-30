package com.evernym.verity.vdr

package object service {
  type CreateVDR = CreateVDRParam => VDR
}

case class CreateVDRParam(libDirLocation: String)
