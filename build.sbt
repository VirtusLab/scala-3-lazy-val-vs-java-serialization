val AkkaVersion = "2.9.4"

name := "java-serialization-trouble-with-lazy-val"

scalaVersion := "3.3.3"

resolvers += "Akka library repository" at "https://repo.akka.io/maven"

run / fork := true

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.5.6"
)
