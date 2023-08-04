package lms.koika

import lms.core.stub._
import lms.core.virtualize
import lms.macros.SourceContext

import lms.macros.RefinedManifest
import lms.collection.mutable._

@virtualize
class StagedProcInterp1bPC extends TutorialFunSuite {
  val under = "proci1b_staged_"

  val REGFILE_SIZE: Int = 7
  val regfile_main = """
    |int main(int argc, char *argv[]) {
    |  int regfile[7] = {0, 0, 0, 0, 0, 0, 0};
    |  Snippet(regfile);
    |  for (int i = 0; i < 6; i++) {
    |    printf("%d ", regfile[i]);
    |  }
    |  printf("\n");
    |  return 0;
    |}
    |""".stripMargin

  def constructMain(expected: Array[Int]): String = {
    var ret = s"""
    |// cc file.c for execution
    |// cbmc -DCBMC file.c for verification
    |#ifndef CBMC
    |#define __CPROVER_assert(b,s) 0
    |#define nondet_uint() 0
    |#endif
    |
    |int bounded(int low, int high) {
    |  int x = nondet_uint();
    |  if (x < low) {
    |    x = low;
    |  }
    |  if (x > high) {
    |    x = high;
    |  }
    |  return x;
    |}
    |
    |int init(struct timer *t) {
    |  t->ticks = 0;
    |  return 0;
    |}
    |
    |
    |int main(int argc, char *argv[]) {
    |  struct timer t1;
    |  struct timer t2;
    |  init(&t1);
    |  init(&t2);
    |  int input = bounded(0, 10);
    |  int regfile[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    |  int regfile2[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ,0};
    |  regfile[1] = input;
    |  regfile2[1] = input;
    |  for (int i = 7; i < 11; i++) {
    |    regfile[i] = bounded(0, 10);
    |    regfile2[i] = bounded(0, 10);
    |  }
    |  int c1 = Snippet(regfile);
    |  int c2 = Snippet(regfile2);
    |  __CPROVER_assert(c1 == c2, "timing leak");
    |""".stripMargin

    var printexpected = s"""
    |  printf("\\nexpected:\\n");
    |  printf(""""
    for (i <- 0 until expected.length) {
      printexpected += s"""${expected(i)} """
    }
    printexpected += s""" ");
    |""".stripMargin

    for (i <- 0 until expected.length) {
      ret += s"""
    |  if (regfile[$i] != ${expected(i)}) {
    |    printf("error: regfile[$i] = %d, expected ${expected(
                 i
               )}\\n", regfile[$i]);
    |    goto error;
    |  }
    |""".stripMargin
    }
    ret += s"""
    |  printf("OK\\n");
    |  return 0;
    |error:
    |  printf("\\nRegfile:\\n");
    |  for (int i = 0; i < 6; i++) {
    |    printf("%d ", regfile[i]);
    |  }
    |  ${printexpected}
    |  printf("\\n\\nFAILED\\n");
    |  return 1;
    |}
    |""".stripMargin
    ret
  }

  override def exec(label: String, code: String, suffix: String = "c") =
    super.exec(label, code, suffix)

  override def check(label: String, code: String, suffix: String = "c") =
    super.check(label, code, suffix)

  val DEBUG = true

  @CStruct
  case class timer(
      ticks: Int,
      rf: Array[Int]
  )

  trait Interp extends Dsl with StructOps {

    @CStructOps
    abstract class Ptimer[timer](
        ticks: Int,
        rf: Array[Int]
    )

    abstract sealed class Instruction
    case class Add(rd: Reg, rs1: Reg, rs2: Reg) extends Instruction
    case class Addi(rd: Reg, rs1: Reg, imm: Int) extends Instruction
    case class Mul(rd: Reg, rs1: Reg, rs2: Reg) extends Instruction
    case class Sub(rd: Reg, rs1: Reg, rs2: Reg) extends Instruction
    case class JumpNZ(rs: Reg, imm: Int) extends Instruction
    case class JumpNeg(rs: Reg, imm: Int) extends Instruction
    // case class Load(rd: Reg, rs1: Reg, imm: Int) extends Instruction
    // case class Store(rs1: Reg, rs2: Reg, imm: Int) extends Instruction

