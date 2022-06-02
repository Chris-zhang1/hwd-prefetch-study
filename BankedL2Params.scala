// See LICENSE.SiFive for license details.

package freechips.rocketchip.subsystem

import chisel3._
import chisel3.util._
import freechips.rocketchip.config._
import freechips.rocketchip.devices.tilelink.BuiltInDevices
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import CoherenceManagerWrapper._

/** Global cache coherence granularity, which applies to all caches, for now. */
case object CacheBlockBytes extends Field[Int](64)

/** L2 Broadcast Hub configuration */
case object BroadcastKey extends Field(BroadcastParams())

case class BroadcastParams(
  nTrackers:      Int     = 4,
  bufferless:     Boolean = false,
  controlAddress: Option[BigInt] = None,
  filterFactory:  TLBroadcast.ProbeFilterFactory = BroadcastFilter.factory)

/** L2 memory subsystem configuration */
case object BankedL2Key extends Field(BankedL2Params())

case class BankedL2Params(
  nBanks: Int = 1,
  coherenceManager: CoherenceManagerInstantiationFn = broadcastManager
) {
  require (isPow2(nBanks) || nBanks == 0)
}

case class CoherenceManagerWrapperParams(
    blockBytes: Int,
    beatBytes: Int,
    nBanks: Int,
    name: String,
    dtsFrequency: Option[BigInt] = None)
  (val coherenceManager: CoherenceManagerInstantiationFn)
  extends HasTLBusParams 
  with TLBusWrapperInstantiationLike
{
  def instantiate(context: HasTileLinkLocations, loc: Location[TLBusWrapper])(implicit p: Parameters): CoherenceManagerWrapper = {
    val cmWrapper = LazyModule(new CoherenceManagerWrapper(this, context))
    cmWrapper.suggestName(loc.name + "_wrapper")
    cmWrapper.halt.foreach { context.anyLocationMap += loc.halt(_) }
    context.tlBusWrapperLocationMap += (loc -> cmWrapper)
    cmWrapper
  }
}

class CoherenceManagerWrapper(params: CoherenceManagerWrapperParams, context: HasTileLinkLocations)(implicit p: Parameters) extends TLBusWrapper(params, params.name) {
  val (tempIn, tempOut, halt) = params.coherenceManager(context)

  private val coherent_jbar = LazyModule(new TLJbar)
  def busView: TLEdge = coherent_jbar.node.edges.out.head
  val inwardNode = tempIn :*= coherent_jbar.node
  val builtInDevices = BuiltInDevices.none
  val prefixNode = None
  println ("params.nBanks", params.nBanks)
  println ("params.blockBytes", params.blockBytes)

  private def banked(node: TLOutwardNode): TLOutwardNode =
    if (params.nBanks == 0) node else { TLTempNode() :=* BankBinder(params.nBanks, params.blockBytes) :*= node }

  val outwardNode = TLDChanDelayer(50) :=* banked(tempOut)
}

object CoherenceManagerWrapper {
  type CoherenceManagerInstantiationFn = HasTileLinkLocations => (TLInwardNode, TLOutwardNode, Option[IntOutwardNode])

  def broadcastManagerFn(
    name: String,
    location: HierarchicalLocation,
    controlPortsSlaveWhere: TLBusWrapperLocation
  ): CoherenceManagerInstantiationFn = { context =>
    implicit val p = context.p
    val cbus = context.locateTLBusWrapper(controlPortsSlaveWhere)

    val BroadcastParams(nTrackers, bufferless, controlAddress, filterFactory) = p(BroadcastKey)
    val bh = LazyModule(new TLBroadcast(TLBroadcastParams(
      lineBytes     = p(CacheBlockBytes),
      numTrackers   = nTrackers,
      bufferless    = bufferless,
      control       = controlAddress.map(x => TLBroadcastControlParams(AddressSet(x, 0xfff), cbus.beatBytes)),
      filterFactory = filterFactory)))
    bh.suggestName(name)

    bh.controlNode.foreach { _ := cbus.coupleTo(s"${name}_ctrl") { TLBuffer(1) := TLFragmenter(cbus) := _ } }
    bh.intNode.foreach { context.ibus.fromSync := _ }

    (bh.node, bh.node, None)
  }

