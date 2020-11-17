package com.wavesplatform.dex.grpc.integration.clients.state

import cats.kernel.Monoid
import cats.syntax.semigroup._
import com.wavesplatform.dex.domain.utils.ScorexLogging
import com.wavesplatform.dex.grpc.integration.clients.state.BlockchainEvent._
import com.wavesplatform.dex.grpc.integration.clients.state.BlockchainStatus._
import com.wavesplatform.dex.grpc.integration.clients.state.StatusUpdate.LastBlockHeight

import scala.collection.immutable.Queue

/**
 * The extension guarantees:
 * 1. During initialization: events will be sent after initial blocks
 * 2. Events has causal ordering
 */

object StatusTransitions extends ScorexLogging {

  def apply(origStatus: BlockchainStatus, event: BlockchainEvent): StatusUpdate = {
    val r = origStatus match {
      case origStatus: Normal =>
        event match {
          case Appended(block) =>
            origStatus.mainFork.withBlock(block) match {
              case Left(e) =>
                log.error(s"Forcibly rollback, because of error: $e")
                val previousBlock = origStatus.mainFork.history.tail.headOption
                val (newFork, _) = previousBlock match {
                  case Some(previousBlock) => origStatus.mainFork.dropAfter(previousBlock.ref)
                  case None => origStatus.mainFork.dropAll
                }

                StatusUpdate(
                  newStatus = TransientRollback(
                    newFork = newFork,
                    newForkChanges = Monoid.empty[BlockchainBalance],
                    previousForkHeight = origStatus.currentHeightHint,
                    previousForkDiffIndex = origStatus.mainFork.diffIndex
                  ),
                  updatedLastBlockHeight = LastBlockHeight.RestartRequired(math.max(0, origStatus.currentHeightHint - 1))
                )
              case Right(updatedFork) =>
                StatusUpdate(
                  newStatus = Normal(updatedFork, block.ref.height),
                  updatedBalances = block.changes,
                  updatedLastBlockHeight =
                    if (block.tpe == WavesBlock.Type.Block) LastBlockHeight.Updated(block.ref.height) else LastBlockHeight.NotChanged
                )
            }

          case RolledBackTo(commonBlockRef) =>
            val (commonFork, droppedDiff) = origStatus.mainFork.dropAfter(commonBlockRef)
            StatusUpdate(
              newStatus = TransientRollback(
                newFork = commonFork,
                newForkChanges = Monoid.empty[BlockchainBalance],
                previousForkHeight = origStatus.currentHeightHint,
                previousForkDiffIndex = droppedDiff
              )
            )

          case SyncFailed(height) =>
            val (commonFork, droppedDiff) = origStatus.mainFork.dropFrom(height)
            StatusUpdate(
              newStatus = TransientRollback(
                newFork = commonFork,
                newForkChanges = Monoid.empty[BlockchainBalance],
                previousForkHeight = origStatus.currentHeightHint,
                previousForkDiffIndex = droppedDiff
              )
            )

          case _ =>
            // Won't happen
            log.error("Unexpected transition, ignore")
            StatusUpdate(origStatus)
        }

      case origStatus: TransientRollback =>
        event match {
          case Appended(block) =>
            origStatus.newFork.withBlock(block) match {
              case Left(e) =>
                log.error(s"Forcibly rollback, because of error: $e")
                StatusUpdate(
                  TransientRollback(
                    newFork = WavesFork(List.empty),
                    newForkChanges = Monoid.empty[BlockchainBalance],
                    previousForkHeight = origStatus.previousForkHeight,
                    previousForkDiffIndex = origStatus.previousForkDiffIndex
                  ),
                  updatedLastBlockHeight =
                    LastBlockHeight.RestartRequired(math.max(1, origStatus.previousForkHeight - 1)) // TODO duplication of max
                )

              case Right(updatedNewFork) =>
                val newForkChanges = origStatus.newForkChanges |+| block.changes
                if (block.tpe == WavesBlock.Type.Block)
                  StatusUpdate(
                    newStatus = origStatus.copy(
                      newFork = updatedNewFork,
                      newForkChanges = newForkChanges
                    )
                    // updatedHeight = updatedHeight // We don't notify about updates until we get the same height
                  )
                else
                  StatusUpdate(
                    newStatus = TransientResolving( // We don't a height, because a micro block comes after all blocks
                      mainFork = updatedNewFork,
                      stash = Queue.empty,
                      currentHeightHint = block.ref.height
                    ),
                    updatedBalances = newForkChanges,
                    requestBalances = origStatus.previousForkDiffIndex.without(newForkChanges.diffIndex),
                    updatedLastBlockHeight =
                      if (block.tpe == WavesBlock.Type.Block) LastBlockHeight.Updated(block.ref.height) else LastBlockHeight.NotChanged
                  )
            }

          case RolledBackTo(commonBlockRef) =>
            val (commonFork, droppedDiff) = origStatus.newFork.dropAfter(commonBlockRef)
            StatusUpdate(
              newStatus = TransientRollback(
                newFork = commonFork,
                newForkChanges = Monoid.empty[BlockchainBalance],
                previousForkHeight = origStatus.previousForkHeight,
                previousForkDiffIndex = origStatus.previousForkDiffIndex |+| droppedDiff
              )
            )

          case SyncFailed(height) =>
            val (commonFork, droppedDiff) = origStatus.newFork.dropFrom(height)
            StatusUpdate(
              newStatus = TransientRollback(
                newFork = commonFork,
                newForkChanges = Monoid.empty[BlockchainBalance],
                previousForkHeight = origStatus.previousForkHeight,
                previousForkDiffIndex = origStatus.previousForkDiffIndex |+| droppedDiff
              )
            )

          case _ =>
            // Won't happen
            log.error("Unexpected transition, ignore")
            StatusUpdate(origStatus)
        }

      case origStatus: TransientResolving =>
        event match {
          // TODO We can stuck if waiting for DataReceiving in stash!!!!
          case DataReceived(updates) =>
            // TODO optimize. Probably we don't need to request all data. E.g. we hadn't this address in last 100 blocks and we got its balance 101 block before
            val init = StatusUpdate(
              newStatus = Normal(origStatus.mainFork, origStatus.currentHeightHint),
              updatedBalances = updates
            )

            origStatus.stash.foldLeft(init) {
              case (r, x) => r |+| apply(r.newStatus, x)
            }

          case _ => StatusUpdate(origStatus.copy(stash = origStatus.stash.enqueue(event)))
        }
    }

    log.info(s"${origStatus.name} + $event = $r")
    r
  }

}