    // workaround for enums
    var __i_enum: Int = 137000
    def iota: Int = {
      __i_enum += 1
      __i_enum
    }

    val AddOp = iota
    val MulOp = iota
    val SubOp = iota

    val EqOp = iota
    val NeqOp = iota
    val LtOp = iota
    val GtOp = iota
    val LeOp = iota
    val GeOp = iota

    val BranchTaken = iota
    val BranchNotTaken = iota
    val NotBranch = iota

    // Type black magic
    type ¬[A] = A => Nothing
    type ∨[T, U] = ¬[¬[T] with ¬[U]]
    type ¬¬[A] = ¬[¬[A]]
    type |∨|[T, U] = { type λ[X] = ¬¬[X] <:< (T ∨ U) }
    // see https://stackoverflow.com/questions/3508077/how-to-define-type-disjunction-union-types

    type Program = List[Instruction]
    type RegFile = Array[Int]

    case class Reg(id: Int)
    val ZERO: Reg = Reg(0)
    val A0: Reg = Reg(1)
    val A1: Reg = Reg(2)
    val A2: Reg = Reg(3)
    val A3: Reg = Reg(4)
    val A4: Reg = Reg(5)
    val A5: Reg = Reg(6)
    val SECRET1: Reg = Reg(7)
    val SECRET2: Reg = Reg(8)
    val SECRET3: Reg = Reg(9)
    val SECRET4: Reg = Reg(10)

    val NOP = Addi(ZERO, ZERO, 0)

    implicit def reg2int(r: Reg): Int = r.id
    implicit def reg2rep(r: Reg): Rep[Int] = unit(r.id)

    class Port[T: Manifest](
        init: T,
        customEqual: Option[(Rep[T], Rep[T]) => Rep[Boolean]] = None
    ) {

      private var readport: Var[T] = __newVar(init)
      private var writeport: Var[T] = __newVar(init)

      private def equal(a: Rep[T], b: Rep[T]): Rep[Boolean] =
        customEqual match {
          case Some(f) => f(a, b)
          case None    => a == b
        }

      def read: Rep[T] = readVar(readport)

      def write(d: Rep[T]): Rep[Unit] = writeport = d

      def flush(): Rep[Unit] = writeport = init
      def freeze(): Rep[Unit] = writeport = readport

      def update(): Rep[Unit] = readport = writeport

      def isDefault: Rep[Boolean] = equal(read, unit(init))
      def isAmong(vs: T*): Rep[Boolean] =
        vs.foldLeft(unit(false))((acc, v) => acc || equal(read, unit(v)))
    }

    def readProgram(file: String): Program = {
      scala.io.Source
        .fromFile(file)
        .getLines()
        .map { line =>
          val tokens = line.split(" ")
          tokens match {
            case Array("add", rd, rs1, rs2) =>
              Add(Reg(rd.toInt), Reg(rs1.toInt), Reg(rs2.toInt))
            case Array("addi", rd, rs1, imm) =>
              Addi(Reg(rd.toInt), Reg(rs1.toInt), imm.toInt)
            case Array("mul", rd, rs1, rs2) =>
              Mul(Reg(rd.toInt), Reg(rs1.toInt), Reg(rs2.toInt))
            case Array("sub", rd, rs1, rs2) =>
              Sub(Reg(rd.toInt), Reg(rs1.toInt), Reg(rs2.toInt))
            case Array("jumpnz", rs, imm) =>
              JumpNZ(Reg(rs.toInt), imm.toInt)
            case Array("jumpneg", rs, imm) =>
              JumpNeg(Reg(rs.toInt), imm.toInt)
            case _ => Predef.println(s"Unknown instruction: $line"); NOP
          }
        }
        .toList
    }

    def expectedResult(prog: Program): Array[Int] = {
      var rf: Array[Int] = new Array[Int](REGFILE_SIZE + 4)

      var i: Int = 0
      while (i < prog.length) {
        prog(i) match {
          case Add(rd, rs1, rs2) => {
            rf(rd) = rf(rs1) + rf(rs2)
            i = i + 1
          }
          case Addi(rd, rs1, imm) => {
            rf(rd) = rf(rs1) + imm
            i = i + 1
          }
          case Mul(rd, rs1, rs2) => {
            rf(rd) = rf(rs1) * rf(rs2)
            i = i + 1
          }
          case Sub(rd, rs1, rs2) => {
            rf(rd) = rf(rs1) - rf(rs2)
            i = i + 1
          }
          case JumpNZ(rs, target) => {
            if (rf(rs) != 0) i = i + target
            else i = i + 1
          }
          case JumpNeg(rs, target) => {
            if (rf(rs) < 0) i = i + target
            else i = i + 1
          }
        }
      }
      rf
    }