  val broadcastManager = broadcastManagerFn("broadcast", InSystem, CBUS)

  val incoherentManager: CoherenceManagerInstantiationFn = { _ =>
    val node = TLNameNode("no_coherence_manager")
    (node, node, None)
  }
}

class Delayer[T <: Data](gen: T, delay: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })
  require(delay > 1, "Use a normal Queue if you want a delay of 1")

  class DebugData extends Bundle {
    val data = gen.cloneType
    val debug_id = UInt(log2Ceil(delay + 1).W)
  }
  val debug_id = RegInit(0.U(log2Ceil(delay + 1).W))
  val cycle = freechips.rocketchip.util.WideCounter(32).value

  val aging = RegInit(0.U((delay - 1).W))
  val tokens = RegInit(0.U(log2Ceil(delay + 1).W))
  println("delay", delay)
  println("log2Ceil(delay + 1)", log2Ceil(delay + 1))
  println("tokens.getWidth", tokens.getWidth)
  val nextAging = WireInit(aging)
  val nextTokens = WireInit(tokens)
  val queue = Module(new Queue(new DebugData, delay))
  nextAging := aging << 1
  when (aging(delay - 2) === 1.U) {
    assert (tokens < delay.U)
    nextTokens := tokens + 1.U
  }

  printf("{cycle:%d,aging:0b%b,tokens:%d}\n", cycle, aging, tokens)

  // enqueue logic
  io.enq.ready := queue.io.enq.ready
  queue.io.enq.valid := io.enq.valid
  queue.io.enq.bits.data := io.enq.bits
  queue.io.enq.bits.debug_id := debug_id
  when (io.enq.fire) {
    nextAging := (aging << 1).asUInt | 1.U
    when (debug_id =/= delay.U) {
      debug_id := debug_id + 1.U
    } .otherwise {
      debug_id := 0.U
    }
    printf("{cycle:%d,enq:%d}\n", cycle, debug_id)
  }

  // dequeue logic
  io.deq.valid := false.B
  io.deq.bits := queue.io.deq.bits.data
  queue.io.deq.ready := false.B
  when (tokens > 0.U) {
    io.deq.valid := queue.io.deq.valid
    queue.io.deq.ready := io.deq.ready
    when (io.deq.fire) {
      when (aging(delay - 2) === 0.U) {
        nextTokens := tokens - 1.U
      } .otherwise {
        nextTokens := tokens
      }
      printf("{cycle:%d,deq:%d}\n", cycle, queue.io.deq.bits.debug_id)
    }
  }

  aging := nextAging
  tokens := nextTokens

  when (queue.io.deq.valid && tokens === 0.U && aging === 0.U) {
    //printf("{cycle:%d,wait:1}\n", cycle)
  }
}

class TLDChanDelayer(delay: Int)(implicit p: Parameters) extends LazyModule
{
  import Chisel._

  val a = BufferParams.none
  val d = BufferParams(delay)
  val node = new TLBufferNode(a, a, a, d, a)

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      out.a <> in.a
      val delayer = Module(new Delayer(new TLBundleD(edgeOut.bundle), delay))
      delayer.io.enq <> out.d
      in .d <> delayer.io.deq

      if (edgeOut.manager.anySupportAcquireB && edgeOut.client.anySupportProbe) {
        println("node requires bce")
        in.b <> out.b
        out.c <> in.c
        out.e <> in.e
      } else {
        println("node doesn't require bce")
        in.b.valid := false.B
        in.c.ready := true.B
        in.e.ready := true.B
        out.b.ready := true.B
        out.c.valid := false.B
        out.e.valid := false.B
      }
    }
  }
}

object TLDChanDelayer {
  def apply(delay: Int)(implicit p: Parameters): TLNode = {
    val buffer = LazyModule(new TLDChanDelayer(delay))
    buffer.node
  }
}
