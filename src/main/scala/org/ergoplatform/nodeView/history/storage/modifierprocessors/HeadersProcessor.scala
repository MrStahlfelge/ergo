package org.ergoplatform.nodeView.history.storage.modifierprocessors

import com.google.common.primitives.Ints
import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.modifiers.history.{Header, HistoryModifierSerializer}
import org.ergoplatform.modifiers.{ErgoFullBlock, ErgoPersistentModifier}
import org.ergoplatform.nodeView.history.HistoryConfig
import org.ergoplatform.nodeView.history.storage.HistoryStorage
import org.ergoplatform.settings.Algos
import org.ergoplatform.settings.Constants.hashLength
import scorex.core.NodeViewModifier._
import scorex.core.consensus.History.ProgressInfo
import scorex.core.utils.ScorexLogging

import scala.util.Try

trait HeadersProcessor extends ScorexLogging {

  protected val config: HistoryConfig

  def bestFullBlockOpt: Option[ErgoFullBlock]

  def typedModifierById[T <: ErgoPersistentModifier](id: ModifierId): Option[T]

  /**
    * Id of best header with transactions and proofs. None in regime that do not process transactions
    */
  def bestFullBlockIdOpt: Option[ModifierId] = None

  /**
    * @return height of best header
    */
  def height: Int = bestHeaderIdOpt.flatMap(id => heightOf(id)).getOrElse(0)

  /**
    * @param id - id of ErgoPersistentModifier
    * @return height of modifier with such id if is in History
    */
  def heightOf(id: ModifierId): Option[Int] = historyStorage.db.get(headerHeightKey(id))
    .map(b => Ints.fromByteArray(b.data))

  /**
    * @param m - header to process
    * @return ProgressInfo - info required for State to be consistent with History
    */
  protected def process(m: Header): ProgressInfo[ErgoPersistentModifier] = {
    val dataToInsert = toInsert(m)
    historyStorage.insert(m.id, dataToInsert)
    if (bestHeaderIdOpt.isEmpty || (bestHeaderIdOpt.get sameElements m.id)) {
      log.info(s"New best header ${m.encodedId}")
      //TODO Notify node view holder that it should download transactions ?
      ProgressInfo(None, Seq(), Seq(m))
    } else {
      log.info(s"New orphaned header ${m.encodedId}, best: ${}")
      ProgressInfo(None, Seq(), Seq())
    }
  }

  /**
    *
    * @param header - header we're going to remove from history
    * @return ids to remove, new data to apply
    */
  protected def toDrop(header: Header): (Seq[ByteArrayWrapper], Seq[(ByteArrayWrapper, ByteArrayWrapper)]) = {
    val modifierId = header.id
    val payloadModifiers = Seq(header.transactionsId, header.ADProofsId).filter(id => historyStorage.contains(id))
      .map(id => ByteArrayWrapper(id))

    val toRemove = Seq(headerDiffKey(modifierId),
      headerScoreKey(modifierId),
      ByteArrayWrapper(modifierId)) ++ payloadModifiers
    val bestHeaderKeyUpdate = if (bestHeaderIdOpt.exists(_ sameElements modifierId)) {
      Seq((BestHeaderKey, ByteArrayWrapper(header.parentId)))
    } else Seq()
    val bestFullBlockKeyUpdate = if (bestFullBlockIdOpt.exists(_ sameElements modifierId)) {
      Seq((BestFullBlockKey, ByteArrayWrapper(header.parentId)))
    } else Seq()
    (toRemove, bestFullBlockKeyUpdate ++ bestHeaderKeyUpdate)
  }

  protected def bestHeaderIdOpt: Option[ModifierId] = historyStorage.db.get(BestHeaderKey).map(_.data)

  /**
    * todo: avoid using require/ensuring, as they can be turned off by a compiler flag
    * todo: also, their intended semantics is different from what we're doing in this method
    *
    * @param m - header to validate
    * @return Succes() if header is valid, Failure(error) otherwise
    */
  protected def validate(m: Header): Try[Unit] = Try {
    if (m.isGenesis) {
      require(bestHeaderIdOpt.isEmpty, "Trying to append genesis block to non-empty history")
    } else {
      val parentOpt = historyStorage.modifierById(m.parentId)
      require(parentOpt.isDefined, "Parent header is no defined")
      require(!historyStorage.contains(m.id), "Header is already in history")
      require(Algos.blockIdDifficulty(m.powHash) >= expectedDifficulty(m),
        s"Block difficulty ${Algos.blockIdDifficulty(m.powHash)} is less than required ${expectedDifficulty(m)}")
      //TODO check timestamp
      //TODO check that block is not too old to prevent DDoS
    }
  }