    def run(prog: Program, state: Rep[timer]): Rep[timer] = {
      var state_ = state

      //
      // def tick(i: Int): Rep[Unit] = {
      //  ticks = readVar(ticks) + i
      // }

      def execute(
          f2e: Map[String, Port[Int]]
      ): (Rep[Int], Rep[Int], Rep[Boolean], Rep[Int]) = {
        val dst = f2e("dst").read
        val op1 = f2e("val1").read
        val op2 = f2e("val2").read
        val op = f2e("op").read

        val e_val =
          if (op == AddOp) op1 + op2
          else if (op == SubOp) op1 - op2
          else if (op == MulOp) op1 * op2
          else if (op == EqOp) if (op1 == op2) 1 else 0
          else if (op == NeqOp) if (op1 != op2) 1 else 0
          else if (op == LtOp) if (op1 < op2) 1 else 0
          else if (op == GtOp) if (op1 > op2) 1 else 0
          else if (op == LeOp) if (op1 <= op2) 1 else 0
          else if (op == GeOp) if (op1 >= op2) 1 else 0
          else 0

        /
        if (op == MulOp) {
          if (op1 == 0 || op2 == 0) state_ = tick(state_)
          else {state_ = tick(state_)}
        }
         */

        val e_dst = dst

        val pred = f2e("bpred").read
        val e_annul =
          if (pred == NotBranch) false
          else if (e_val == 0) pred == BranchTaken
          else if (e_val == 1) pred == BranchNotTaken
          else false

        val delta = f2e("bdelta").read

        val e_nextpc =
          if (e_val == 0) f2e("epc").read + 1
          else if (e_val == 1) f2e("epc").read + delta
          else f2e("epc").read + 1

        (e_dst, e_val, e_annul, e_nextpc)
      }

      def BTB(pc: Rep[Int]): Rep[Int] = pc + 1

      def BPredict(pc: Rep[Int]): Rep[Boolean] = false

      val regfile: Rep[RegFile] = state.rf
      var pc: Port[Int] = new Port[Int](0)

      var f2e: Map[String, Port[Int]] = Map(
        "dst" -> new Port[Int](0),
        "val1" -> new Port[Int](0),
        "val2" -> new Port[Int](0),
        "op" -> new Port[Int](AddOp),
        "bpred" -> new Port[Int](NotBranch),
        "bdelta" -> new Port[Int](0),
        "epc" -> new Port[Int](0)
      )

      var e2c: Map[String, Port[Int]] = Map(
        "dst" -> new Port[Int](0),
        "val" -> new Port[Int](0)
      )

      // Run until all Ports are default values
      while (
        (0 <= pc.read && pc.read < prog.length)
        || f2e.foldLeft(unit(false))((acc, kv) => acc || !kv._2.isDefault)
        || e2c.foldLeft(unit(false))((acc, kv) => acc || !kv._2.isDefault)
      ) {

        // pipeline update
        f2e.foreach { case (_, port) => port.update() }
        e2c.foreach { case (_, port) => port.update() }
        pc.update()

        // Commit stage
        if (!e2c("dst").isAmong(0))
          regfile(e2c("dst").read) = e2c("val").read

        // Execute stage
        val (e_dst, e_val, e_annul, e_nextpc) = execute(f2e)

        e2c("dst").write(e_dst)
        e2c("val").write(e_val)

        // Fetch stage

        val nextpc = BTB(pc.read)
        val predict = BPredict(pc.read)

        var stall = false

        if (prog.length == pc.read) {
          pc.freeze()
          f2e.foreach { case (_, port) => port.flush() }
        }

        for (i <- (0 until prog.length): Range) {
          if (i == pc.read) {
            prog(i) match {
              case Add(rd, rs1, rs2) => {
                stall = !((!e2c("dst").isAmong(rd, rs1, rs2)
                  || e2c("dst").isDefault)
                  && (!f2e("dst").isAmong(rd, rs1, rs2)
                    || f2e("dst").isDefault))

                f2e("dst").write(rd)
                f2e("val1").write(regfile(rs1))
                f2e("val2").write(regfile(rs2))
                f2e("op").write(AddOp)
                f2e("bpred").write(NotBranch)
                f2e("bdelta").write(1)
                f2e("epc").write(pc.read)
                pc.write(nextpc)
              }

              case Addi(rd, rs1, imm) => {
                stall = !((!e2c("dst").isAmong(rd, rs1)
                  || e2c("dst").isDefault)
                  && (!f2e("dst").isAmong(rd, rs1)
                    || f2e("dst").isDefault))

                f2e("dst").write(rd)
                f2e("val1").write(regfile(rs1))
                f2e("val2").write(imm)
                f2e("op").write(AddOp)
                f2e("bpred").write(NotBranch)
                f2e("bdelta").write(1)
                f2e("epc").write(pc.read)
                pc.write(nextpc)
              }

              case Sub(rd, rs1, rs2) => {
                stall = !((!e2c("dst").isAmong(rd, rs1, rs2)
                  || e2c("dst").isDefault)
                  && (!f2e("dst").isAmong(rd, rs1, rs2)
                    || f2e("dst").isDefault))

                f2e("dst").write(rd)
                f2e("val1").write(regfile(rs1))
                f2e("val2").write(regfile(rs2))
                f2e("op").write(SubOp)
                f2e("bpred").write(NotBranch)
                f2e("bdelta").write(1)
                f2e("epc").write(pc.read)
                pc.write(nextpc)
              }

              case Mul(rd, rs1, rs2) => {
                stall = !((!e2c("dst").isAmong(rd, rs1, rs2)
                  || e2c("dst").isDefault)
                  && (!f2e("dst").isAmong(rd, rs1, rs2)
                    || f2e("dst").isDefault))

                f2e("dst").write(rd)
                f2e("val1").write(regfile(rs1))
                f2e("val2").write(regfile(rs2))
                f2e("op").write(MulOp)
                f2e("bpred").write(NotBranch)
                f2e("bdelta").write(1)
                f2e("epc").write(pc.read)
                pc.write(nextpc)
              }

              case JumpNZ(rs, target) => {
                stall = !(e2c("dst").read != rs.id && f2e("dst").read != rs.id)

                f2e("dst").write(ZERO)
                f2e("val1").write(regfile(rs))
                f2e("val2").write(0)
                f2e("op").write(NeqOp)
                f2e("bpred").write(BranchNotTaken)
                f2e("bdelta").write(target)
                f2e("epc").write(pc.read)
                pc.write(nextpc)

              }
              case JumpNeg(rs, target) => {
                stall = !(e2c("dst").read != rs.id && f2e("dst").read != rs.id)

                f2e("dst").write(ZERO)
                f2e("val1").write(regfile(rs))
                f2e("val2").write(0)
                f2e("op").write(LtOp)
                f2e("bpred").write(BranchNotTaken)
                f2e("bdelta").write(target)
                f2e("epc").write(pc.read)
                pc.write(nextpc)
              }
            }
          }
        }

        if (e_annul) {
          pc.write(e_nextpc)
          f2e.foreach { case (_, p) => p.flush() }
        } else if (stall) {
          pc.freeze()
          f2e.foreach { case (_, p) => p.flush() }
        }
      }
      state_
    }
  }

