//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import scala.util.matching.Regex
import scala.io.Source
import org.scalatest.Suites
import org.scalatest.matchers.should._

abstract class TutorialSuite(
    val targetName: String, // See GeneratorUtils
    targetConfigs: String = "NoConfig",
    platformConfigs: String = "HostDebugFeatures_DefaultF1Config",
    tracelen: Int = 8,
    simulationArgs: Seq[String] = Seq()
  ) extends firesim.TestSuiteCommon with Matchers {

  val backendSimulator = "verilator"

  val targetTuple = s"$targetName-$targetConfigs-$platformConfigs"
  val commonMakeArgs = Seq(s"TARGET_PROJECT=midasexamples",
                           s"DESIGN=$targetName",
                           s"TARGET_CONFIG=${targetConfigs}",
                           s"PLATFORM_CONFIG=${platformConfigs}")

  def run(backend: String,
          debug: Boolean = false,
          logFile: Option[File] = None,
          waveform: Option[File] = None,
          args: Seq[String] = Nil) = {
    val makeArgs = Seq(
      s"run-$backend%s".format(if (debug) "-debug" else ""),
      "LOGFILE=%s".format(logFile map toStr getOrElse ""),
      "WAVEFORM=%s".format(waveform map toStr getOrElse ""),
      "ARGS=%s".format(args mkString " "))
    if (isCmdAvailable(backend)) {
      make(makeArgs:_*)
    } else 0
  }


  /**
    * Runs MIDAS-level simulation on the design.
    *
    * @param b Backend simulator: "verilator" or "vcs"
    * @param debug When true, captures waves from the simulation
    * @param args A seq of PlusArgs to pass to the simulator.
    * @param shouldPass When false, asserts the test returns a non-zero code
    */
  def runTest(b: String, debug: Boolean = false, args: Seq[String] = simulationArgs, shouldPass: Boolean = true) {
    val prefix =  if (shouldPass) "pass in " else "fail in "
    val testEnvStr  = s"${b} MIDAS-level simulation"
    val wavesStr = if (debug) " with waves enabled" else ""
    val argStr = " with args: " + args.mkString(" ")

    val haveThisBehavior = prefix + testEnvStr + wavesStr + argStr

    if (isCmdAvailable(b)) {
      it should haveThisBehavior in {
         assert((run(b, debug, args = args) == 0) == shouldPass)
      }
    } else {
      ignore should haveThisBehavior in { }
    }
  }

  // Checks that a bridge generated log in ${genDir}/${synthLog} matches output
  // generated directly by the RTL simulator (usually with printfs)
  def diffSynthesizedLog(synthLog: String,
                         stdoutPrefix: String = "SYNTHESIZED_PRINT ",
                         synthPrefix: String  = "SYNTHESIZED_PRINT ",
                         synthLinesToDrop: Int = 0) {
    behavior of s"${synthLog}"
    it should "match the prints generated by the verilated design" in {
      val verilatedLogFile = new File(outDir,  s"/${targetName}.${backendSimulator}.out")
      val synthLogFile = new File(genDir, s"/${synthLog}")
      val verilatedOutput = extractLines(verilatedLogFile, stdoutPrefix).sorted
      val synthPrintOutput = extractLines(synthLogFile, synthPrefix, synthLinesToDrop).sorted
      diffLines(verilatedOutput, synthPrintOutput)
    }
  }

  // Checks that a bridge generated log in ${genDir}/${synthLog} is empty
  def assertSynthesizedLogEmpty(synthLog: String) {
    s"${synthLog}" should "be empty" in {
      val synthLogFile = new File(genDir, s"/${synthLog}")
      val lines = extractLines(synthLogFile, prefix = "")
      assert(lines.isEmpty)
    }
  }

  def expectedFMR(expectedValue: Double, error: Double = 0.0) {
    it should s"run with an FMR between ${expectedValue - error} and ${expectedValue + error}" in {
      val verilatedLogFile = new File(outDir,  s"/${targetName}.${backendSimulator}.out")
      val lines = Source.fromFile(verilatedLogFile).getLines.toList.reverse
      val fmrRegex = raw"^FMR: (\d*\.\d*)".r
      val fmr = lines.collectFirst {
        case fmrRegex(value) => value.toDouble
      }
      assert(fmr.nonEmpty, "FMR value not found.")
      assert(fmr.get >= expectedValue - error)
      assert(fmr.get <= expectedValue + error)
    }
  }

  // Check that we are extracting from the desired ROI by checking that the
  // bridge-inserted cycle prefix matches the target-side cycle prefix
  def checkPrintCycles(filename: String, startCycle: Int, endCycle: Int, linesPerCycle: Int) {
    it should "have synthesized printfs in the desired cycles" in {
      val synthLogFile = new File(genDir, s"/${filename}")
      val synthPrintOutput = extractLines(synthLogFile, prefix = "")
      val length = synthPrintOutput.size
      assert(length  == linesPerCycle * (endCycle - startCycle + 1))
      for ((line, idx) <- synthPrintOutput.zipWithIndex) {
        val currentCycle = idx / linesPerCycle + startCycle
        val printRegex = raw"^CYCLE:\s*(\d*) SYNTHESIZED_PRINT CYCLE:\s*(\d*).*".r
        line match {
          case printRegex(cycleA, cycleB) =>
            assert(cycleA.toInt == currentCycle)
            assert(cycleB.toInt == currentCycle)
        }
      }
    }
  }

  /**
    * Compares an AutoCounter output CSV against a reference generated using in-circuit printfs.
    */
  def checkAutoCounterCSV(filename: String, stdoutPrefix: String) {
    it should s"produce a csv file (${filename}) that matches in-circuit printf output" in {
      val scrubWhitespace = raw"\s*(.*)\s*".r
      def splitAtCommas(s: String) = {
        s.split(",")
         .map(scrubWhitespace.findFirstMatchIn(_).get.group(1))
      }

      def quotedSplitAtCommas(s: String) = {
        s.split("\",\"")
         .map(scrubWhitespace.findFirstMatchIn(_).get.group(1))
      }

      val refLogFile = new File(outDir,  s"/${targetName}.${backendSimulator}.out")
      val acFile = new File(genDir, s"/${filename}")

      val refVersion ::refClockInfo :: refLabelLine :: refDescLine :: refOutput =
        extractLines(refLogFile, stdoutPrefix, headerLines = 0).toList
      val acVersion  ::acClockInfo  :: acLabelLine :: acDescLine  :: acOutput =
        extractLines(acFile,      prefix = "" , headerLines = 0).toList

      assert(acVersion == refVersion)

      val refLabels = splitAtCommas(refLabelLine)
      val acLabels  = splitAtCommas(acLabelLine)
      acLabels should contain theSameElementsAs refLabels

      val swizzle: Seq[Int] = refLabels.map { acLabels.indexOf(_) }

      def checkLine(acLine: String, refLine: String, tokenizer: String => Seq[String] = splitAtCommas) {
        val Seq(acFields, refFields) = Seq(acLine, refLine).map(tokenizer)
        val assertMessagePrefix = s"Row commencing with ${refFields.head}:"
        assert(acFields.size == refFields.size, s"${assertMessagePrefix} lengths do not match")
        for ((field, columnIdx) <- refFields.zipWithIndex) {
          assert(field == acFields(swizzle(columnIdx)),
            s"${assertMessagePrefix} value for label ${refLabels(columnIdx)} does not match."
          )
        }
      }

      for ((acLine, refLine) <- acOutput.zip(refOutput)) {
        checkLine(acLine, refLine)
      }
    }
  }

  mkdirs()
  behavior of s"$targetName"
  elaborateAndCompile()
  compileMlSimulator(backendSimulator)
  runTest(backendSimulator)
}

