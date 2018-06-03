package com.softwaremill.sttp

// export util functions that are package private
object Export {

  def encodingFromContentType(ct: String): Option[String] = {
    com.softwaremill.sttp.encodingFromContentType(ct)
  }
}
