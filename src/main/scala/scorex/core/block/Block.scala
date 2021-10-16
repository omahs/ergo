package scorex.core.block

import scorex.core.block.Block.{Timestamp, Version}
import scorex.core.consensus.History
import scorex.core.serialization.ScorexSerializer
import scorex.core.transaction._
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.{NodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import scorex.util.ModifierId

/**
  * A block is an atomic piece of data network participates are agreed on.
  *
  * A block has:
  * - transactional data: a sequence of transactions, where a transaction is an atomic state update.
  * Some metadata is possible as well(transactions Merkle tree root, state Merkle tree root etc).
  *
  * - consensus data to check whether block was generated by a right party in a right way. E.g.
  * "baseTarget" & "generatorSignature" fields in the Nxt block structure, nonce & difficulty in the
  * Bitcoin block structure.
  *
  * - a signature(s) of a block generator(s)
  *
  * - additional data: block structure version no, timestamp etc
  */

trait Block[TX <: Transaction]
  extends TransactionsCarryingPersistentNodeViewModifier {

  def version: Version

  def timestamp: Timestamp
}

object Block {
  type BlockId = ModifierId
  type Timestamp = Long
  type Version = Byte

  val BlockIdLength: Int = NodeViewModifier.ModifierIdSize

}