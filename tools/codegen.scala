// Generates Arbitrary instance for tuples and functions
// see src/main/scala/org/scalacheck/ArbitraryArities.scala

def csv(s: Seq[String]) = s mkString ", "
def typed(s: Seq[(String,String)]) = s map { case (v,t) => s"$v:$t"}
def wrapType(t: String, w:String) = s"$w[$t]"

def idents(name: String, i: Int) = 1 to i map(i0 => name+i0)
def types(i: Int) = idents("T",i).mkString(",")

def valsWithTypes(i: Int) =
  idents("t",i) zip
  idents("T",i) map
  { case (v,t) => s"$v:$t"} mkString ","

def wrappedArgs(wrapper: String, i: Int) = 
  csv(typed(
    idents(wrapper.take(1).toLowerCase,i) zip
    idents("T",i).map(wrapType(_,wrapper))
  ))

def generators(s: Seq[(String,String)]) =
  s map { case (v,a) => s"$v<-$a"} mkString "; "

def vals(i: Int) = csv(idents("t",i))

def coImplicits(i: Int) = (1 to i).map(n => s"co$n: Cogen[T$n]").mkString(",")

def fnArgs(i: Int) = (1 to i).map(n => s"t$n: T$n").mkString(",")

def nestedPerturbs(i: Int) =
  (1 to i).foldLeft("seed0") { (s, n) => s"co$n.perturb($s, t$n)" }

def fntype(i: Int) =
  s"(${types(i)}) => Z"

def arbfn(i: Int) = s"""
  /** Arbitrary instance of Function${i} */
  implicit def arbFunction${i}[${types(i)},Z](implicit g: Arbitrary[Z], ${coImplicits(i)}): Arbitrary[${fntype(i)}] =
    Arbitrary(Gen.function${i}(g.arbitrary))
"""

def genfn(i: Int) = s"""
  /** Gen creator for Function${i} */
  def function${i}[${types(i)},Z](g: Gen[Z])(implicit ${coImplicits(i)}): Gen[${fntype(i)}] =
    Gen.gen { (p, seed0) =>
      val f: ${fntype(i)} =
        (${fnArgs(i)}) => g.pureApply(p, ${nestedPerturbs(i)})
      new Gen.R[${fntype(i)}] {
        val result = Some(f)
        val seed = seed0.next
      }
    }
"""

def tuple(i: Int) = {
  val gens = generators(
    idents("t",i) zip
    idents("a",i).map(_+".arbitrary")
  )
  s"""
  /** Arbitrary instance of ${i}-Tuple */
  implicit def arbTuple$i[${types(i)}](implicit
    ${wrappedArgs("Arbitrary",i)}
  ): Arbitrary[Tuple$i[${types(i)}]]
    = Arbitrary(for {
        ${gens}
      } yield Tuple$i(${vals(i)}))
"""
}

def zip(i: Int) = {
  val gens = generators(idents("t",i) zip idents("g",i))
  def sieveCopy = idents("g",i) zip idents("t",i) map { case (g,t) => s"$g.sieveCopy($t)" } mkString " && "
  s"""
  /** Combines the given generators into one generator that produces a
   *  tuple of their generated values. */
  def zip[${types(i)}](
    ${wrappedArgs("Gen",i)}
  ): Gen[(${types(i)})] = {
    val g = for {
      ${gens}
    } yield (${vals(i)})
    g.suchThat {
      case (${vals(i)}) =>
        ${sieveCopy}
    }
  }
"""
}

def resultOf(i: Int) = {
  def delegate = idents("T",i).drop(1).map("_:"+_).mkString(",")
  s"""
  /** Takes a function and returns a generator that generates arbitrary
   *  results of that function by feeding it with arbitrarily generated input
   *  parameters. */
  def resultOf[${types(i)},R]
    (f: (${types(i)}) => R)
    (implicit
      ${wrappedArgs("Arbitrary",i)}
    ): Gen[R] = arbitrary[T1] flatMap {
      t => resultOf(f(t,$delegate))
    }
"""
}

def tupleCogen(i: Int) = {
  s"""
  implicit final def tuple${i}[${types(i)}](implicit ${wrappedArgs("Cogen",i)}): Cogen[Tuple$i[${types(i)}]] =
    Cogen((seed: rng.Seed, t: Tuple$i[${types(i)}]) =>
      ${idents("c", i).zipWithIndex.foldLeft("seed"){
        case (str, (c, n)) => s"$c.perturb($str, t._${n + 1})"
      }}
    )
"""
}

/*
  implicit def cogenFunction2[A: Arbitrary, B: Arbitrary, Z: Cogen]: Cogen[(A, B) => Z] =
    Cogen { (seed, f) =>
      val r0 = arbitrary[A].doPureApply(params, seed)
      val r1 = arbitrary[B].doPureApply(params, r0.seed)
      Cogen[Z].perturb(r1.seed, f(r0.retrieve.get, r1.retrieve.get))
    }
 */

def functionCogen(i: Int) = {
  def stanza(j: Int): String = {
    val s = if (j == 1) "seed" else s"r${j-1}.seed"
    s"      val r${j} = arbitrary[T$j].doPureApply(params, $s)\n"
  }

  val ap = (1 to i).map(j => s"r${j}.retrieve.get").mkString("f(", ", ", ")")
  val fn = s"Function$i[${types(i)}, Z]"

  s"""
  implicit final def function${i}[${types(i)}, Z](implicit ${wrappedArgs("Arbitrary",i)}, z: Cogen[Z]): Cogen[$fn] =
    Cogen { (seed: Seed, f: $fn) =>
${(1 to i).map(stanza).mkString}
      Cogen[Z].perturb(seed, $ap)
    }
"""
}


println(
  args(0) match {
    case "Arbitrary" =>

    s"""/**
Defines implicit [[org.scalacheck.Arbitrary]] instances for tuples and functions

Autogenerated using tools/codegen.sh
*/
package org.scalacheck

private[scalacheck] trait ArbitraryArities{
  // Functions //
  ${1 to 22 map arbfn mkString ""}

  // Tuples //
  ${1 to 22 map tuple mkString ""}
}"""

    case "Gen" =>

    s"""/**
Defines zip and resultOf for all arities

Autogenerated using tools/codegen.sh
*/
package org.scalacheck
private[scalacheck] trait GenArities{

  // genfn //
${1 to 22 map genfn mkString ""}

  // zip //
${1 to 22 map zip mkString ""}

  // resultOf //
  import Arbitrary.arbitrary
  def resultOf[T,R](f: T => R)(implicit a: Arbitrary[T]): Gen[R]
${2 to 22 map resultOf mkString ""}
}"""

    case "Cogen" =>

    s"""/**
Autogenerated using tools/codegen.sh
*/
package org.scalacheck
private[scalacheck] abstract class CogenArities{

  ${1 to 22 map tupleCogen mkString ""}

  import Arbitrary.arbitrary
  import Gen.Parameters.{ default => params }
  import rng.Seed

  ${1 to 22 map functionCogen mkString ""}

}"""


  }
)
