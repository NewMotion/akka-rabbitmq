package com.thenewmotion.akka.rabbitmq

import org.specs2.mock.Mockito
import akka.testkit.TestFSMRef
import akka.actor.{ ActorRef, Props }
import ConnectionActor._
import com.rabbitmq.client.ShutdownSignalException
import scala.concurrent.duration._
import scala.collection.immutable.Iterable
import java.io.IOException

/**
 * @author Yaroslav Klymko
 */
class ConnectionActorSpec extends ActorSpec with Mockito {
  "ConnectionActor" should {
    "try to connect on startup" in new TestScope {
      actorRef ! Connect
      state mustEqual connected
      val order = inOrder(factory, connection, setup, actor)
      there was one(factory).newConnection()
      there was one(connection).addShutdownListener(any)
      there was one(setup).apply(connection, actorRef)
    }
    "not reconnect if has connection" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actorRef ! Connect
      state mustEqual connected
    }
    "try to reconnect if failed to connect" in new TestScope {
      factory.newConnection throws new IOException
      actorRef ! Connect
      state mustEqual disconnected
    }
    "try to reconnect if can't create new channel" in new TestScope {
      connection.createChannel() throws new IOException
      actorRef.setState(Connected, Connected(connection, None))
      actorRef ! create
      state mustEqual disconnected
    }
    "attempt to connect on Connect message" in new TestScope {
      factory.newConnection throws new IOException thenReturns connection
      actorRef ! Connect
      state mustEqual disconnected
      actorRef ! Connect
      state mustEqual connected
    }
    "reconnect on ShutdownSignalException from server" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actor.shutdownCompleted(shutdownSignal())
      state mustEqual connected
    }
    "keep trying to reconnect on ShutdownSignalException from server" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      factory.newConnection throws new IOException thenThrow new IOException thenReturns connection
      actor.shutdownCompleted(shutdownSignal())
      state mustEqual disconnected
    }

    "not reconnect on ShutdownSignalException from client" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actor.shutdownCompleted(shutdownSignal(initiatedByApplication = true))
      there was no(factory).newConnection()
      state mustEqual disconnected
    }
    "create children actor with channel" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actorRef ! create
      expectMsg(channel)
      expectMsg(ChannelCreated(testActor))
    }
    "create children actor without channel if failed to create new channel" in new TestScope {
      connection.createChannel() throws new IOException
      actorRef.setState(Connected, Connected(connection, None))
      actorRef ! create
      expectMsg(ParentShutdownSignal)
      expectMsg(ChannelCreated(testActor))
      state mustEqual disconnected
    }
    "create children actor without channel" in new TestScope {
      actorRef ! create
      expectMsg(ChannelCreated(testActor))
    }
    "notify children if connection lost" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actor.shutdownCompleted(shutdownSignal())
      expectMsg(ParentShutdownSignal)
    }
    "notify children when connection established" in new TestScope {
      actorRef ! Connect
      expectMsg(channel)
    }
    "close connection on shutdown" in new TestScope {
      actorRef.setState(Connected, Connected(connection, None))
      actorRef.stop()
      there was one(connection).close()
    }
  }

  private abstract class TestScope extends ActorScope {
    val channel = mock[Channel]
    val connection = {
      val connection = mock[Connection]
      connection.isOpen returns true
      connection.createChannel() returns channel
      connection
    }
    val factory = {
      val factory = mock[ConnectionFactory]
      factory.newConnection() returns connection
      factory
    }
    val create = CreateChannel(null)
    val reconnectionDelay = FiniteDuration(10, SECONDS)
    val setup = mock[(Connection, ActorRef) => Any]
    val actorRef = TestFSMRef(new TestConnectionActor)

    def actor = actorRef.underlyingActor.asInstanceOf[ConnectionActor]
    def state: (State, Data) = actorRef.stateName -> actorRef.stateData
    def disconnected = Disconnected -> NoConnection
    def connected = Connected -> Connected(connection, None)

    def shutdownSignal(initiatedByApplication: Boolean = false) = {
      val shutdownSignal = mock[ShutdownSignalException]
      shutdownSignal.isInitiatedByApplication returns initiatedByApplication
      shutdownSignal
    }

    class TestConnectionActor extends ConnectionActor(factory, reconnectionDelay, setup) {
      override def children = Iterable(testActor)
      override def newChild(props: Props, name: Option[String]) = testActor
      override def preStart() {}
    }
  }
}
