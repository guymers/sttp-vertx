package com.github.guymers.sttp.vertx

import java.nio.ByteBuffer

import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.SttpBackendOptions
import com.softwaremill.sttp.impl.monix.MonixStreamingTest
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

class VertxMonixStreamingTest extends MonixStreamingTest with VertxTest {

  override implicit lazy val backend: SttpBackend[Task, Observable[ByteBuffer]] = {
    VertxMonixBackend(vertx, SttpBackendOptions.Default, Scheduler.global)
  }

}
