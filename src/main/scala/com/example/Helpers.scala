package com.example

import java.time.LocalDateTime
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import scala.language.implicitConversions
import org.mongodb.{scala => mongoDB}
import org.{reactivestreams => rxStreams}

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Success

object Helpers {

  def info(s: String, verbose: Boolean = true): Unit = {
    if (verbose) {
      println(s"${LocalDateTime.now()} $s")
    }
  }
  implicit def observableToPublisher[T](observable: mongoDB.Observable[T])(implicit ec: ExecutionContext): rxStreams.Publisher[T] = ObservableToPublisher(observable)
  def observableToPublisherWL[T](observable: mongoDB.Observable[T], log: Boolean, stopImmediatelly: Boolean)(implicit ec: ExecutionContext): rxStreams.Publisher[T] = ObservableToPublisher(observable, log, stopImmediatelly)

  case class ObservableToPublisher[T](observable: mongoDB.Observable[T], log: Boolean = false, stopImmediatelly: Boolean = false)(implicit ec: ExecutionContext) extends rxStreams.Publisher[T] {
    def subscribe(subscriber: rxStreams.Subscriber[_ >: T]): Unit = {
      observable.subscribe(
        new mongoDB.Observer[T]() {

          final private val awaitingFor: AtomicLong = new AtomicLong(0)
          final private val cancelled: AtomicBoolean = new AtomicBoolean
          final private val killSwitch = Promise[Unit]

          override def onSubscribe(subscription: mongoDB.Subscription): Unit = {
            def maybeUnsubscribe(force: Boolean): Unit = {
              if (force) {
                val suffix =
                  if (awaitingFor.get() > 0) ", Next will be exception :("
                  else ""
                info(s"will unsubscribe               <========== Here we unsubscribe from mongo subscription$suffix", log)
                subscription.unsubscribe()
              }
            }

            subscriber.onSubscribe(new rxStreams.Subscription() {

              def request(n: Long) {
                info(s"Called request $n", log)
                awaitingFor.addAndGet(n)
                if (!subscription.isUnsubscribed && n < 1) {
                  subscriber.onError(
                    new IllegalArgumentException(
                      """3.9 While the Subscription is not cancelled,
                        |Subscription.request(long n) MUST throw a java.lang.IllegalArgumentException if the
                        |argument is <= 0.""".stripMargin
                    )
                  )
                } else {
                  subscription.request(n)
                }
              }

              def cancel() {
                info(s"called cancel()                <========== Here we were asked to unsubscribe / reactivestream", log)
                if (!cancelled.getAndSet(true)) {
                  maybeUnsubscribe(stopImmediatelly)
                }
              }
            })
            // unsubscribe on killSwitch
            killSwitch.future.onComplete { _ =>
              maybeUnsubscribe(!stopImmediatelly)
            }
          }

          def onNext(result: T): Unit = {
            awaitingFor.decrementAndGet()
            info(s"Called onNext, awaiting for ${awaitingFor.get()} more", log)
            if (!cancelled.get()) subscriber.onNext(result)
            maybeComplete()
          }

          def onError(e: Throwable): Unit = {
            info(s"Called onError", log)
            if (!cancelled.get()) subscriber.onError(e)
            maybeComplete()
          }

          def onComplete(): Unit = {
            info(s"Called onComplete", log)
            if (!cancelled.get()) subscriber.onComplete()
            maybeComplete()
          }
          private def maybeComplete(): Unit = {
            if (cancelled.get() && awaitingFor.get() == 0) {
              killSwitch.tryComplete(Success(()))
            }
          }
        }
      )
    }
  }
}