//class PointerChaserF1Test extends TutorialSuite(
//  "PointerChaser", "PointerChaserConfig", simulationArgs = Seq("`cat runtime.conf`"))
class GCDF1Test extends TutorialSuite("GCD")
// Hijack Parity to test all of the Midas-level backends
class ParityF1Test extends TutorialSuite("Parity") {
  runTest("verilator", true)
  runTest("vcs", true)
}

class ParityVitisTest extends TutorialSuite("Parity", platformConfigs = classOf[DefaultVitisConfig].getSimpleName) {
  runTest("verilator", true)
  runTest("vcs", true)
}


/** Trait so that we have a uniform numbering scheme for the plusargs tests
  */
trait PlusArgsKey {
  def getKey(groupNumber: Int, testNumber: Int): String = {
    val key = (groupNumber << 4 | testNumber)
    s"+plusargs_test_key=${key}"
  }
}

class PlusArgsGroup68Bit extends TutorialSuite("PlusArgsModule", "PlusArgsModuleTestConfigGroup68Bit") with PlusArgsKey {
  it should "provide the correct default value, 3 slice" in {
    assert(run("verilator", false, args = Seq(getKey(0,0))) == 0)
  }

  it should "accept an int from the command line" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=3", getKey(0,1))) == 0)
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("f00000000", 16)}", getKey(0,2))) == 0)
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("f0000000000000000", 16)}", getKey(0,3))) == 0)
  }

  it should "reject large runtime values" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("ff0000000000000000", 16)}", getKey(0,4))) != 0)
  }
}