  protected val historyStorage: HistoryStorage

  protected val BestHeaderKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(hashLength)(Header.ModifierTypeId))

  protected val BestFullBlockKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(hashLength)(-1))

  /**
    * @param id - header id
    * @return score of header with such id if is in History
    */
  protected def scoreOf(id: ModifierId): Option[BigInt] = historyStorage.db.get(headerScoreKey(id)).map(b => BigInt(b.data))

  /**
    * @param height - block height
    * @return ids of headers on chosen height.
    *         Seq.empty we don't have any headers on this height (e.g. it is too big or we bootstrap in PoPoW regime)
    *         single id if no forks on this height
    *         multiple ids if there are forks at chosen height
    */
  protected def headerIdsAtHeight(height: Int): Seq[ModifierId] = historyStorage.db.get(heightIdsKey(height: Int))
    .map(_.data).getOrElse(Array()).grouped(32).toSeq

  protected def headerDiffKey(id: ModifierId): ByteArrayWrapper = ByteArrayWrapper(Algos.hash("diff".getBytes ++ id))

  protected def headerScoreKey(id: ModifierId): ByteArrayWrapper = ByteArrayWrapper(Algos.hash("score".getBytes ++ id))

  protected def headerHeightKey(id: ModifierId): ByteArrayWrapper = ByteArrayWrapper(Algos.hash("height".getBytes ++ id))

  private def bestHeadersChainScore: BigInt = scoreOf(bestHeaderIdOpt.get).get

  private def toInsert(h: Header): Seq[(ByteArrayWrapper, ByteArrayWrapper)] = {
    val requiredDifficulty: BigInt = expectedDifficulty(h)
    if (h.isGenesis) {
      Seq((ByteArrayWrapper(h.id), ByteArrayWrapper(HistoryModifierSerializer.toBytes(h))),
        (BestHeaderKey, ByteArrayWrapper(h.id)),
        (headerHeightKey(h.id), ByteArrayWrapper(Ints.toByteArray(1))),
        (headerScoreKey(h.id), ByteArrayWrapper(requiredDifficulty.toByteArray)),
        (headerDiffKey(h.id), ByteArrayWrapper(requiredDifficulty.toByteArray)))
    } else {
      val blockScore = scoreOf(h.parentId).get + requiredDifficulty
      val bestRow: Seq[(ByteArrayWrapper, ByteArrayWrapper)] = if (blockScore > bestHeadersChainScore) {
        Seq((BestHeaderKey, ByteArrayWrapper(h.id)))
      } else {
        Seq()
      }
      val scoreRow = (headerScoreKey(h.id), ByteArrayWrapper(blockScore.toByteArray))
      val height = heightOf(h.parentId).get + 1
      val heightRow = (headerHeightKey(h.id), ByteArrayWrapper(Ints.toByteArray(height)))
      val headerIdsRow: (ByteArrayWrapper, ByteArrayWrapper) = (heightIdsKey(height),
        ByteArrayWrapper((headerIdsAtHeight(height) :+ h.id).flatten.toArray))
      val diffRow = (headerDiffKey(h.id), ByteArrayWrapper(requiredDifficulty.toByteArray))
      val modifierRow = (ByteArrayWrapper(h.id), ByteArrayWrapper(HistoryModifierSerializer.toBytes(h)))
      Seq(diffRow, scoreRow, heightRow, modifierRow, headerIdsRow) ++ bestRow
    }
  }

  private def heightIdsKey(height: Int): ByteArrayWrapper = ByteArrayWrapper(Algos.hash(Ints.toByteArray(height)))

  private val difficultyCalculator = new DifficultyCalculator

  private def expectedDifficulty(h: Header): BigInt = {
    if (h.isGenesis) {
      BigInt(Algos.initialDifficulty)
    } else if (difficultyCalculator.recalculationRequired(heightOf(h.parentId).get)) {
      ???
    } else {
      difficultyAt(h.parentId).get
    }
  }

  private def difficultyAt(id: ModifierId): Option[BigInt] = historyStorage.db.get(headerDiffKey(id)).map(b => BigInt(b.data))

}
