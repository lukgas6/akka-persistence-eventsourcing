package io.scalac.seed.domain

import akka.actor._
import akka.persistence._
import io.scalac.seed.common.Acknowledge

object AggregateRoot {

  trait State
  case object Uninitialized extends State
  case object Removed extends State

  trait Event

  trait Command
  case object Remove extends Command
  case object Kill extends Command
  case object GetState extends Command

  /**
   * Specifies how many events should be processed before new snapshot is taken.
   */
  val eventsPerSnapshot = 10
}

/**
 * Base class for other aggregates.
 * It includes such functionality as: snapshot management, publishing applied events to Event Bus, handling processor recovery.
 *
 */
trait AggregateRoot extends EventsourcedProcessor with ActorLogging {

  import AggregateRoot._

  override def processorId: String

  protected var state: State = Uninitialized

  private var eventsSinceLastSnapshot = 0

  /**
   * Updates internal processor state according to event that is to be applied.
   *
   * @param evt Event to apply
   */
  def updateState(evt: Event): Unit


  /**
   * This method should be used as a callback handler for persist() method.
   *
   * @param evt Event that has been persisted
   */
  protected def afterEventPersisted(evt: Event): Unit = {
    eventsSinceLastSnapshot += 1
    if (eventsSinceLastSnapshot >= eventsPerSnapshot) {
      log.debug(s"${eventsPerSnapshot} events reached, saving snapshot")
      saveSnapshot(state)
      eventsSinceLastSnapshot = 0
    }
    updateAndRespond(evt)
    publish(evt)
  }

  private def updateAndRespond(evt: Event): Unit = {
    updateState(evt)
    respond
  }

  protected def respond: Unit = {
    sender() ! state
    context.parent ! Acknowledge(processorId)
  }

  private def publish(event: Event) =
    context.system.eventStream.publish(event)

  override val receiveRecover: Receive = {
    case evt: Event =>
      eventsSinceLastSnapshot += 1
      updateState(evt)
    case SnapshotOffer(metadata, state: State) =>
      restoreFromSnapshot(metadata, state)
      log.debug("recovering aggregate from snapshot")
  }

  protected def restoreFromSnapshot(metadata: SnapshotMetadata, state: State)

}