package com.softwaremill.sttp

object syntax {

  implicit final class MonadErrorOps[R[_], A](val r: R[A]) extends AnyVal {
    def map[B](f: A => B)(implicit ME: MonadError[R]): R[B] = ME.map(r)(f)
    def flatMap[B](f: A => R[B])(implicit ME: MonadError[R]): R[B] = ME.flatMap(r)(f)
  }
}