class PlusArgsGroup29Bit extends TutorialSuite("PlusArgsModule", "PlusArgsModuleTestConfigGroup29Bit") with PlusArgsKey {
  it should "provide the correct default value, 1 slice" in {
    assert(run("verilator", false, args = Seq(getKey(1,0))) == 0)
  }

  it should "accept an int from the command line, 1 slice" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("1eadbeef", 16)}", getKey(1,1))) == 0)
  }
}



class ShiftRegisterF1Test extends TutorialSuite("ShiftRegister")
class ResetShiftRegisterF1Test extends TutorialSuite("ResetShiftRegister")
class EnableShiftRegisterF1Test extends TutorialSuite("EnableShiftRegister")
class StackF1Test extends TutorialSuite("Stack")
class RiscF1Test extends TutorialSuite("Risc")
class RiscSRAMF1Test extends TutorialSuite("RiscSRAM")
class AccumulatorF1Test extends TutorialSuite("Accumulator")
class VerilogAccumulatorF1Test extends TutorialSuite("VerilogAccumulator")
class AssertModuleF1Test extends TutorialSuite("AssertModule")
class AutoCounterModuleF1Test extends TutorialSuite("AutoCounterModule",
    simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter")) {
  checkAutoCounterCSV("autocounter0.csv", "AUTOCOUNTER_PRINT ")
}
class AutoCounter32bRolloverTest extends TutorialSuite("AutoCounter32bRollover",
    simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter")) {
  checkAutoCounterCSV("autocounter0.csv", "AUTOCOUNTER_PRINT ")
}
class AutoCounterCoverModuleF1Test extends TutorialSuite("AutoCounterCoverModule",
    simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter")) {
  checkAutoCounterCSV("autocounter0.csv", "AUTOCOUNTER_PRINT ")
}
class AutoCounterPrintfF1Test extends TutorialSuite("AutoCounterPrintfModule",
    simulationArgs = Seq("+print-file=synthprinttest.out"),
    platformConfigs = "AutoCounterPrintf_HostDebugFeatures_DefaultF1Config") {
  diffSynthesizedLog("synthprinttest.out0", stdoutPrefix = "AUTOCOUNTER_PRINT CYCLE", synthPrefix = "CYCLE")
}
class AutoCounterGlobalResetConditionF1Test extends TutorialSuite("AutoCounterGlobalResetCondition",
    simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter")) {
  def assertCountsAreZero(filename: String, clockDivision: Int) {
    s"Counts reported in ${filename}" should "always be zero" in {
      val log = new File(genDir, s"/${filename}")
      val versionLine :: lines = extractLines(log, "", headerLines = 0).toList
      val sampleLines = lines.drop(AutoCounterVerificationConstants.headerLines - 1)

      assert(versionLine.split(",")(1).toInt == AutoCounterVerificationConstants.expectedCSVVersion)

      val perfCounterRegex = raw"(\d*),(\d*),(\d*)".r
      sampleLines.zipWithIndex foreach {
          case (perfCounterRegex(baseCycle,localCycle,value), idx) =>
            assert(baseCycle.toInt == 1000 * (idx + 1))
            assert(localCycle.toInt == (1000 / clockDivision) * (idx + 1))
            assert(value.toInt == 0)
      }
    }
  }
  assertCountsAreZero("autocounter0.csv", clockDivision = 1)
  assertCountsAreZero("autocounter1.csv", clockDivision = 2)
}

class PrintfModuleF1Test extends TutorialSuite("PrintfModule",
  simulationArgs = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out")) {
  diffSynthesizedLog("synthprinttest.out0")
}
class NarrowPrintfModuleF1Test extends TutorialSuite("NarrowPrintfModule",
  simulationArgs = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out")) {
  diffSynthesizedLog("synthprinttest.out0")
}

class PrintfGlobalResetConditionTest extends TutorialSuite("PrintfGlobalResetCondition",
  simulationArgs = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out")) {
  // The log should be empty.
  assertSynthesizedLogEmpty("synthprinttest.out0")
  assertSynthesizedLogEmpty("synthprinttest.out1")
}