  abstract class DslDriverX[A: Manifest, B: Manifest] extends DslDriverC[A, B] {
    q =>
    val main: String = ""

    override val codegen = new DslGenC with CCodeGenStruct {
      val IR: q.type = q

      override def emitAll(
          g: lms.core.Graph,
          name: String
      )(m1: Manifest[_], m2: Manifest[_]): Unit = {
        val ng = init(g)
        val efs = "" // quoteEff(g.block.ein)
        val stt = dce.statics.toList.map(quoteStatic).mkString(", ")
        prepareHeaders
        emitln("""
        |/*****************************************
        |Emitting C Generated Code
        |*******************************************/
        """.stripMargin)
        val src = run(name, ng)
        emitDefines(stream)
        emitHeaders(stream)
        emitFunctionDecls(stream)
        emitDatastructures(stream)
        emitFunctions(stream)
        emitInit(stream)
        emitln(s"\n/**************** $name ****************/")
        emit(src)
        emitln("""
        |/*****************************************
        |End of C Generated Code
        |*******************************************/
        |""".stripMargin)
        emit(main)
      }
    }
  }

  test("proc 1") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val N = A3
      val Temp = A2
      val F_n = A1
      val F_n_1 = A0
      val Fibprog = List(
        Addi(F_n, ZERO, 1),
        Addi(F_n_1, ZERO, 0),
        Addi(N, ZERO, 15),
        Addi(Temp, ZERO, 0),
        Add(Temp, F_n, F_n_1),
        Add(F_n_1, F_n, ZERO),
        Add(F_n, Temp, ZERO),
        Addi(N, N, -1),
        JumpNZ(N, -4),
        NOP // TODO: Last instruction Jump is buggy
      )
      val expected = expectedResult(Fibprog)
      override val main = constructMain(expected)
      def tick: Rep[timer => timer] = fun { t =>
        {
          var timer = t
          readVar(timer).ticks = readVar(timer).ticks + 1
          timer
        }
      }
      def snippet(initRegFile: Rep[timer]) = {

        val st = run(Fibprog, initRegFile)
        tick(st)
      }
    }
    exec("1", snippet.code)
  }

  test("proc rar hazard") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A1, A0, 1), //
        Addi(A3, A0, 2), // RAR
        Addi(A2, A4, 3) // NO RAR
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)

      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("rar_hazard", snippet.code)
  }

  test("proc waw hazard") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A0, ZERO, 1),
        Addi(A0, A0, 1), // WAW
        Addi(A3, A2, 2) // NO WAW
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("waw_hazard", snippet.code)
  }

  test("proc raw hazard") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A0, ZERO, 1),
        Addi(A1, A0, 1), // RAW
        Addi(A3, A2, 2) // NO RAW
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("raw_hazard", snippet.code)
  }

  test("proc war hazard") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A1, A0, 1), //
        Addi(A0, A3, 2), // WAR
        Addi(A2, A4, 3) // NO WAR
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("war_hazard", snippet.code)
  }

  test("proc annul") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A0, ZERO, 1),
        JumpNZ(A0, 2),
        Addi(A1, ZERO, 2),
        Addi(A2, ZERO, 3)
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("annul", snippet.code)
  }

  test("proc loop") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A0, ZERO, 3),
        Addi(A0, A0, -1),
        JumpNZ(A0, -1),
        NOP,
        NOP,
        NOP
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("loop_hazard", snippet.code)
  }

  test("proc hazard") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Addi(A0, ZERO, 1),
        Add(A1, A0, A0), // RAW
        Add(A2, A1, A0),
        Add(A3, A2, A1),
        Add(A0, A3, A2), // RAW
        Add(A1, A0, A3), // RAW, RAR
        Add(A2, A1, A0), // RAW, RAR
        Add(A3, A2, A1), // RAW, RAR
        Add(A3, A0, A3) // WAW, RAW
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("hazard", snippet.code)

  }

  test("proc stress") {
    // read from file 1.asm and get the program
    val snippet = new DslDriverX[timer, timer] with Interp {

      val filename = "src/out/1.asm"
      val program = readProgram(filename)
      val expected: Array[Int] = expectedResult(program)
      override val main = constructMain(expected)

      def snippet(initRegFile: Rep[timer]) = {
        run(program, initRegFile)
      }

    }
    exec("stress", snippet.code)
  }

  test("proc mul") {
    val snippet = new DslDriverX[timer, timer] with Interp {
      val prog = List(
        Mul(SECRET2, SECRET1, A0),
        Addi(A0, ZERO, 1),
        Addi(A1, ZERO, 2),
        Addi(A2, ZERO, 3),
        Addi(A3, ZERO, 4),
        Addi(A4, ZERO, 5),
        Addi(A5, ZERO, 6)
      )
      val expected = expectedResult(prog)
      override val main = constructMain(expected)
      def snippet(initRegFile: Rep[timer]) = {
        run(prog, initRegFile)
      }
    }
    exec("mul", snippet.code)

  }
}
