package org.ergoplatform.nodeView.wallet

import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R1}
import org.ergoplatform._
import org.ergoplatform.db.DBSpec
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.mempool.ErgoMemPoolReader
import org.ergoplatform.nodeView.wallet.WalletScanLogic.ScanResults
import org.ergoplatform.nodeView.wallet.persistence.{OffChainRegistry, WalletRegistry, WalletStorage}
import org.ergoplatform.nodeView.wallet.requests.{AssetIssueRequest, PaymentRequest}
import org.ergoplatform.nodeView.wallet.scanning.{EqualsScanningPredicate, ScanRequest, ScanWalletInteraction}
import org.ergoplatform.nodeView.wallet.scanning.LegacyScan
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.fixtures.WalletFixture
import org.ergoplatform.utils.MempoolTestHelpers
import org.ergoplatform.utils.generators.{ErgoTransactionGenerators, WalletGenerators}
import org.ergoplatform.utils.{ErgoPropertyTest, WalletTestOps}
import org.ergoplatform.wallet.interface4j.SecretString
import org.ergoplatform.wallet.Constants.{PaymentsScanId, ScanId}
import org.ergoplatform.wallet.boxes.BoxSelector.BoxSelectionResult
import org.ergoplatform.wallet.boxes.{ErgoBoxSerializer, ReplaceCompactCollectBoxSelector, TrackedBox}
import org.ergoplatform.wallet.crypto.ErgoSignature
import org.ergoplatform.wallet.mnemonic.Mnemonic
import org.ergoplatform.wallet.secrets.ExtendedSecretKey
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import scorex.db.{LDBKVStore, LDBVersionedStore}
import scorex.util.encode.Base16
import sigmastate.Values.{ByteArrayConstant, EvaluatedValue}
import sigmastate.helpers.TestingHelpers.testBox
import sigmastate.{SType, Values}

import scala.util.Random