class PrintfCycleBoundsTestBase(startCycle: Int, endCycle: Int) extends TutorialSuite(
  "PrintfModule",
   simulationArgs = Seq(
      "+print-file=synthprinttest.out",
      s"+print-start=${startCycle}",
      s"+print-end=${endCycle}"
    )) {
  checkPrintCycles("synthprinttest.out0", startCycle, endCycle, linesPerCycle = 4)
}

class PrintfCycleBoundsF1Test extends PrintfCycleBoundsTestBase(startCycle = 172, endCycle = 9377)

class TriggerPredicatedPrintfF1Test extends TutorialSuite("TriggerPredicatedPrintf",
  simulationArgs = Seq("+print-file=synthprinttest.out")) with TriggerPredicatedPrintfConsts {
  val startCycle = assertTriggerCycle + 2
  val endCycle = deassertTriggerCycle + 2
  checkPrintCycles("synthprinttest.out0", startCycle, endCycle, linesPerCycle = 2)
}

class WireInterconnectF1Test extends TutorialSuite("WireInterconnect")
class TrivialMulticlockF1Test extends TutorialSuite("TrivialMulticlock") {
  runTest("verilator", true)
  runTest("vcs", true)
}

class TriggerWiringModuleF1Test extends TutorialSuite("TriggerWiringModule")

class MulticlockAssertF1Test extends TutorialSuite("MulticlockAssertModule")

class AssertTortureTest extends TutorialSuite("AssertTorture") with AssertTortureConstants {
  def checkClockDomainAssertionOrder(clockIdx: Int): Unit = {
    it should s"capture asserts in the same order as the reference printfs in clock domain $clockIdx" in {
      val verilatedLogFile = new File(outDir,  s"/${targetName}.verilator.out")
      // Diff parts of the simulation's stdout against itself, as the synthesized
      // assertion messages are dumped to the same file as printfs in the RTL
      val expected = extractLines(verilatedLogFile, prefix = s"${printfPrefix}${clockPrefix(clockIdx)}")
      val actual  = extractLines(verilatedLogFile, prefix = s"Assertion failed: ${clockPrefix(clockIdx)}")
      diffLines(expected, actual)
    }
  }
  // TODO: Create a target-parameters instance we can inspect here
  Seq.tabulate(4)(i => checkClockDomainAssertionOrder(i))
}

class AssertGlobalResetConditionF1Test extends TutorialSuite("AssertGlobalResetCondition")

class MulticlockPrintF1Test extends TutorialSuite("MulticlockPrintfModule",
  simulationArgs = Seq("+print-file=synthprinttest.out",
                       "+print-no-cycle-prefix")) {
  diffSynthesizedLog("synthprinttest.out0")
  diffSynthesizedLog("synthprinttest.out1",
    stdoutPrefix = "SYNTHESIZED_PRINT_HALFRATE ",
    synthPrefix = "SYNTHESIZED_PRINT_HALFRATE ",
    // Corresponds to a single cycle of extra output.
    synthLinesToDrop = 4)
}

class MulticlockAutoCounterF1Test extends TutorialSuite("MulticlockAutoCounterModule",
    simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter")) {
  checkAutoCounterCSV("autocounter0.csv", "AUTOCOUNTER_PRINT ")
  checkAutoCounterCSV("autocounter1.csv", "AUTOCOUNTER_PRINT_SLOWCLOCK ")
}
// Basic test for deduplicated extracted models
class TwoAddersF1Test extends TutorialSuite("TwoAdders")

class RegfileF1Test extends TutorialSuite("Regfile")

class MultiRegfileF1Test extends TutorialSuite("MultiRegfile")
class MultiRegfileFMRF1Test extends TutorialSuite("MultiRegfileFMR") {
  // A threaded model that relies on another model to implement an internal
  // combinational path (like an extracted memory model) will only simulate
  // one target thread-cycle every two host cycles.
  expectedFMR(2.0 * MultiRegfile.nCopiesToTime)
}

class MultiSRAMF1Test extends TutorialSuite("MultiSRAM")
class MultiSRAMFMRF1Test extends TutorialSuite("MultiSRAMFMR") {
  expectedFMR(MultiRegfile.nCopiesToTime) // No comb paths -> 1:1
}

class NestedModelsF1Test extends TutorialSuite("NestedModels")

class MultiRegF1Test extends TutorialSuite("MultiReg")

class PassthroughModelTest extends TutorialSuite("PassthroughModel") {
  expectedFMR(2.0)
}

class PassthroughModelNestedTest extends TutorialSuite("PassthroughModelNested") {
  expectedFMR(2.0)
}

class PassthroughModelBridgeSourceTest extends TutorialSuite("PassthroughModelBridgeSource") {
  expectedFMR(1.0)
}

