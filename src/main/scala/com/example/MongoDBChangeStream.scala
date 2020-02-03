package com.example

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.ActorSystem
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

  def runInserts(): Future[Done] = {
    import system.dispatcher
    Source.fromPublisher(
      database
        .getCollection[Document](collection)
        .insertOne(
          Document(
            "name" -> Random.alphanumeric.take(10).mkString
          )
        )
    ).runForeach { _ =>
      system.scheduler.scheduleOnce(1.second) {
        runInserts()
        ()
      }
    }
  }

  def consume(i: Int): UniqueKillSwitch = {
    val (ks, _) =
      Source.fromPublisher {
        val changeStreamObservable = database.getCollection[Document](collection)
          .watch[Document](pipeline = Seq())

        // Here we do not have any
        //  changeStreamObservable.stop()
        // to stop underlying change stream cursor
        // see https://docs.mongodb.com/manual/reference/method/db.collection.watch/#behavior

        changeStreamObservable
      }.map { doc =>
        // just print notifications about arriving updates from change stream
        println(s"[$i] Got next doc")
        doc
      }.viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.last)(Keep.both)
        .run()

    ks
  }


  // we start to insert records into collection
  runInserts()

  system.scheduler.scheduleOnce(5.second) {
    // here we open changestream to consume inserted records
    val ks = consume(1)

    system.scheduler.scheduleOnce(5.second) {
      // here we close akkastream, and soon after we got exception
      // Problem: I do not see any way to close underlying cursor from `watch`
      ks.shutdown()
    }(system.dispatcher)
  }(system.dispatcher)

  // schedule termination to complete
  system.scheduler.scheduleOnce(15.second) {
    system.terminate()
    ()
  }(system.dispatcher)
}


