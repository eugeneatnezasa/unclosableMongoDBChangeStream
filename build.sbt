name := "mongodbChangeStream"

version := "0.1"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.8.0",
  "org.reactivestreams" % "reactive-streams" % "1.0.2",
  "com.typesafe.akka" %% "akka-stream" % "2.5.25",
  "org.mongodb" % "mongodb-driver-async" % "3.12.1",
)