package com.example

import java.time.LocalDateTime
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.Done
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{ActorMaterializer, KillSwitches, Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.mongodb.scala._

import scala.concurrent.Future
import scala.util.Random
import scala.concurrent.duration._

object MongoDBChangeStream extends App {
  import com.example.Helpers._

  implicit val system: ActorSystem = ActorSystem("test")
  implicit val materializer: Materializer = ActorMaterializer()

  val mongoClient: MongoClient = MongoClient("mongodb://localhost")
  val database: MongoDatabase = mongoClient.getDatabase("mydb")
  val collection = "mycollection"
  val ids = new AtomicInteger(1)
  val stopImmediatelly = args.isEmpty
  val stopInserts = new AtomicBoolean(false)

  def runInserts(continue: Boolean): Future[Unit] = if (continue) {
    import system.dispatcher
    Source.fromPublisher(
      database
        .getCollection[Document](collection)
        .insertOne(
          Document(
            "name" -> Random.alphanumeric.take(10).mkString
          )
        )
    ).map{ c =>
      info("Inserted one record")
      c
    }.runForeach { _ =>
      system.scheduler.scheduleOnce(1.second) {
        runInserts(continue && !stopInserts.get)
        ()
      }
    }.map(_ => ())

  } else Future.successful(())

  def consume(): UniqueKillSwitch = {
    import system.dispatcher

    val (ks, _) =
      Source.fromPublisher {
        val changeStreamObservable = database.getCollection[Document](collection)
          .watch[Document](pipeline = Seq())
        if (stopImmediatelly) {
          info("This will ends with Exception...")
        }
        observableToPublisherWL(changeStreamObservable, log = true, stopImmediatelly)
      }.map { doc =>
        // just print notifications about arriving updates from change stream
        info("Got next doc")
        doc
      }.viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.last)(Keep.both)
        .run()

    ks
  }


  // we start to insert records into collection
  runInserts(continue = true)

  system.scheduler.scheduleOnce(4.second) {
    // here we open changestream to consume inserted records
    val ks = consume()

    system.scheduler.scheduleOnce(5.second) {
      // here we close akkastream, and soon after we got exception
      // Problem: I do not see any way to close underlying cursor from `watch`
      info("Shutdown killswitch / akkastream")
      ks.shutdown()
    }(system.dispatcher)
  }(system.dispatcher)

  // schedule termination to complete
  system.scheduler.scheduleOnce(30.second) {
    stopInserts.set(true)
    system.terminate()
    ()
  }(system.dispatcher)
}


