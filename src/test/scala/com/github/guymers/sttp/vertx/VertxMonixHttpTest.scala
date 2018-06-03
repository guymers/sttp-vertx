package com.github.guymers.sttp.vertx

import java.nio.ByteBuffer

import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.SttpBackendOptions
import com.softwaremill.sttp.impl.monix.convertMonixTaskToFuture
import com.softwaremill.sttp.testing.ConvertToFuture
import com.softwaremill.sttp.testing.HttpTest
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

class VertxMonixHttpTest extends HttpTest[Task] with VertxTest {

  override implicit val convertToFuture: ConvertToFuture[Task] = convertMonixTaskToFuture
  override implicit lazy val backend: SttpBackend[Task, Observable[ByteBuffer]] = {
    VertxMonixBackend(vertx, SttpBackendOptions.Default, Scheduler.global)
  }

}
