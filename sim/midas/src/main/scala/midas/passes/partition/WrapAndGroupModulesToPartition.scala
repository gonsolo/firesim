package midas.passes.partition

import scala.Console.println
import scala.collection.mutable
import Array.range

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.analyses.{InstanceKeyGraph, InstanceGraph}

import midas._
import midas.stage._
import midas.targetutils._

import org.chipsalliance.cde.config.{Parameters, Config}

class WrapAndGroupModulesToPartition
  extends Transform
  with DependencyAPIMigration 
  with InsertWrapperPass
  with GroupAndInsertWrapperPass
  with PromoteSubmodulesByNamePass
  with StateToLowFIRRTLPass
  with DedupFAME5InstancesPass {

  import PartitionModulesInfo._

  def execute(state: CircuitState): CircuitState = {
    val p = getConfigParams(state.annotations)

    // If (Remove/Extract)ModuleNameAnnotation is found this is happening after the
    // NoCPartitionExtract pass. Otherwise, just use the FireAxePartitionGlobalInfo
    val partitionModules = state.annotations.collectFirst(_ match {
      case RemoveModuleNameAnnotation(name) => Seq(Seq(name))
      case ExtractModuleNameAnnotation(name) => Seq(Seq(name))
    }).getOrElse(p(FireAxePartitionGlobalInfo).get)
    val partitionModuleWrappers = partitionModules.map(_.map(wrapperPfx + "_" + _))

    println("- Promote the modules to extract to top level")
    val promotedState = partitionModules.flatten.foldLeft(state) { case (st, module) =>
      promoteModules(st, module)
    }

    println("- Lower to LowFIRRTL")
    val loweredState = toLowFirrtl(removePartial(promotedState))

    println(s"- Wrap the individual modules to extract")
    val modulesWrappedState = (partitionModules.flatten).zip(
                              (partitionModuleWrappers.flatten))
                              .foldLeft(loweredState)(
                                (st, mmw) => wrapModule(st, mmw._1, mmw._2)
                              )

    println(s"- Wrap the modules to extract by groups of ${partitionModules.size}")
    val groupedState = wrapModulesByGroups(
        modulesWrappedState,
        partitionModuleWrappers.map(_.toSet),
        groupPfx)

    println(s"- Deduplicate instances to multithread")
    val dedupedState = deduplicateInstancesOnFAME5(groupedState)

    val (groups, groupWrappers) = getGroups(dedupedState)
    print(s"- groups: ${groups}")
    print(s"- groupsWrappers: ${groupWrappers}")
    val groupWrappedState = groups.zip(groupWrappers).foldLeft(dedupedState) {
      (s, gw) => wrapModule(s, gw._1, gw._2)
    }
    groupWrappedState
  }
}