class ResetPulseBridgeActiveHighTest extends TutorialSuite(
    "ResetPulseBridgeTest",
    // Disable assertion synthesis to rely on native chisel assertions to catch bad behavior
    platformConfigs = "NoSynthAsserts_HostDebugFeatures_DefaultF1Config",
    simulationArgs = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength}")) {
  runTest(backendSimulator,
    args = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength + 1}"),
    shouldPass = false)
}

class ResetPulseBridgeActiveLowTest extends TutorialSuite(
    "ResetPulseBridgeTest",
    targetConfigs = "ResetPulseBridgeActiveLowConfig",
    platformConfigs = "NoSynthAsserts_HostDebugFeatures_DefaultF1Config",
    simulationArgs = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength}")) {
  runTest(backendSimulator,
    args = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength + 1}"),
    shouldPass = false)
}

class TerminationF1Test extends TutorialSuite("TerminationModule") {
  1 to 10 foreach {x => runTest(backendSimulator, args = Seq("+termination-bridge-tick-rate=10", s"+seed=${x}"), shouldPass = true)}
}

class CustomConstraintsF1Test extends TutorialSuite("CustomConstraints") {
  def readLines(filename: String): List[String] = {
    val file = new File(genDir, s"/${filename}")
    Source.fromFile(file).getLines.toList
  }
  it should s"generate synthesis XDC file" in {
    val xdc = readLines("FireSim-generated.synthesis.xdc")
    xdc should contain("constrain_synth1")
    atLeast (1, xdc) should fullyMatch regex "constrain_synth2 \\[reg firesim_top/.*/dut/r0\\]".r
  }
  it should s"generate implementation XDC file" in {
    val xdc = readLines("FireSim-generated.implementation.xdc")
    xdc should contain("constrain_impl1")
    atLeast (1, xdc) should fullyMatch regex "constrain_impl2 \\[reg WRAPPER_INST/CL/firesim_top/.*/dut/r1]".r
  }
}

// Suite Collections
class ChiselExampleDesigns extends Suites(
  new GCDF1Test,
  new ParityF1Test,
  new PlusArgsGroup68Bit,
  new PlusArgsGroup29Bit,
  new ResetShiftRegisterF1Test,
  new EnableShiftRegisterF1Test,
  new StackF1Test,
  new RiscF1Test,
  new RiscSRAMF1Test,
  new AccumulatorF1Test,
  new VerilogAccumulatorF1Test,
  new CustomConstraintsF1Test,
  // This test is known to fail non-deterministically. See https://github.com/firesim/firesim/issues/1147
  // new TerminationF1Test
)

class PrintfSynthesisCITests extends Suites(
  new PrintfModuleF1Test,
  new NarrowPrintfModuleF1Test,
  new MulticlockPrintF1Test,
  new PrintfCycleBoundsF1Test,
  new TriggerPredicatedPrintfF1Test,
  new PrintfGlobalResetConditionTest,
)

class AssertionSynthesisCITests extends Suites(
  new AssertModuleF1Test,
  new MulticlockAssertF1Test,
  new AssertTortureTest,
  new AssertGlobalResetConditionF1Test,
)

class AutoCounterCITests extends Suites(
  new AutoCounterModuleF1Test,
  new AutoCounterCoverModuleF1Test,
  new AutoCounterPrintfF1Test,
  new MulticlockAutoCounterF1Test,
  new AutoCounterGlobalResetConditionF1Test,
  new AutoCounter32bRolloverTest,
)

class GoldenGateMiscCITests extends Suites(
  new TwoAddersF1Test,
  new TriggerWiringModuleF1Test,
  new WireInterconnectF1Test,
  new TrivialMulticlockF1Test,
  new RegfileF1Test,
  new MultiRegfileF1Test,
  new MultiSRAMF1Test,
  new NestedModelsF1Test,
  new MultiRegF1Test
)

// These groups are vestigial from CircleCI container limits
class CIGroupA extends Suites(
  new ChiselExampleDesigns,
  new PrintfSynthesisCITests,
  new firesim.fasedtests.CIGroupA,
  new AutoCounterCITests,
  new ResetPulseBridgeActiveHighTest,
  new ResetPulseBridgeActiveLowTest,
)

class CIGroupB extends Suites(
  new AssertionSynthesisCITests,
  new GoldenGateMiscCITests,
  new firesim.fasedtests.CIGroupB,
  new firesim.AllMidasUnitTests,
  new firesim.FailingUnitTests
)