class ErgoWalletServiceSpec
  extends ErgoPropertyTest
    with WalletGenerators
    with MempoolTestHelpers
    with WalletTestOps
    with ErgoWalletSupport
    with ErgoTransactionGenerators
    with DBSpec
    with BeforeAndAfterAll {

  override val ergoSettings: ErgoSettings = settings

  private implicit val x: WalletFixture = new WalletFixture(settings, parameters, getCurrentView(_).vault)
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 4, sizeRange = 4)
  private lazy val pks = getPublicKeys.toList
  private val masterKey = ExtendedSecretKey.deriveMasterKey(Mnemonic.toSeed(SecretString.create("edge talent poet tortoise trumpet dose")))

  override def afterAll(): Unit = try super.afterAll() finally x.stop()

  private def initialState(store: LDBKVStore, versionedStore: LDBVersionedStore, mempool: Option[ErgoMemPoolReader] = None) = {
    ErgoWalletState(
      new WalletStorage(store, settings),
      secretStorageOpt = Option.empty,
      new WalletRegistry(versionedStore)(settings.walletSettings),
      OffChainRegistry.empty,
      outputsFilter = Option.empty,
      WalletVars(Some(defaultProver), Seq.empty, None),
      stateReaderOpt = Option.empty,
      mempoolReaderOpt = mempool,
      utxoStateReaderOpt = Option.empty,
      parameters,
      maxInputsToUse = 1000,
      rescanInProgress = false
    )
  }

  property("restoring wallet should fail if pruning is enabled") {
    withVersionedStore(2) { versionedStore =>
      withStore { store =>
        val walletState = initialState(store, versionedStore)
        val walletService = new ErgoWalletServiceImpl(settings)
        val settingsWithPruning = settings.copy(nodeSettings = settings.nodeSettings.copy(blocksToKeep = 0))
        walletService.restoreWallet(
          walletState,
          settingsWithPruning,
          mnemonic = SecretString.create("x"),
          mnemonicPassOpt = None,
          walletPass = SecretString.create("y")
        ).failed.get.getMessage shouldBe "Unable to restore wallet when pruning is enabled"
      }
    }
  }

  property("it should prepare unsigned transaction") {
    val inputBoxes = {
      Seq(
        TrackedBox(
          ErgoLikeTransaction(IndexedSeq(), IndexedSeq()),
          creationOutIndex = 0,
          None,
          testBox(1L, Values.TrueLeaf.toSigmaProp, 0),
          Set(PaymentsScanId)
        )
      )
    }

    forAll(ergoBoxCandidateGen, ergoBoxCandidateGen, validErgoTransactionGen, proveDlogGen) {
      case (outputCandidate, outputChangeCandidate, (ergoBoxes, _), proveDlog) =>
        val selectionResult = BoxSelectionResult(inputBoxes, Seq(outputChangeCandidate))
        val tx = prepareUnsignedTransaction(Seq(outputCandidate), startHeight, selectionResult, ergoBoxes, Option(proveDlog)).get
        tx.inputs shouldBe inputBoxes.map(_.box.id).map(id => new UnsignedInput(id))
        tx.dataInputs shouldBe ergoBoxes.map(dataInputBox => DataInput(dataInputBox.id))
        tx.outputCandidates.size shouldBe 2
        tx.outputCandidates.map(_.value).sum shouldBe outputCandidate.value + outputChangeCandidate.value

        val txWithChangeBoxesButNoChangeAddress =
          prepareUnsignedTransaction(Seq(outputCandidate), startHeight, selectionResult, ergoBoxes, Option.empty)
        txWithChangeBoxesButNoChangeAddress.isFailure shouldBe true
    }
  }

  property("it should generate valid box candidates from payment request") {
    forAll(validErgoTransactionGen) {
      case (ergoBoxes, _) =>
        val paymentRequest = PaymentRequest(pks.head, 1, Seq.empty, Map.empty)
        val paymentCandidates = requestsToBoxCandidates(Seq(paymentRequest), ergoBoxes.head.id, startHeight, parameters, pks).get
        paymentCandidates shouldBe List(new ErgoBoxCandidate(value = 1, ergoTree = pks.head.script, startHeight))
    }
  }

  property("it should generate valid box candidates from asset issue requests") {
    forAll(validErgoTransactionGen) {
      case (ergoBoxes, _) =>
        val ergoBox = ergoBoxes.head

        val registers: Option[Map[NonMandatoryRegisterId, EvaluatedValue[_ <: SType]]] = Option(Map(ErgoBox.R4 -> sigmastate.Values.FalseLeaf))
        val illegalAssetIssueRequest = AssetIssueRequest(address = pks.head, Some(1), amount = 1, "test", "test", 4, registers)
        val invalidCandidates = requestsToBoxCandidates(Seq(illegalAssetIssueRequest), ergoBox.id, startHeight, parameters, pks)
        invalidCandidates.failed.get.getMessage shouldBe "Additional registers contain R0...R6"

        val assetIssueRequestWithoutAddress = AssetIssueRequest(addressOpt = Option.empty, Some(1), amount = 1, "test", "test", 4, Option.empty)
        val missingAddressCandidates = requestsToBoxCandidates(Seq(assetIssueRequestWithoutAddress), ergoBox.id, startHeight, parameters, Seq.empty)
        missingAddressCandidates.failed.get.getMessage shouldBe "No address available for box locking"

        val assetIssueRequestWithoutValue = AssetIssueRequest(address = pks.head, valueOpt = Option.empty, amount = 1, "test", "test", 4, Option.empty)
        val missingValueCandidates = requestsToBoxCandidates(Seq(assetIssueRequestWithoutValue), ergoBox.id, startHeight, parameters, Seq.empty).get.head
        missingValueCandidates.value > 0 shouldBe true

        val assetIssueRequest = AssetIssueRequest(address = pks.head, Some(1), amount = 1, "test-name", "test-description", 4, Option.empty)
        val validCandidate = requestsToBoxCandidates(Seq(assetIssueRequest), ergoBox.id, startHeight, parameters, Seq.empty).get.head
        validCandidate.value shouldBe 1
        validCandidate.additionalRegisters shouldBe
          Map(
            ErgoBox.R4 -> ByteArrayConstant("test-name".getBytes("UTF-8")),
            ErgoBox.R5 -> ByteArrayConstant("test-description".getBytes("UTF-8")),
            ErgoBox.R6 -> ByteArrayConstant("4".getBytes("UTF-8")),
          )
        validCandidate.additionalTokens.toMap shouldBe Map(ergoBox.id -> 1)
        validCandidate.creationHeight shouldBe startHeight
        validCandidate.ergoTree shouldBe pks.head.script
    }
  }

  property("it should get scan confirmed and unconfirmed transactions") {
    forAll(Gen.nonEmptyListOf(trackedBoxGen), modifierIdGen) { case (boxes, txId) =>
      withVersionedStore(10) { versionedStore =>
        withStore { store =>
          val allBoxes = {
            val unspentBoxes = boxes.map(bx => bx.copy(spendingHeightOpt = None, spendingTxIdOpt = None, scans = Set(ScanId @@ 0)))
            val spentBox = boxes.head.copy(spendingHeightOpt = Some(100), spendingTxIdOpt = Some(txId), scans = Set(ScanId @@ 0))
            unspentBoxes :+ spentBox
          }
          val encodedBoxes = allBoxes.map { box =>
            Base16.encode(ErgoBoxSerializer.toBytes(box.box))
          }

          val paymentRequest = PaymentRequest(pks.head, 50000, Seq.empty, Map.empty)
          val boxSelector = new ReplaceCompactCollectBoxSelector(settings.walletSettings.maxInputs, settings.walletSettings.optimalInputs, None)

          val walletService = new ErgoWalletServiceImpl(ergoSettings)
          val unconfirmedTx =
            walletService.generateTransaction(
              initialState(store, versionedStore),
              boxSelector,
              Seq(paymentRequest),
              inputsRaw = encodedBoxes,
              dataInputsRaw = Seq.empty,
              sign = true
            ).get.asInstanceOf[ErgoTransaction]

          // let's create wallet state with an unconfirmed transaction in mempool
          val wState = initialState(store, versionedStore, Some(new FakeMempool(Seq(unconfirmedTx))))
          val signedTx1 =
            walletService.generateTransaction(wState, boxSelector, Seq(paymentRequest), inputsRaw = encodedBoxes, dataInputsRaw = Seq.empty, sign = true)
              .get.asInstanceOf[ErgoTransaction]
          val walletTx1 = WalletTransaction(signedTx1, 100, Seq(ScanId @@ 0))

          // let's update wallet registry with a transaction from a block
          val genesisBlock = makeGenesisBlock(pks.head.pubkey, randomNewAsset)
          wState.registry.updateOnBlock(ScanResults(allBoxes, Seq.empty, Seq(walletTx1)), genesisBlock.id, blockHeight = 100).get

          // transaction should be retrieved by only a scan id that was associated with it
          val txs1 = walletService.getScanTransactions(wState, ScanId @@ 0, 100)
          assert(txs1.nonEmpty)
          val txs2 = walletService.getScanTransactions(wState, ScanId @@ 1, 100)
          assert(txs2.isEmpty)

          // let's test that unconfirmed transaction is retrieved
          val scanId =
            walletService.addScan(wState, ScanRequest("foo", EqualsScanningPredicate(R1, ByteArrayConstant(pks.head.script.bytes)), Some(ScanWalletInteraction.Off), Some(false)))
              .get._1.scanId

          val txs3 = walletService.getScanTransactions(wState, scanId, 100, includeUnconfirmed = true)
          txs3.size shouldBe 1

          txs3.head.wtx.tx.id shouldBe unconfirmedTx.id
        }
      }
    }
  }

  property("it should get spent and unspent wallet boxes") {
    forAll(Gen.nonEmptyListOf(trackedBoxGen), modifierIdGen) { case (boxes, txId) =>
      withVersionedStore(10) { versionedStore =>
        withStore { store =>
          val wState = initialState(store, versionedStore)
          val blockId = modifierIdGen.sample.get
          val unspentBoxes = boxes.map(bx => bx.copy(spendingHeightOpt = None, spendingTxIdOpt = None, scans = Set(PaymentsScanId)))
          val spentBox = boxes.head.copy(spendingHeightOpt = Some(10000), spendingTxIdOpt = Some(txId), scans = Set(PaymentsScanId))
          val allBoxes = unspentBoxes :+ spentBox
          wState.registry.updateOnBlock(ScanResults(allBoxes, Seq.empty, Seq.empty), blockId, 100).get

          val walletService = new ErgoWalletServiceImpl(settings)
          val actualUnspentOnlyWalletBoxes = walletService.getWalletBoxes(wState, unspentOnly = true, considerUnconfirmed = false).toList
          val expectedUnspentOnlyWalletBoxes = unspentBoxes.map(x => WalletBox(x, wState.fullHeight)).sortBy(_.trackedBox.inclusionHeightOpt)
          actualUnspentOnlyWalletBoxes should contain theSameElementsAs expectedUnspentOnlyWalletBoxes

          val actualWalletBoxes = walletService.getWalletBoxes(wState, unspentOnly = false, considerUnconfirmed = false).toList
          val expectedWalletBoxes = allBoxes.map(x => WalletBox(x, wState.fullHeight)).sortBy(_.trackedBox.inclusionHeightOpt)
          actualWalletBoxes should contain theSameElementsAs expectedWalletBoxes
        }
      }
    }
  }

  property("it should generate signed and unsigned transaction") {
    withVersionedStore(2) { versionedStore =>
      withStore { store =>
        val wState = initialState(store, versionedStore)

        val encodedBoxes =
          boxesAvailable(makeGenesisBlock(pks.head.pubkey, randomNewAsset), pks.head.pubkey)
            .map { box =>
              Base16.encode(ErgoBoxSerializer.toBytes(box))
            }
        val paymentRequest = PaymentRequest(pks.head, 50000, Seq.empty, Map.empty)
        val boxSelector = new ReplaceCompactCollectBoxSelector(settings.walletSettings.maxInputs, settings.walletSettings.optimalInputs, None)

        val (tx, inputs, dataInputs) = generateUnsignedTransaction(wState, boxSelector, Seq(paymentRequest), inputsRaw = encodedBoxes, dataInputsRaw = Seq.empty).get
        dataInputs shouldBe empty
        inputs.size shouldBe 1
        tx.inputs.size shouldBe 1
        tx.outputs.size shouldBe 2
        tx.outputs.map(_.value).sum shouldBe inputs.map(_.value).sum

        val walletService = new ErgoWalletServiceImpl(settings)
        val signedTx = walletService.generateTransaction(wState, boxSelector, Seq(paymentRequest), inputsRaw = encodedBoxes, dataInputsRaw = Seq.empty, sign = true).get.asInstanceOf[ErgoTransaction]

        ErgoSignature.verify(signedTx.messageToSign, signedTx.inputs.head.spendingProof.proof, pks.head.pubkey.h) shouldBe true
        signedTx.inputs.size shouldBe 1
        signedTx.outputs.size shouldBe 2

      }
    }
  }

  property("it should process unlock using preEip3Derivation") {
    withVersionedStore(2) { versionedStore =>
      withStore { store =>
        val walletState = initialState(store, versionedStore)
        val unlockedWalletState = processUnlock(walletState, masterKey, usePreEip3Derivation = true).get
        unlockedWalletState.storage.readAllKeys().size shouldBe 1
        unlockedWalletState.storage.readAllKeys() should contain(masterKey.publicKey)
        unlockedWalletState.walletVars.proverOpt shouldNot be(empty)
      }
    }
  }

  property("it should process unlock without preEip3Derivation") {
    withVersionedStore(2) { versionedStore =>
      withStore { store =>
        val walletState = initialState(store, versionedStore)
        val unlockedWalletState = processUnlock(walletState, masterKey, usePreEip3Derivation = false).get
        unlockedWalletState.storage.readAllKeys().size shouldBe 1
        unlockedWalletState.storage.readChangeAddress shouldNot be(empty)
        unlockedWalletState.walletVars.proverOpt shouldNot be(empty)
      }
    }
  }

  property("it should migrate legacy scans") {
    forAll(Gen.nonEmptyListOf(externalAppGen)) { scans =>
      withVersionedStore(2) { versionedStore =>
        withStore { store =>
          val state = initialState(store, versionedStore)
          scans.foreach { scan =>
            val legacyScan =
              LegacyScan(scan.scanId.toShort,
                scan.scanName,
                scan.trackingRule,
                scan.walletInteraction,
                scan.removeOffchain
              )
            state.storage.addLegacyScan(legacyScan).get
          }
          state.storage.getLegacyScans shouldNot be(empty)
          val legacyScanCount = state.storage.getLegacyScans.size
          val (newScans, newState) = state.migrateScans(settings).get
          newScans.size shouldBe legacyScanCount
          newState.walletVars.externalScans shouldBe newScans
          state.storage.getLegacyScans shouldBe empty
          state.storage.allScans.size shouldBe legacyScanCount
        }
      }
    }
  }

  property("it should lock/unlock wallet") {
    withVersionedStore(2) { versionedStore =>
      withStore { store =>
        val walletState = initialState(store, versionedStore)
        val walletService = new ErgoWalletServiceImpl(settings)
        val pass = Random.nextString(10)
        val initializedState = walletService.initWallet(walletState, settings, SecretString.create(pass), Option.empty).get._2

        // Wallet unlocked after init, so we're locking it
        val initLockedWalletState = walletService.lockWallet(initializedState)
        initLockedWalletState.secretStorageOpt.get.isLocked shouldBe true
        initLockedWalletState.walletVars.proverOpt shouldBe empty

        val unlockedWalletState = walletService.unlockWallet(initLockedWalletState, SecretString.create(pass), usePreEip3Derivation = true).get
        unlockedWalletState.secretStorageOpt.get.isLocked shouldBe false
        unlockedWalletState.storage.readAllKeys().size shouldBe 1
        unlockedWalletState.walletVars.proverOpt shouldNot be(empty)

        val lockedWalletState = walletService.lockWallet(unlockedWalletState)
        lockedWalletState.secretStorageOpt.get.isLocked shouldBe true
        lockedWalletState.walletVars.proverOpt shouldBe empty

        val finalUnlockedState = walletService.unlockWallet(lockedWalletState, SecretString.create(pass), usePreEip3Derivation = true).get
        finalUnlockedState.secretStorageOpt.get.isLocked shouldBe false
        finalUnlockedState.storage.readAllKeys().size shouldBe 1
        finalUnlockedState.walletVars.proverOpt shouldNot be(empty)
      }
    }
  }
}
