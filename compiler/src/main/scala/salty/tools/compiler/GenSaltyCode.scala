package salty.tools
package compiler

import scala.collection.{mutable => mut}
import scala.tools.nsc._
import scala.tools.nsc.plugins._
import scala.util.{Either, Left, Right}
import salty.ir, ir.{Name, Prim, Desc}
import salty.ir.{Focus, Tails}, Focus.sequenced
import salty.util, util.sh, util.ScopedVar.scoped

abstract class GenSaltyCode extends PluginComponent
                               with GenIRFiles
                               with GenTypeKinds
                               with GenNameEncoding {
  import global.{ merge => _, _ }
  import global.definitions._
  import global.treeInfo.hasSynthCaseSymbol

  val phaseName = "saltycode"

  override def newPhase(prev: Phase): StdPhase =
    new SaltyCodePhase(prev)

  def debug[T](msg: String)(v: T): T = { println(s"$msg = $v"); v }

  def unreachable = abort("unreachable")

  def undefined(focus: Focus) =
    Tails.termn(ir.Undefined(focus.cf, focus.ef))

  class Env {
    private val env = mut.Map.empty[Symbol, ir.Node]

    def enter(sym: Symbol, node: ir.Node): ir.Node = {
      env += ((sym, node))
      node
    }
    def resolve(sym: Symbol): ir.Node = env(sym)
  }

  class LabelEnv(env: Env) {
    private val labels  = mut.Map.empty[Symbol, ir.Node]
    private val phiss   = mut.Map.empty[Symbol, Seq[ir.Node]]
    private val efphis  = mut.Map.empty[Symbol, ir.Node]

    def enterLabel(ld: LabelDef): ir.Node = {
      if (labels.contains(ld.symbol)) {
        labels -= ld.symbol
        phiss -= ld.symbol
        efphis -= ld.symbol
      }
      val sym = ld.symbol
      val label = ir.Label(genLabelName(ld.symbol), Seq())
      val phis = Seq.fill(ld.params.length)(ir.Phi(label, Seq()))
      val efphi = ir.EfPhi(label, Seq())
      val treesyms = ld.params.map(_.symbol)
      val reflsyms = sym.asMethod.paramLists.head
      treesyms.zip(reflsyms).zip(phis).foreach {
        case ((treesym, reflsym), phi) =>
          env.enter(reflsym, phi)
          env.enter(treesym, phi)
      }
      labels += sym -> label
      phiss  += sym -> phis
      efphis += sym -> efphi
      label
    }
    def enterLabelCall(sym: Symbol, values: Seq[ir.Node], focus: Focus): Unit = {
      val ir.Label(_, cfs) = labels(sym)
      cfs := cfs :+ focus.cf

      val ir.EfPhi(_, efs) = efphis(sym)
      efs := efs :+ focus.ef

      phiss(sym).zip(values).foreach {
        case (ir.Phi(_, values), v) =>
          values := values :+ v
      }
    }
    def resolveLabel(sym: Symbol): ir.Node = labels(sym)
    def resolveLabelParams(sym: Symbol): Seq[ir.Node] = phiss(sym)
    def resolveLabelEf(sym: Symbol): ir.Node = efphis(sym)
  }

  class CollectLocalInfo extends Traverser {
    var mutableVars: Set[Symbol] = Set.empty
    var labels: Set[LabelDef] = Set.empty

    override def traverse(tree: Tree) = {
      tree match {
        case label: LabelDef =>
          labels += label
        case Assign(id @ Ident(_), _) =>
          mutableVars += id.symbol
        case _ =>
          ()
      }
      super.traverse(tree)
    }

    def collect(tree: Tree) = {
      traverse(tree)
      this
    }
  }

  class SaltyCodePhase(prev: Phase) extends StdPhase(prev) {
    val curLocalInfo = new util.ScopedVar[CollectLocalInfo]
    val curClassSym  = new util.ScopedVar[Symbol]
    val curMethodSym = new util.ScopedVar[Symbol]
    val curEnv       = new util.ScopedVar[Env]
    val curLabelEnv  = new util.ScopedVar[LabelEnv]
    val curThis      = new util.ScopedVar[ir.Node]

    override def run(): Unit = {
      scalaPrimitives.init()
      super.run()
    }

    override def apply(cunit: CompilationUnit): Unit = {
      def collectClassDefs(tree: Tree): List[ClassDef] = {
        tree match {
          case EmptyTree => Nil
          case PackageDef(_, stats) => stats flatMap collectClassDefs
          case cd: ClassDef => cd :: Nil
        }
      }
      val classDefs = collectClassDefs(cunit.body)

      classDefs.foreach { cd =>
        val sym = cd.symbol
        if (isPrimitiveValueClass(sym) || (sym == ArrayClass))
          ()
        else {
          val scope = genClass(cd)
          genIRFile(cunit, sym, scope)
        }
      }
    }

    def genClass(cd: ClassDef): ir.Scope = scoped (
      curClassSym := cd.symbol
    ) {
      val sym     = cd.symbol
      val name    = genClassName(sym)
      val parent  = genClassDefn(sym.superClass)
      val ifaces  = genClassInterfaces(sym)
      val fields  = genClassFields(sym).toSeq
      val methods = genClassMethods(cd.impl.body)
      val owner   =
        if (sym.isModuleClass)
          name -> ir.Module(name, parent +: ifaces)
        else if (sym.isInterface)
          name -> ir.Interface(name, ifaces)
        else
          name -> ir.Class(name, parent +: ifaces)

      ir.Scope(Map(((owner +: fields) ++ methods): _*))
    }

    def genClassInterfaces(sym: Symbol) =
      for {
        parent <- sym.info.parents
        psym = parent.typeSymbol
        if psym.isInterface
      } yield {
        genClassDefn(psym)
      }

    def genClassMethods(stats: List[Tree]): List[(ir.Name, ir.Node)] =
      stats.flatMap {
        case dd: DefDef => List(genDef(dd))
        case _          => Nil
      }

    def genClassFields(sym: Symbol) = {
      val owner = genClassDefn(sym)
      for {
        f <- sym.info.decls
        if !f.isMethod && f.isTerm && !f.isModule
      } yield {
        val name = genFieldName(f)
        name -> ir.Field(name, genType(f.tpe), Seq(owner))
      }
    }

    def genDef(dd: DefDef): (ir.Name, ir.Node) = scoped (
      curMethodSym := dd.symbol
    ) {
      //println(s"generating $dd")
      val sym = dd.symbol
      val name = genDefName(sym)
      val paramSyms = defParamSymbols(dd)
      val ty =
        if (dd.symbol.isClassConstructor) Prim.Unit
        else genType(sym.tpe.resultType)
      val rel = Seq(genClassDefn(sym))

      if (dd.symbol.isDeferred) {
        val params = genParams(paramSyms, define = false)
        name -> ir.Declare(name, ty, params, rel)
      } else {
        val env = new Env
        scoped (
          curEnv := env,
          curLabelEnv := new LabelEnv(env),
          curLocalInfo := (new CollectLocalInfo).collect(dd.rhs)
        ) {
          val params = genParams(paramSyms, define = true)
          val body = genDefBody(dd.rhs, params)
          name -> ir.Define(name, ty, params, body, rel)
        }
      }
    }

    def defParamSymbols(dd: DefDef): List[Symbol] = {
      val vp = dd.vparamss
      if (vp.isEmpty) Nil else vp.head.map(_.symbol)
    }

    def genParams(paramSyms: List[Symbol], define: Boolean): Seq[ir.Node] = {
      val self = ir.Param(Name.Simple("this"), genClassDefn(curClassSym))
      val params = paramSyms.map { sym =>
        val node = ir.Param(genParamName(sym), genType(sym.tpe))
        if (define)
          curEnv.enter(sym, node)
        node
      }

      self +: params
    }

    def notMergeableGuard(f: => Tails): Tails =
      try f
      catch {
        case Tails.NotMergeable(tails) => tails
      }

    def genDefBody(body: Tree, params: Seq[ir.Node]) =
      notMergeableGuard {
        body match {
          case Block(List(ValDef(_, nme.THIS, _, _)),
                     label @ LabelDef(name, Ident(nme.THIS) :: _, rhs)) =>

            curLabelEnv.enterLabel(label)
            val start = Focus.start()
            val values = params.take(label.params.length)
            curLabelEnv.enterLabelCall(label.symbol, values, start)
            scoped (
              curThis := curLabelEnv.resolveLabelParams(label.symbol).head
            ) {
              genLabel(label)
            }
          case _ =>
            scoped (
              curThis := params.head
            ) {
              genExpr(body, Focus.start())
            }
        }
      }.end(ir.Return(_, _, _))

    def genExpr(tree: Tree, focus: Focus): Tails = tree match {
      case ld: LabelDef =>
        assert(ld.params.length == 0)
        val label = curLabelEnv.enterLabel(ld)
        curLabelEnv.enterLabelCall(ld.symbol, Seq(), focus)
        genLabel(ld)

      case vd: ValDef =>
        val (rfocus, rt) = genExpr(vd.rhs, focus).merge
        val isMutable = curLocalInfo.mutableVars.contains(vd.symbol)
        val vdfocus =
          if (!isMutable) {
            curEnv.enter(vd.symbol, rfocus.value)
            rfocus withValue ir.Unit()
          } else {
            val alloc = ir.Alloc(genType(vd.symbol.tpe))
            curEnv.enter(vd.symbol, alloc)
            rfocus mapEf (ir.Store(_, alloc, rfocus.value))
          }
        vdfocus +: rt

      case If(cond, thenp, elsep) =>
        genIf(cond, thenp, elsep, focus)

      case Return(expr) =>
        val (efocus, etails) = genExpr(expr, focus).merge
        (efocus withCf ir.Return(efocus.cf, efocus.ef, efocus.value)) +: etails

      case Try(expr, catches, finalizer) if catches.isEmpty && finalizer.isEmpty =>
        genExpr(expr, focus)

      case Try(expr, catches, finalizer) =>
        genTry(expr, catches, finalizer, focus)

      case Throw(expr) =>
        val (efocus, etails) = genExpr(expr, focus).merge
        (efocus withCf ir.Throw(efocus.cf, efocus.ef, efocus.value)) +: etails

      case app: Apply =>
        genApply(app, focus)

      case app: ApplyDynamic =>
        genApplyDynamic(app, focus)

      case This(qual) =>
        Tails.open(focus withValue {
          if (tree.symbol == curClassSym.get) curThis
          else ir.ValueOf(genClassDefn(tree.symbol))
        })

      case Select(qual, sel) =>
        val sym = tree.symbol
        if (sym.isModule)
          Tails.open(focus withValue ir.ValueOf(genClassDefn(sym)))
        else if (sym.isStaticMember)
          Tails.open(focus withValue genStaticMember(sym))
        else {
          val (qfocus, qt) = genExpr(qual, focus).merge
          val elem = ir.Elem(qfocus.value, ir.ValueOf(genFieldDefn(tree.symbol)))
          (qfocus mapEf (ir.Load(_, elem))) +: qt
        }

      case id: Ident =>
        val sym = id.symbol
        Tails.open {
          if (!curLocalInfo.mutableVars.contains(sym))
            focus withValue {
              if (sym.isModule) ir.ValueOf(genClassDefn(sym))
              else curEnv.resolve(sym)
            }
          else
            focus mapEf (ir.Load(_, curEnv.resolve(sym)))
        }

      case lit: Literal =>
        Tails.open(focus withValue genValue(lit))

      case block: Block =>
        genBlock(block, focus)

      case Typed(Super(_, _), _) =>
        Tails.open(focus withValue curThis)

      case Typed(expr, _) =>
        genExpr(expr, focus)

      case Assign(lhs, rhs) =>
        lhs match {
          case sel @ Select(qual, _) =>
            val (qfocus, qt) = genExpr(qual, focus).merge
            val (rfocus, rt) = genExpr(rhs, qfocus).merge
            val elem = ir.Elem(qfocus.value, ir.ValueOf(genFieldDefn(sel.symbol)))
            (rfocus mapEf (ir.Store(_, elem, rfocus.value))) +: (qt ++ rt)

          case id: Ident =>
            val (rfocus, rt) = genExpr(rhs, focus).merge
            (rfocus mapEf (ir.Store(_, curEnv.resolve(id.symbol), rfocus.value))) +: rt
        }

      case av: ArrayValue =>
        genArrayValue(av, focus)

      case m: Match =>
        genSwitch(m, focus)

      case fun: Function =>
        undefined(focus)

      case EmptyTree =>
        Tails.open(focus withValue ir.Unit())

      case _ =>
        abort("Unexpected tree in genExpr: " +
              tree + "/" + tree.getClass + " at: " + tree.pos)
    }

    def genValue(lit: Literal): ir.Node = {
      val value = lit.value
      value.tag match {
        case NullTag =>
          ir.Null()
        case UnitTag =>
          ir.Unit()
        case BooleanTag =>
          if (value.booleanValue) ir.True() else ir.False()
        case ByteTag =>
          ir.I8(value.intValue.toByte)
        case ShortTag | CharTag =>
          ir.I16(value.intValue.toShort)
        case IntTag =>
          ir.I32(value.intValue)
        case LongTag =>
          ir.I64(value.longValue)
        case FloatTag =>
          ir.F32(value.floatValue)
        case DoubleTag =>
          ir.F64(value.doubleValue)
        case StringTag =>
          ir.Str(value.stringValue)
        case ClazzTag =>
          ir.Box(ir.Tag(genType(value.typeValue)), ref(javaLangClass))
        case EnumTag =>
          genStaticMember(value.symbolValue)
      }
    }

    def genStaticMember(sym: Symbol) =
      ir.ValueOf(genFieldDefn(sym))

    def genTry(expr: Tree, catches: List[Tree], finalizer: Tree, focus: Focus) = {
      val cf          = ir.Try(focus.cf)
      val normal      = genExpr(expr, focus withCf cf)
      val exceptional = genCatch(catches, focus withCf ir.CaseException(cf))

      genFinally(normal ++ exceptional, finalizer)
    }

    def genCatch(catches: List[Tree], focus: Focus) = {
      val exc    = ir.ExceptionOf(focus.cf)
      val switch = ir.Switch(focus.cf, ir.TagOf(exc))

      val cases =
        catches.map {
          case CaseDef(pat, _, body) =>
            val (symopt, excty) = pat match {
              case Typed(Ident(nme.WILDCARD), tpt) =>
                (None, genType(tpt.tpe))
              case Ident(nme.WILDCARD) =>
                (None, genType(ThrowableClass.tpe))
              case Bind(_, _) =>
                (Some(pat.symbol), genType(pat.symbol.tpe))
            }
            symopt foreach (curEnv.enter(_, ir.Cast(exc, excty)))
            genExpr(body, focus withCf ir.CaseConst(switch, ir.Tag(excty)))
        }
      val default =
        Tails.termn(ir.Throw(ir.CaseDefault(switch), focus.ef, exc))

      Tails.flatten(default +: cases)
    }

    def genFinally(tails: Tails, finalizer: Tree) = {
      val Tails(open, closed) = tails

      def genClosed(focus: Focus, wrap: (ir.Node, ir.Node) => ir.Node): Seq[ir.Node] = {
        val ir.End(ends) = genExpr(finalizer, focus).end((cf, ef, v) => wrap(cf, ef))
        ends
      }

      val closedtails = Tails(Seq(), closed.flatMap {
        case ir.Return(cf, ef, v) => genClosed(Focus(cf, ef, ir.Unit()), ir.Return(_, _, v))
        case ir.Throw(cf, ef, v)  => genClosed(Focus(cf, ef, ir.Unit()), ir.Throw(_, _, v))
        case ir.Undefined(cf, ef) => genClosed(Focus(cf, ef, ir.Unit()), ir.Undefined(_, _))
      })

      val opentails =
        if (open.isEmpty) Tails.empty
        else {
          val (focus, _) = Tails(open, Seq()).merge
          genExpr(finalizer, focus)
        }

      opentails ++ closedtails
    }

    def genBlock(block: Block, focus: Focus) = {
      val Block(stats, last) = block

      def isCaseLabelDef(tree: Tree) =
        tree.isInstanceOf[LabelDef] && hasSynthCaseSymbol(tree)

      def translateMatch(last: LabelDef) = {
        val (prologue, cases) = stats.span(s => !isCaseLabelDef(s))
        val labels = cases.map { case label: LabelDef => label }
        genMatch(prologue, labels :+ last, focus)
      }

      last match {
        case label: LabelDef if isCaseLabelDef(label) =>
          translateMatch(label)

        case Apply(TypeApply(Select(label: LabelDef, nme.asInstanceOf_Ob), _), _)
            if isCaseLabelDef(label) =>
          translateMatch(label)

        case _ =>
          val (focs, tails) = sequenced(stats, focus)(genExpr(_, _))
          val lastfocus = focs.lastOption.getOrElse(focus)
          genExpr(last, lastfocus) ++ tails
      }
    }

    def genMatch(prologue: List[Tree], lds: List[LabelDef], focus: Focus) = {
      val (prfocus, prt) = sequenced(prologue, focus)(genExpr(_, _))
      val lastfocus = prfocus.lastOption.getOrElse(focus)

      for (ld <- lds) {
        curLabelEnv.enterLabel(ld)
      }
      curLabelEnv.enterLabelCall(lds.head.symbol, Seq(), lastfocus)

      var lasttails = prt
      for (ld <- lds) {
        lasttails = lasttails ++ genLabel(ld)
      }

      lasttails
    }

    // TODO: while(true) loops
    def genLabel(label: LabelDef) = {
      val cf = curLabelEnv.resolveLabel(label.symbol)
      val ef = curLabelEnv.resolveLabelEf(label.symbol)
      genExpr(label.rhs, Focus(cf, ef, ir.Unit()))
    }

    def genArrayValue(av: ArrayValue, focus: Focus): Tails = {
      val ArrayValue(tpt, elems) = av
      val ty           = genType(tpt.tpe)
      val len          = elems.length
      val salloc       = ir.Salloc(ty, ir.I32(len))
      val (rfocus, rt) =
        if (elems.isEmpty)
          (focus, Tails.empty)
        else {
          val (vfocus, vt) = sequenced(elems, focus)(genExpr(_, _))
          val values       = vfocus.map(_.value)
          val lastfocus    = vfocus.lastOption.getOrElse(focus)
          val (sfocus, st) = sequenced(values.zipWithIndex, lastfocus) { (vi, foc) =>
            val (value, i) = vi
            Tails.open(foc withEf ir.Store(foc.ef, ir.Elem(salloc, ir.I32(i)), value))
          }
          (sfocus.last, vt ++ st)
        }

      (rfocus withValue salloc) +: rt
    }

    def genIf(cond: Tree, thenp: Tree, elsep: Tree, focus: Focus) = {
      val (condfocus, condt) = genExpr(cond, focus).merge
      val cf = ir.If(condfocus.cf, condfocus.value)
      condt ++
      genExpr(thenp, condfocus withCf ir.CaseTrue(cf)) ++
      genExpr(elsep, condfocus withCf ir.CaseFalse(cf))
    }

    def genSwitch(m: Match, focus: Focus): Tails = {
      val Match(sel, cases) = m

      val (selfocus, selt) = genExpr(sel, focus).merge
      val switch = ir.Switch(selfocus.cf, selfocus.value)

      val defaultBody =
        cases.collectFirst {
          case c @ CaseDef(Ident(nme.WILDCARD), _, body) => body
        }.get
      val defaultTails =
        genExpr(defaultBody, selfocus withCf ir.CaseDefault(switch))
      val branchTails: Seq[Tails] =
        cases.map {
          case CaseDef(Ident(nme.WILDCARD), _, _) =>
            Tails.empty
          case CaseDef(pat, guard, body) =>
            assert(guard.isEmpty)
            val consts =
              pat match {
                case lit: Literal =>
                  List(genValue(lit))
                case Alternative(alts) =>
                  alts.map {
                    case lit: Literal => genValue(lit)
                  }
                case _ =>
                  Nil
              }
            val cf = consts match {
              case const :: Nil => ir.CaseConst(switch, consts.head)
              case _            => ir.Merge(consts.map(ir.CaseConst(switch, _)))
            }
            genExpr(body, selfocus withCf cf)
        }

      Tails.flatten(defaultTails +: branchTails)
    }

    def genApplyDynamic(app: ApplyDynamic, focus: Focus) =
      undefined(focus)

    def genApply(app: Apply, focus: Focus): Tails = {
      val Apply(fun, args) = app

      fun match {
        case _: TypeApply =>
          genApplyTypeApply(app, focus)
        case Select(Super(_, _), _) =>
          genApplySuper(app, focus)
        case Select(New(_), nme.CONSTRUCTOR) =>
          genApplyNew(app, focus)
        case _ =>
          val sym = fun.symbol

          if (sym.isLabel) {
            genApplyLabel(app, focus)
          } else if (scalaPrimitives.isPrimitive(sym)) {
            genPrimitiveOp(app, focus)
          } else if (currentRun.runDefinitions.isBox(sym)) {
            val arg = args.head
            genPrimitiveBox(arg, arg.tpe, focus)
          } else if (currentRun.runDefinitions.isUnbox(sym)) {
            genPrimitiveUnbox(args.head, app.tpe, focus)
          } else {
            genNormalApply(app, focus)
          }
      }
    }

    def genApplyLabel(tree: Tree, focus: Focus) = notMergeableGuard {
      val Apply(fun, args) = tree
      val label = curLabelEnv.resolveLabel(fun.symbol)
      val (argsfocus, tails) = sequenced(args, focus)(genExpr(_, _))
      val lastfocus = argsfocus.lastOption.getOrElse(focus)
      curLabelEnv.enterLabelCall(fun.symbol, argsfocus.map(_.value), lastfocus)
      val res = tails
      res
    }

    lazy val primitive2box = Map(
      BooleanTpe -> ir.Extern(Name.Simple("java.lang.Boolean")),
      ByteTpe    -> ir.Extern(Name.Simple("java.lang.Byte")),
      CharTpe    -> ir.Extern(Name.Simple("java.lang.Character")),
      ShortTpe   -> ir.Extern(Name.Simple("java.lang.Short")),
      IntTpe     -> ir.Extern(Name.Simple("java.lang.Integer")),
      LongTpe    -> ir.Extern(Name.Simple("java.lang.Long")),
      FloatTpe   -> ir.Extern(Name.Simple("java.lang.Float")),
      DoubleTpe  -> ir.Extern(Name.Simple("java.lang.Double"))
    )

    lazy val javaLangClass = ir.Extern(Name.Simple("java.lang.Class"))

    lazy val ctorName = Name.Simple(nme.CONSTRUCTOR.toString)

    def ref(node: ir.Node) =
      ir.Type(ir.Shape.Ref(ir.Shape.Hole), Seq(node))

    def genPrimitiveBox(expr: Tree, tpe: Type, focus: Focus) = {
      val (efocus, et) = genExpr(expr, focus).merge
      val box = ir.Box(efocus.value, ref(primitive2box(tpe.widen)))

      (efocus withValue box) +: et
    }

    def genPrimitiveUnbox(expr: Tree, tpe: Type, focus: Focus) = {
      val (efocus, et) = genExpr(expr, focus).merge
      val unbox  = ir.Unbox(efocus.value, primitive2box(tpe.widen))

      (efocus withValue unbox) +: et
    }

    def genPrimitiveOp(app: Apply, focus: Focus): Tails = {
      import scalaPrimitives._

      val sym = app.symbol
      val Apply(fun @ Select(receiver, _), args) = app
      val code = scalaPrimitives.getPrimitive(sym, receiver.tpe)

      if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
        genSimpleOp(app, receiver :: args, code, focus)
      else if (code == CONCAT)
        genStringConcat(app, receiver, args, focus)
      else if (code == HASH)
        genHash(app, receiver, focus)
      else if (isArrayOp(code))
        genArrayOp(app, code, focus)
      else if (isCoercion(code))
        genCoercion(app, receiver, code, focus)
      else if (code == SYNCHRONIZED)
        genSynchronized(app, focus)
      else
        abort("Unknown primitive operation: " + sym.fullName + "(" +
              fun.symbol.simpleName + ") " + " at: " + (app.pos))
    }

    def numOfType(num: Int, ty: ir.Node) = ty match {
      case Prim.I8  => ir.I8 (num.toByte)
      case Prim.I16 => ir.I16(num.toShort)
      case Prim.I32 => ir.I32(num)
      case Prim.I64 => ir.I64(num.toLong)
      case Prim.F32 => ir.F32(num.toFloat)
      case Prim.F64 => ir.F64(num.toDouble)
      case _      => unreachable
    }

    def genSimpleOp(app: Apply, args: List[Tree], code: Int, focus: Focus) = {
      val retty = genType(app.tpe)

      args match {
        case List(right)       => genUnaryOp(code, right, retty, focus)
        case List(left, right) => genBinaryOp(code, left, right, retty, focus)
        case _                 => abort("Too many arguments for primitive function: " + app)
      }
    }

    def genUnaryOp(code: Int, right: Tree, retty: ir.Node, focus: Focus) = {
      import scalaPrimitives._

      val (rfocus, rt) = genExpr(right, focus).merge
      val resfocus =
        code match {
          case POS  => rfocus
          case NEG  => rfocus mapValue (v => ir.Sub(numOfType(0, retty), v))
          case NOT  => rfocus mapValue (v => ir.Xor(numOfType(-1, retty), v))
          case ZNOT => rfocus mapValue (v => ir.Xor(ir.True(), v))
          case _    => abort("Unknown unary operation code: " + code)
        }

      rfocus +: rt
    }

    def genBinaryOp(code: Int, left: Tree, right: Tree, retty: ir.Node,
                    focus: Focus): Tails = {
      import scalaPrimitives._

      val lty   = genType(left.tpe)
      val rty   = genType(right.tpe)

      code match {
        case ADD  => genBinaryOp(ir.Add,  left, right, retty, focus)
        case SUB  => genBinaryOp(ir.Sub,  left, right, retty, focus)
        case MUL  => genBinaryOp(ir.Mul,  left, right, retty, focus)
        case DIV  => genBinaryOp(ir.Div,  left, right, retty, focus)
        case MOD  => genBinaryOp(ir.Mod,  left, right, retty, focus)
        case OR   => genBinaryOp(ir.Or,   left, right, retty, focus)
        case XOR  => genBinaryOp(ir.Xor,  left, right, retty, focus)
        case AND  => genBinaryOp(ir.And,  left, right, retty, focus)
        case LSL  => genBinaryOp(ir.Shl,  left, right, retty, focus)
        case LSR  => genBinaryOp(ir.Lshr, left, right, retty, focus)
        case ASR  => genBinaryOp(ir.Ashr, left, right, retty, focus)

        case LT   => genBinaryOp(ir.Lt,  left, right, binaryOperationType(lty, rty), focus)
        case LE   => genBinaryOp(ir.Lte, left, right, binaryOperationType(lty, rty), focus)
        case GT   => genBinaryOp(ir.Gt,  left, right, binaryOperationType(lty, rty), focus)
        case GE   => genBinaryOp(ir.Gte, left, right, binaryOperationType(lty, rty), focus)

        case EQ   => genEqualityOp(left, right, ref = false, negated = false, focus)
        case NE   => genEqualityOp(left, right, ref = false, negated = true,  focus)
        case ID   => genEqualityOp(left, right, ref = true,  negated = false, focus)
        case NI   => genEqualityOp(left, right, ref = true,  negated = true,  focus)

        case ZOR  => genIf(left, Literal(Constant(true)), right, focus)
        case ZAND => genIf(left, right, Literal(Constant(false)), focus)

        case _    => abort("Unknown binary operation code: " + code)
      }
    }

    def genBinaryOp(op: (ir.Node, ir.Node) => ir.Node, left: Tree, right: Tree, retty: ir.Node,
                    focus: Focus): Tails = {
      val (lfocus, lt) = genExpr(left, focus).merge
      val lcoerced     = genCoercion(lfocus.value, genType(left.tpe), retty)
      val (rfocus, rt) = genExpr(right, lfocus).merge
      val rcoerced     = genCoercion(rfocus.value, genType(right.tpe), retty)

      (rfocus withValue op(lcoerced, rcoerced)) +: (lt ++ rt)
    }

    def genEqualityOp(left: Tree, right: Tree, ref: Boolean, negated: Boolean,
                      focus: Focus) = {
      val eq = if (negated) ir.Neq else ir.Eq

      genKind(left.tpe) match {
        case ClassKind(_) | BottomKind(NullClass) =>
          val (lfocus, lt) = genExpr(left, focus).merge
          val (rfocus, rt) = genExpr(right, lfocus).merge
          val resfocus =
            if (ref)
              rfocus withValue eq(lfocus.value, rfocus.value)
            else if (lfocus.value.desc eq Desc.Null)
              rfocus withValue eq(rfocus.value, ir.Null())
            else if (rfocus.value.desc eq Desc.Null)
              rfocus withValue eq(lfocus.value, ir.Null())
            else {
              val equals = ir.Equals(rfocus.ef, lfocus.value, rfocus.value)
              val value = if (!negated) equals else ir.Xor(ir.True(), equals)
              rfocus withEf equals withValue value
            }
          resfocus +: (lt ++ rt)

        case kind =>
          val lty   = genType(left.tpe)
          val rty   = genType(right.tpe)
          val retty = binaryOperationType(lty, rty)
          genBinaryOp(eq, left, right, retty, focus)
      }
    }

    def binaryOperationType(lty: ir.Node, rty: ir.Node) = (lty, rty) match {
      case (Prim.I(lwidth), Prim.I(rwidth)) =>
        if (lwidth >= rwidth) lty else rty
      case (Prim.I(_), Prim.F(_)) =>
        rty
      case (Prim.F(_), Prim.I(_)) =>
        lty
      case (Prim.F(lwidth), Prim.F(rwidth)) =>
        if (lwidth >= rwidth) lty else rty
      case (ty1 , ty2) if ty1 type_== ty2 =>
        ty1
      case (Prim.Null, _) =>
        rty
      case (_, Prim.Null) =>
        lty
      case _ =>
        abort(s"can't perform binary opeation between $lty and $rty")
    }

    def genStringConcat(tree: Tree, left: Tree, args: List[Tree], focus: Focus) = {
      val List(right) = args
      val (lfocus, lt) = genExpr(left, focus).merge
      val (rfocus, rt) = genExpr(right, lfocus).merge

      (rfocus withValue ir.Add(lfocus.value, rfocus.value)) +: (lt ++ rt)
    }

    def genHash(tree: Tree, receiver: Tree, focus: Focus) = {
      val method = getMember(ScalaRunTimeModule, nme.hash_)
      val (recfocus, rt) = genExpr(receiver, focus).merge

      genMethodCall(method, recfocus.value, Nil, recfocus) ++ rt
    }

    def genArrayOp(app: Apply, code: Int, focus: Focus): Tails = {
      import scalaPrimitives._

      val Apply(Select(array, _), args) = app
      val (allfocus, allt) = sequenced(array :: args, focus)(genExpr(_, _))
      val lastfocus  = allfocus.last
      def arrayvalue = allfocus(0).value
      def argvalues  = allfocus.tail.map(_.value)
      val rfocus =
        if (scalaPrimitives.isArrayGet(code))
          lastfocus mapEf (ir.Load(_, ir.Elem(arrayvalue, argvalues(0))))
        else if (scalaPrimitives.isArraySet(code))
          lastfocus mapEf (ir.Store(_, ir.Elem(arrayvalue, argvalues(0)), argvalues(1)))
        else
          lastfocus withValue ir.Length(arrayvalue)

      rfocus +: allt
    }

    def genSynchronized(app: Apply, focus: Focus): Tails = {
      val Apply(Select(receiver, _), List(arg)) = app
      val (recfocus, rt) = genExpr(receiver, focus).merge
      val (argfocus, at) = genExpr(arg, recfocus).merge

      argfocus +: (rt ++ at)
    }

    def genCoercion(app: Apply, receiver: Tree, code: Int, focus: Focus): Tails = {
      val (rfocus, rt) = genExpr(receiver, focus).merge
      val (fromty, toty) = coercionTypes(code)

      (rfocus mapValue (genCoercion(_, fromty, toty))) +: rt
    }

    def genCoercion(value: ir.Node, fromty: ir.Node, toty: ir.Node): ir.Node =
      if (fromty type_== toty)
        value
      else {
        val op = (fromty, toty) match {
          case (Prim.I(lwidth), Prim.I(rwidth))
            if lwidth < rwidth        => ir.Zext
          case (Prim.I(lwidth), Prim.I(rwidth))
            if lwidth > rwidth        => ir.Trunc
          case (Prim.I(_), Prim.F(_)) => ir.Sitofp
          case (Prim.F(_), Prim.I(_)) => ir.Fptosi
          case (Prim.F64, Prim.F32)   => ir.Fptrunc
          case (Prim.F32, Prim.F64)   => ir.Fpext
          case (Prim.Null, _)         => ir.Cast
        }
        op(value, toty)
      }

    def coercionTypes(code: Int): (ir.Node, ir.Node) = {
      import scalaPrimitives._

      code match {
        case B2B       => (Prim.I8, Prim.I8)
        case B2S | B2C => (Prim.I8, Prim.I16)
        case B2I       => (Prim.I8, Prim.I32)
        case B2L       => (Prim.I8, Prim.I64)
        case B2F       => (Prim.I8, Prim.F32)
        case B2D       => (Prim.I8, Prim.F64)

        case S2B       | C2B       => (Prim.I16, Prim.I8)
        case S2S | S2C | C2S | C2C => (Prim.I16, Prim.I16)
        case S2I       | C2I       => (Prim.I16, Prim.I32)
        case S2L       | C2L       => (Prim.I16, Prim.I64)
        case S2F       | C2F       => (Prim.I16, Prim.F32)
        case S2D       | C2D       => (Prim.I16, Prim.F64)

        case I2B       => (Prim.I32, Prim.I8)
        case I2S | I2C => (Prim.I32, Prim.I16)
        case I2I       => (Prim.I32, Prim.I32)
        case I2L       => (Prim.I32, Prim.I64)
        case I2F       => (Prim.I32, Prim.F32)
        case I2D       => (Prim.I32, Prim.F64)

        case L2B       => (Prim.I64, Prim.I8)
        case L2S | L2C => (Prim.I64, Prim.I16)
        case L2I       => (Prim.I64, Prim.I32)
        case L2L       => (Prim.I64, Prim.I64)
        case L2F       => (Prim.I64, Prim.F32)
        case L2D       => (Prim.I64, Prim.F64)

        case F2B       => (Prim.F32, Prim.I8)
        case F2S | F2C => (Prim.F32, Prim.I16)
        case F2I       => (Prim.F32, Prim.I32)
        case F2L       => (Prim.F32, Prim.I64)
        case F2F       => (Prim.F32, Prim.F32)
        case F2D       => (Prim.F32, Prim.F64)

        case D2B       => (Prim.F64, Prim.I8)
        case D2S | D2C => (Prim.F64, Prim.I16)
        case D2I       => (Prim.F64, Prim.I32)
        case D2L       => (Prim.F64, Prim.I64)
        case D2F       => (Prim.F64, Prim.F32)
        case D2D       => (Prim.F64, Prim.F64)
      }
    }

    def genApplyTypeApply(app: Apply, focus: Focus) = {
      val Apply(TypeApply(fun @ Select(receiver, _), targs), _) = app
      val ty = genType(targs.head.tpe)
      val (rfocus, rt) = genExpr(receiver, focus).merge
      val value = fun.symbol match {
        case Object_isInstanceOf => ir.Is(rfocus.value, ty)
        case Object_asInstanceOf => ir.Cast(rfocus.value, ty)
      }

      (rfocus withValue value) +: rt
    }

    def genNormalApply(app: Apply, focus: Focus) = {
      val Apply(fun @ Select(receiver, _), args) = app
      val (rfocus, rt) = genExpr(receiver, focus).merge

      genMethodCall(fun.symbol, rfocus.value, args, rfocus) ++ rt
    }

    def genApplySuper(app: Apply, focus: Focus) = {
      val Apply(fun @ Select(sup, _), args) = app

      genMethodCall(fun.symbol, curThis.get, args, focus)
    }

    def genApplyNew(app: Apply, focus: Focus) = {
      val Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) = app
      val ctor = fun.symbol
      val kind = genKind(tpt.tpe)
      val ty   = toIRType(kind)

      kind match {
        case ArrayKind(of) =>
          genNewArray(toIRType(of), args.head, focus)
        case ckind: ClassKind =>
          genNew(ckind.sym, ctor, args, focus)
        case ty =>
          abort("unexpected new: " + app + "\ngen type: " + ty)
      }
    }

    def genNewArray(elemty: ir.Node, length: Tree, focus: Focus) = {
      val (lfocus, lt) = genExpr(length, focus).merge

      (lfocus withValue ir.Salloc(elemty, lfocus.value)) +: lt
    }

    def genNew(sym: Symbol, ctorsym: Symbol, args: List[Tree], focus: Focus) = {
      val alloc = ir.Alloc(genClassDefn(sym))

      genMethodCall(ctorsym, alloc, args, focus)
    }

    def genMethodCall(sym: Symbol, self: ir.Node, args: Seq[Tree], focus: Focus): Tails = {
      val (argfocus, argt) = sequenced(args, focus)(genExpr(_, _))
      val argvalues        = argfocus.map(_.value)
      val lastfocus        = argfocus.lastOption.getOrElse(focus)
      val stat             = genDefDefn(sym)
      val call             = ir.Call(lastfocus.ef, ir.ValueOf(stat), self +: argvalues)

      (lastfocus withEf call withValue call) +: argt
    }

  }
}
