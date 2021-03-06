/*                                                                                                *\
**      _____            __         ______           __                                           **
**     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____                                **
**     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/    Scala Center                **
**    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /        https://scala.epfl.ch       **
**   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/         (c) 2017-2018, LAMP/EPFL    **
**                                                                                                **
\*                                                                                                */

package ch.epfl.scala.profilers

import java.nio.file.{Files, Path, StandardOpenOption}
import java.util

import ch.epfl.scala.profiledb.utils.AbsolutePath

import scala.tools.nsc.Global
import ch.epfl.scala.profilers.tools.{Logger, QuantitiesHijacker}

import scala.collection.mutable.ArrayBuffer
import scala.reflect.internal.util.StatisticsStatics

final class ProfilingImpl[G <: Global](override val global: G, logger: Logger[G])
    extends ProfilingStats {
  import global._

  def registerProfilers(): Unit = {
    // Register our profiling macro plugin
    analyzer.addMacroPlugin(ProfilingMacroPlugin)
    analyzer.addAnalyzerPlugin(ProfilingAnalyzerPlugin)
  }

  /**
    * Represents the profiling information about expanded macros.
    *
    * Note that we could derive the value of expanded macros from the
    * number of instances of [[MacroInfo]] if it were not by the fact
    * that a macro can expand in the same position more than once. We
    * want to be able to report/analyse such cases on their own, so
    * we keep it as a paramater of this entity.
    */
  case class MacroInfo(expandedMacros: Int, expandedNodes: Int, expansionNanos: Long) {
    def +(other: MacroInfo): MacroInfo = {
      val totalExpanded = expandedMacros + other.expandedMacros
      val totalNodes = expandedNodes + other.expandedNodes
      val totalTime = expansionNanos + other.expansionNanos
      MacroInfo(totalExpanded, totalNodes, totalTime)
    }
  }

  object MacroInfo {
    final val Empty = MacroInfo(0, 0, 0L)
    implicit val macroInfoOrdering: Ordering[MacroInfo] = Ordering.by(_.expansionNanos)
    def aggregate(infos: Iterator[MacroInfo]): MacroInfo = {
      infos.foldLeft(MacroInfo.Empty)(_ + _)
    }
  }

  import scala.reflect.internal.util.SourceFile
  case class MacroProfiler(
      perCallSite: Map[Position, MacroInfo],
      perFile: Map[SourceFile, MacroInfo],
      inTotal: MacroInfo,
      repeatedExpansions: Map[Tree, Int]
  )

  def toMillis(nanos: Long): Long =
    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos)

  def groupPerFile[V](
      kvs: Map[Position, V]
  )(empty: V, aggregate: (V, V) => V): Map[SourceFile, V] = {
    kvs.groupBy(_._1.source).mapValues {
      case posInfos: Map[Position, V] => posInfos.valuesIterator.fold(empty)(aggregate)
    }
  }

  lazy val macroProfiler: MacroProfiler = {
    import ProfilingMacroPlugin.{macroInfos, repeatedTrees}
    val perCallSite = macroInfos.toMap
    val perFile = groupPerFile(perCallSite)(MacroInfo.Empty, _ + _)
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
    val inTotal = MacroInfo.aggregate(perFile.valuesIterator)
    val repeated = repeatedTrees.toMap.valuesIterator
      .filter(_.count > 1)
      .map(v => v.original -> v.count)
      .toMap
    // perFile and inTotal are already converted to millis
    val callSiteNanos = perCallSite
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
    MacroProfiler(callSiteNanos, perFile, inTotal, repeated)
  }

  case class ImplicitInfo(count: Int) {
    def +(other: ImplicitInfo): ImplicitInfo = ImplicitInfo(count + other.count)
  }

  object ImplicitInfo {
    final val Empty = ImplicitInfo(0)
    def aggregate(infos: Iterator[ImplicitInfo]): ImplicitInfo = infos.fold(Empty)(_ + _)
    implicit val infoOrdering: Ordering[ImplicitInfo] = Ordering.by(_.count)
  }

  case class ImplicitProfiler(
      perCallSite: Map[Position, ImplicitInfo],
      perFile: Map[SourceFile, ImplicitInfo],
      perType: Map[Type, ImplicitInfo],
      inTotal: ImplicitInfo
  )

  lazy val implicitProfiler: ImplicitProfiler = {
    val perCallSite = implicitSearchesByPos.toMap.mapValues(ImplicitInfo.apply)
    val perFile = groupPerFile[ImplicitInfo](perCallSite)(ImplicitInfo.Empty, _ + _)
    val perType = implicitSearchesByType.toMap.mapValues(ImplicitInfo.apply)
    val inTotal = ImplicitInfo.aggregate(perFile.valuesIterator)
    ImplicitProfiler(perCallSite, perFile, perType, inTotal)
  }

  def generateGraphData(outputDir: AbsolutePath): List[AbsolutePath] = {
    Files.createDirectories(outputDir.underlying)
    val graphName = s"implicit-searches-${java.lang.Long.toString(System.currentTimeMillis())}"
    //val dotFile = outputDir.resolve(s"$graphName.dot")
    //ProfilingAnalyzerPlugin.dottify(graphName, dotFile.underlying)
    val flamegraphFile = outputDir.resolve(s"$graphName.flamegraph")
    ProfilingAnalyzerPlugin.foldStacks(flamegraphFile.underlying)
    List(flamegraphFile)
  }

  private object ProfilingAnalyzerPlugin extends global.analyzer.AnalyzerPlugin {
    import scala.collection.mutable
    private type Entry =
      (global.analyzer.ImplicitSearch, statistics.TimerSnapshot, statistics.TimerSnapshot)

    private var implicitsStack: List[Entry] = Nil
    private val implicitsTimers = perRunCaches.newAnyRefMap[Type, statistics.Timer]()
    private val searchIdsToStackedNames = perRunCaches.newMap[Int, (String, Type)]()
    private val stackedNanos = perRunCaches.newAnyRefMap[String, (Long, Type)]()
    private val registeredQuantities = QuantitiesHijacker.getRegisteredQuantities(global)
    private val searchIdsToTimers = perRunCaches.newMap[Int, statistics.Timer]()
    //private val implicitsDependants = new mutable.AnyRefMap[Type, mutable.HashSet[Type]]()

    private def typeToString(`type`: Type): String =
      global.exitingTyper(`type`.toLongString).trim

    def foldStacks(outputPath: Path): Unit = {
      // This part is memory intensive and hence the use of java collections
      val stacksJavaList = new java.util.ArrayList[String]()
      val allStacks = stackedNanos.foreach {
        case (name, (nanos, tpe)) =>
          val count = implicitSearchesByType.getOrElse(tpe, sys.error(s"No counter for ${tpe}"))
          stacksJavaList.add(s"$name [total $count] ${nanos / 1000000}")
      }
      java.util.Collections.sort(stacksJavaList)
      Files.write(outputPath, stacksJavaList, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    }

/*    def dottify(graphName: String, outputPath: Path): Unit = {
      def clean(`type`: Type) = typeToString(`type`).replace("\"", "\'")
      def qualify(node: String, timing: Long, counter: Int): String = {
        val nodeName = node.stripPrefix("\"").stripSuffix("\"")
        val style = if (timing >= 500) "style=filled, fillcolor=\"#ea9d8f\"," else ""
        s"""$node [${style}label="${nodeName}\\l${counter} times = ${timing}ms"];"""
      }

      val nodes = implicitSearchesByType.keys
      val nodesIds = nodes.map(`type` => `type` -> s""""${clean(`type`)}"""").toMap
      def getNodeId(`type`: Type): String = {
        nodesIds.getOrElse(`type`, sys.error {
            s"""Id for ${`type`} doesn't exist.
              |
              |  Information about the type:
              |   - `structure` -> ${global.showRaw(`type`)}
              |   - `safeToString` -> ${`type`.safeToString}
              |   - `toLongString` after typer -> ${typeToString(`type`)}
              |   - `typeSymbol` -> ${`type`.typeSymbol}
            """.stripMargin
        })
      }

      val connections = for {
        (dependee, dependants) <- implicitsDependants.toSet
        dependant <- dependants
        dependantId = getNodeId(dependant)
        dependeeId = getNodeId(dependee)
        if dependeeId != dependantId && !dependantId.isEmpty && !dependeeId.isEmpty
      } yield s"$dependantId -> $dependeeId;"

      val nodeInfos = nodes.map { `type` =>
        val id = getNodeId(`type`)
        val timer = getImplicitTimerFor(`type`).nanos / 1000000
        val count = implicitSearchesByType.getOrElse(`type`, sys.error(s"No counter for ${`type`}"))
        qualify(id, timer, count)
      }

      val graph = s"""digraph "$graphName" {
        | graph [ranksep=0, rankdir=LR];
        |${nodeInfos.mkString("  ", "\n  ", "\n  ")}
        |${connections.mkString("  ", "\n  ", "\n  ")}
        |}""".stripMargin.getBytes(UTF_8)
      Files.write(outputPath, graph, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    }*/

    private def getImplicitTimerFor(candidate: Type): statistics.Timer =
      implicitsTimers.getOrElse(candidate, sys.error(s"Timer for ${candidate} doesn't exist"))

    private def getSearchTimerFor(searchId: Int): statistics.Timer = {
      searchIdsToTimers
        .getOrElse(searchId, sys.error(s"Missing non-cumulative timer for $searchId"))
    }

    override def pluginsNotifyImplicitSearch(search: global.analyzer.ImplicitSearch): Unit = {
      if (StatisticsStatics.areSomeColdStatsEnabled() && statistics.areStatisticsLocallyEnabled) {
        val targetType = search.pt
        val targetPos = search.pos

        // Stop counter of dependant implicit search
        implicitsStack.headOption.foreach {
          case (search, _, searchStart) =>
            val searchTimer = getSearchTimerFor(search.searchId)
            statistics.stopTimer(searchTimer, searchStart)
        }

        // Create timer and unregister it so that it is invisible in console output
        val prefix = s"  $targetType"
        val perTypeTimer = implicitsTimers
          .getOrElseUpdate(targetType, statistics.newTimer(prefix, "typer"))
        registeredQuantities.remove(s"/$prefix")

        // Create non-cumulative timer for the search and unregister it too
        val searchId = search.searchId
        val searchPrefix = s"  implicit search ${searchId}"
        val searchTimer = statistics.newTimer(searchPrefix, "typer")
        registeredQuantities.remove(s"/$searchPrefix")
        searchIdsToTimers.+=(searchId -> searchTimer)

        // Update all timers and counters
        val typeCounter = implicitSearchesByType.getOrElse(targetType, 0)
        implicitSearchesByType.update(targetType, typeCounter + 1)
        val posCounter = implicitSearchesByPos.getOrElse(targetPos, 0)
        implicitSearchesByPos.update(targetPos, posCounter + 1)
        if (global.analyzer.openMacros.nonEmpty)
          statistics.incCounter(implicitSearchesByMacrosCount)

        // Add stacked names and timer for the flamegraph generation
        val stackedName = search.context.openImplicits.foldLeft(typeToString(targetType)) {
          case (stackedName, dependant) => s"${typeToString(dependant.pt)};$stackedName".trim
        }

        searchIdsToStackedNames.+=((search.searchId, (stackedName, targetType)))

/*        // Add dependants once we hit a concrete node
        search.context.openImplicits.headOption.foreach { dependant =>
          implicitsDependants
            .getOrElseUpdate(targetType, new mutable.HashSet())
            .+=(dependant.pt)
        }*/

        // Start the timer at the end to factor out the cost of our analysis
        val implicitTypeStart = statistics.startTimer(perTypeTimer)
        val searchStart = statistics.startTimer(searchTimer)
        implicitsStack = (search, implicitTypeStart, searchStart) :: implicitsStack
      }
    }

    override def pluginsNotifyImplicitSearchResult(result: global.analyzer.SearchResult): Unit = {
      super.pluginsNotifyImplicitSearchResult(result)
      if (StatisticsStatics.areSomeColdStatsEnabled() && statistics.areStatisticsLocallyEnabled) {
        // 1. Stop timer for the running search.
        val (search, implicitTypeStart, searchStart) = implicitsStack.head
        val targetType = search.pt
        val timer = getImplicitTimerFor(targetType)
        statistics.stopTimer(timer, implicitTypeStart)

        // 2. Register the timing diff for every stacked name.
        val searchId = search.searchId
        val searchTimer = getSearchTimerFor(searchId)
        statistics.stopTimer(searchTimer, searchStart)
        val (stackedName, stackedType) = searchIdsToStackedNames
          .getOrElse(searchId, sys.error(s"Missing stacked name for $searchId ($targetType)."))
        val (previousNanos, _) = stackedNanos.getOrElse(stackedName, (0L, stackedType))
        stackedNanos.+=((stackedName, ((searchTimer.nanos + previousNanos), stackedType)))

        // 3. Reset the stack and stop timer if there is a dependant search
        val previousImplicits = implicitsStack.tail
        implicitsStack = previousImplicits.headOption match {
          case Some((prevSearch, implicitTypeStart, _)) =>
            val newSearchStart = statistics.startTimer(getSearchTimerFor(prevSearch.searchId))
            (prevSearch, implicitTypeStart, newSearchStart) :: previousImplicits.tail
          case None => previousImplicits
        }
      }
    }
  }

  object ProfilingMacroPlugin extends global.analyzer.MacroPlugin {
    type Typer = analyzer.Typer
    private def guessTreeSize(tree: Tree): Int =
      1 + tree.children.map(guessTreeSize).sum

    type RepeatedKey = (String, String)
    case class RepeatedValue(original: Tree, result: Tree, count: Int)
    private[ProfilingImpl] val repeatedTrees = perRunCaches.newMap[RepeatedKey, RepeatedValue]
    private[ProfilingImpl] val macroInfos = perRunCaches.newMap[Position, MacroInfo]
    private final val EmptyRepeatedValue = RepeatedValue(EmptyTree, EmptyTree, 0)

    import scala.tools.nsc.Mode
    override def pluginsMacroExpand(t: Typer, expandee: Tree, md: Mode, pt: Type): Option[Tree] = {
      object expander extends analyzer.DefMacroExpander(t, expandee, md, pt) {
        private var alreadyTracking: Boolean = false

        /**
          * Overrides the default method that expands all macros.
          *
          * We perform this because we need our own timer and access to the first timer snapshot
          * in order to obtain the expansion time for every expanded tree.
          */
        override def apply(desugared: Tree): Tree = {
          val shouldTrack = statistics.enabled && !alreadyTracking
          val start = if (shouldTrack) {
            alreadyTracking = true
            statistics.startTimer(preciseMacroTimer)
          } else null
          try super.apply(desugared)
          finally if (shouldTrack) {
            alreadyTracking = false
            updateExpansionTime(desugared, start)
          } else ()
        }

        def updateExpansionTime(desugared: Tree, start: statistics.TimerSnapshot): Unit = {
          statistics.stopTimer(preciseMacroTimer, start)
          val (nanos0, _) = start
          val timeNanos = (preciseMacroTimer.nanos - nanos0)
          val callSitePos = desugared.pos
          // Those that are not present failed to expand
          macroInfos.get(callSitePos).foreach { found =>
            val updatedInfo = found.copy(expansionNanos = timeNanos)
            macroInfos(callSitePos) = updatedInfo
          }
        }

        override def onFailure(expanded: Tree) = {
          statistics.incCounter(failedMacros)
          super.onFailure(expanded)
        }

        override def onDelayed(expanded: Tree) = {
          statistics.incCounter(delayedMacros)
          super.onDelayed(expanded)
        }

        override def onSuccess(expanded: Tree) = {
          val callSitePos = expandee.pos
          val printedExpandee = showRaw(expandee)
          val printedExpanded = showRaw(expanded)
          val key = (printedExpandee, printedExpanded)
          val currentValue = repeatedTrees.getOrElse(key, EmptyRepeatedValue)
          val newValue = RepeatedValue(expandee, expanded, currentValue.count + 1)
          repeatedTrees.put(key, newValue)
          val macroInfo = macroInfos.getOrElse(callSitePos, MacroInfo.Empty)
          val expandedMacros = macroInfo.expandedMacros + 1
          val treeSize = macroInfo.expandedNodes + guessTreeSize(expanded)
          // Use 0L for the timer because it will be filled in by the caller `apply`
          macroInfos.put(callSitePos, MacroInfo(expandedMacros, treeSize, 0L))
          super.onSuccess(expanded)
        }
      }
      Some(expander(expandee))
    }
  }
}

trait ProfilingStats {
  val global: Global
  import global.statistics.{newTimer, newSubCounter, macroExpandCount, implicitSearchCount}
  macroExpandCount.children.clear()

  final val preciseMacroTimer = newTimer("precise time in macroExpand")
  final val failedMacros = newSubCounter("  of which failed macros", macroExpandCount)
  final val delayedMacros = newSubCounter("  of which delayed macros", macroExpandCount)

  final val implicitSearchesByMacrosCount = newSubCounter("  from macros", implicitSearchCount)

  import scala.reflect.internal.util.Position
  final val implicitSearchesByType = global.perRunCaches.newMap[global.Type, Int]()
  final val implicitSearchesByPos = global.perRunCaches.newMap[Position, Int]()
}
