import akka.actor.Address
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, Subscribe}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import akka.actor.typed.pubsub.{PubSub, Topic}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise

object ClusterApp:

  enum Mode(val behavior: Behavior[Command]):
    case Leader(b: Behavior[Command]) extends Mode(b)
    case Worker(b: Behavior[Command]) extends Mode(b)

    def isLeader: Boolean = this match
      case Mode.Leader(_) => true
      case Mode.Worker(_) => false

  sealed trait Command
  case class Message(content: String) extends Command:
    lazy val bomb: String =
      Thread.sleep(200)
      "BOMB: " + content

  object LeaderNode:
    def apply(): Behavior[Command] = Behaviors.setup { context =>
      val pubSub = PubSub(context.system)
      val topic: ActorRef[Topic.Command[Message]] = pubSub.topic[Message]("cluster-messages")

      println("started master behavior")

      Behaviors.receiveMessage:
        case msg: Message =>
          Thread.sleep(50)
          context.log.info(s"Master sending message: ${msg.content}")
          topic ! Topic.Publish(msg)
          Behaviors.same
    }

  object WorkerNode:
    def apply(): Behavior[Command] = Behaviors.setup { context =>
      val pubSub = PubSub(context.system)
      val topic: ActorRef[Topic.Command[Message]] = pubSub.topic[Message]("cluster-messages")

      topic ! Topic.Subscribe(context.self)

      Behaviors.receiveMessage:
        case msg: Message =>
          println(s"Worker received message: ${msg.content}")
          println(s"Disarmed the bomb: ${msg.bomb}")
          Behaviors.same
    }

  def main(args: Array[String]): Unit =
    println(s"Starting with args ${args.mkString(", ")}")
    if args.isEmpty then println("provide 'leader' or 'worker' as sole argument")
    else
      val label = args(0)
      val mode = label match
        case "leader" => Mode.Leader(LeaderNode())
        case "worker" => Mode.Worker(WorkerNode())

      startup(mode)

  def startup(mode: Mode): Unit =
    val port = mode match
      case Mode.Leader(_) => 2551
      case Mode.Worker(_) => 2552

    val config = ConfigFactory
      .parseString(s"""
        akka {
          actor {
            provider = cluster

            allow-java-serialization = on
          }
          remote {
            artery {
              canonical.hostname = "127.0.0.1"
              canonical.port = $port
            }
          }
          cluster {
            seed-nodes = [
              "akka://ClusterSystem@127.0.0.1:2551",
              "akka://ClusterSystem@127.0.0.1:2552"
            ]
            downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
          }
        }
      """)
      .withFallback(ConfigFactory.load())

    val system = ActorSystem(mode.behavior, "ClusterSystem", config)

    val whenClusterIsUp = Promise[Unit]()

    system.systemActorOf(
      Behaviors.setup[MemberEvent] { context =>
        val cluster = Cluster(context.system)
        cluster.subscriptions ! Subscribe(context.self, classOf[MemberUp])

        val members = collection.mutable.Set[Address]()

        Behaviors.receiveMessage {
          case MemberUp(member) =>
            context.log.info("Member is Up: {}", member.address)
            members += member.address

            // if everyone joined, start sending messages
            if members.size == 2 && mode.isLeader then whenClusterIsUp.trySuccess(())

            Behaviors.same
          case anyOther =>
            context.log.info(s"Got: $anyOther")
            Behaviors.same
        }
      },
      "clusterListener"
    )

    system.log.info("Waiting for cluster to form up...")

    import system.executionContext

    whenClusterIsUp.future.foreach { _ =>
      system.log.info("Cluster formed up! Starting to send messages.")
      Iterator.iterate(0)(_ + 1).map(idx => s"message no.$idx").map(Message(_)).foreach { msg =>
        system ! msg
        msg.bomb // touch bomb locally
        Thread.sleep(1000)
      }
    }
