/*
 *  Copyright 2024 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.unification

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{RigidityEnv, SourceLocation, Symbol, Type}
import ca.uwaterloo.flix.util.collection.Bimap
import ca.uwaterloo.flix.util.{InternalCompilerException, Result}

import scala.collection.immutable.SortedSet
import scala.collection.mutable

object EffUnification2 {

  /**
    * Returns the most general unifier of the all the pairwise unification problems in `l`.
    *
    * Note: A type in `l` must not contain any associated effects.
    */
  def unifyAll(l: List[(Type, Type)], renv0: RigidityEnv)(implicit flix: Flix): Result[Substitution, UnificationError] = {
    // If the list is empty we can return immediately.
    if (l.isEmpty) {
      return Result.Ok(Substitution.empty)
    }

    val allVars = mutable.Set.empty[Type.Var]
    for ((t1, t2) <- l) {
      allVars ++= t1.typeVars
      allVars ++= t2.typeVars
    }

    val forward = allVars.foldLeft(Map.empty[Type.Var, Int]) {
      case (macc, tvar) => macc + (tvar -> tvar.sym.id)
    }
    val backward = allVars.foldLeft(Map.empty[Int, Type.Var]) {
      case (macc, tvar) => macc + (tvar.sym.id -> tvar)
    }
    implicit val bimap: Bimap[Type.Var, Int] = Bimap(forward, backward)

    var currentEqns: List[Equation] = l.map {
      case (tpe1, tpe2) => Equation(fromType(tpe1), fromType(tpe2))
    }
    var currentSubst: LocalSubstitution = LocalSubstitution.empty

    try {
      val (nextEqns, nextSubst) = unitPropagate(currentEqns, currentSubst)
      currentSubst = nextSubst
      currentEqns = nextEqns

      val restSubst = boolUnify(currentEqns, Set.empty)
      val resultSubst = currentSubst @@ restSubst

      Result.Ok(resultSubst.toSubst(bimap))
    } catch {
      case InternalFailure(t1, t2) =>
        val tpe1 = toType(t1, SourceLocation.Unknown)
        val tpe2 = toType(t2, SourceLocation.Unknown)
        Result.Err(UnificationError.MismatchedEffects(tpe1, tpe2))
    }
  }

  private def toType(t0: Term, loc: SourceLocation)(implicit m: Bimap[Type.Var, Int]): Type = t0 match {
    case Term.True => Type.Pure
    case Term.False => Type.Univ
    case Term.Var(x) => m.getBackward(x).get
    case Term.Not(t) => Type.mkComplement(toType(t, loc), loc)
    case Term.And(ts) => Type.mkIntersection(ts.map(toType(_, loc)), loc)
    case Term.Or(ts) => Type.mkUnion(ts.map(toType(_, loc)), loc)
  }

  private def fromType(t: Type): Term = ???

  private case class InternalFailure(x: Term, y: Term) extends RuntimeException

  // Saturates all unit clauses.
  private def unitPropagate(eqns: List[Equation], subst0: LocalSubstitution): (List[Equation], LocalSubstitution) = {
    var currentSubst = subst0

    val (initialGround, initialNonGround) = eqns.partition(isGround)
    var currentGround = initialGround
    var currentNonGround = initialNonGround
    while (currentGround.nonEmpty) {
      currentSubst = extendSubstWithGround(currentGround, currentSubst) // TODO: rigidity
      val (nextGround, nextNonGround) = currentNonGround.partition(isGround)
      currentGround = nextGround
      currentNonGround = nextNonGround
    }

    (currentNonGround, currentSubst)
  }

  // x = true, x = false, or mirrored. + x flexible
  private def isGround(eq: Equation): Boolean = eq match {
    case Equation(Term.Var(x), Term.True) => true // TODO: Rest
    case _ => false
  }

  private def extendSubstWithGround(eqns: List[Equation], subst: LocalSubstitution): LocalSubstitution = eqns match {
    case Nil => subst
    case x :: xs => extendSubstWithGround(xs, extendSubstWithSingleGround(x, subst))
  }

  private def extendSubstWithSingleGround(eq: Equation, subst: LocalSubstitution): LocalSubstitution = eq match {
    case Equation(Term.Var(x), t0) => subst.m.get(x) match {
      case None => subst.extended(x, t0)
      case Some(t1) => if (t0 == t1) subst else throw InternalFailure(t0, t1)
    }
    case Equation(t0, Term.Var(x)) => subst.m.get(x) match {
      case None => subst.extended(x, t0)
      case Some(t1) => if (t0 == t1) subst else throw InternalFailure(t0, t1)
    }
    case _ => throw InternalCompilerException(s"Unexpected equation: '$eq'.", SourceLocation.Unknown)
  }

  private case class Equation(t1: Term, t2: Term)

  private def boolUnify(l: List[Equation], renv: Set[Int])(implicit flix: Flix): LocalSubstitution = l match {
    case Nil => LocalSubstitution.empty
    case Equation(t1, t2) :: xs =>
      val subst = booleanUnification(t1, t2, renv)
      val subst1 = boolUnify(xs.map(eq => subst.apply(eq)), renv)
      subst @@ subst1 // TODO: order?
  }

  private def booleanUnification(t1: Term, t2: Term, renv: Set[Int])(implicit flix: Flix): LocalSubstitution = {
    // The boolean expression we want to show is false.
    val query = Term.mkXor(t1, t2)

    // Compute the variables in the query.
    val typeVars = query.freeVars.toList

    // Compute the flexible variables.
    val flexibleTypeVars = typeVars.filterNot(renv.contains)

    // Determine the order in which to eliminate the variables.
    val freeVars = flexibleTypeVars

    // Eliminate all variables.
    val subst = successiveVariableElimination(query, freeVars)

    //    if (!subst.isEmpty) {
    //      val s = subst.toString
    //      val len = s.length
    //      if (len > 50) {
    //        println(s.substring(0, Math.min(len, 300)))
    //        println()
    //      }
    //    }

    subst
  }

  private def successiveVariableElimination(f: Term, flexvs: List[Int])(implicit flix: Flix): LocalSubstitution = flexvs match {
    case Nil =>
      // Determine if f is unsatisfiable when all (rigid) variables are made flexible.
      if (!satisfiable(f))
        LocalSubstitution.empty
      else
        throw BoolUnificationException()

    case x :: xs =>
      val t0 = LocalSubstitution.singleton(x, Term.False)(f)
      val t1 = LocalSubstitution.singleton(x, Term.True)(f)
      val se = successiveVariableElimination(Term.mkAnd(t0, t1), xs)

      val f1 = Term.mkOr(se(t0), Term.mkAnd(Term.Var(x), Term.mkNot(se(t1))))
      val st = LocalSubstitution.singleton(x, f1)
      st ++ se
  }

  private def satisfiable(t: Term): Boolean = ??? // TODO

  private sealed trait Term {

    final def freeVars: SortedSet[Int] = this match {
      case Term.True => SortedSet.empty
      case Term.False => SortedSet.empty
      case Term.Var(x) => SortedSet(x)
      case Term.Not(t) => t.freeVars
      case Term.And(ts) => ts.foldLeft(SortedSet.empty[Int])(_ ++ _.freeVars)
      case Term.Or(ts) => ts.foldLeft(SortedSet.empty[Int])(_ ++ _.freeVars)
    }

    final def size: Int = this match {
      case Term.True => 0
      case Term.False => 0
      case Term.Var(_) => 0
      case Term.Not(t) => t.size + 1
      case Term.And(ts) => ts.map(_.size).sum + (ts.length - 1)
      case Term.Or(ts) => ts.map(_.size).sum + (ts.length - 1)
    }

    override def toString: String = this match {
      case Term.True => "true"
      case Term.False => "false"
      case Term.Var(x) => s"x$x"
      case Term.Not(f) => f match {
        case Term.Var(x) => s"¬x$x"
        case _ => s"¬($f)"
      }
      case Term.And(ts) => s"(${ts.mkString(" ∧ ")})"
      case Term.Or(ts) => s"(${ts.mkString(" ∨ ")})"
    }

  }

  private object Term {

    case object True extends Term

    case object False extends Term

    case class Var(x: Int) extends Term

    case class Not(t: Term) extends Term

    case class And(ts: List[Term]) extends Term {
      assert(ts.length >= 2)
    }

    case class Or(ts: List[Term]) extends Term {
      assert(ts.length >= 2)
    }

    final def mkNot(t: Term): Term = t match {
      case True => False
      case False => True
      case Not(t) => t
      case _ => Not(t)
    }

    final def mkAnd(x: Term, y: Term): Term = (x, y) match {
      case (False, _) => False
      case (_, False) => False
      case (True, _) => y
      case (_, True) => y
      case _ => mkAnd(List(x, y))
    }

    final def mkOr(x: Term, y: Term): Term = (x, y) match {
      case (True, _) => True
      case (_, True) => True
      case (False, _) => y
      case (_, False) => x
      case _ => mkOr(List(x, y))
    }

    final def mkAnd(ts: List[Term]): Term = {
      val varTerms = mutable.Set.empty[Term]
      val nonVarTerms = mutable.ListBuffer.empty[Term]
      for (t <- ts) {
        t match {
          case True => // nop
          case False => return False
          case x@Term.Var(_) => varTerms += x
          case And(ts0) =>
            for (t0 <- ts0) {
              t0 match {
                case True => // nop
                case False => return False
                case x@Term.Var(_) => varTerms += x
                case _ => nonVarTerms += t
              }
            }
          case _ => nonVarTerms += t
        }
      }

      varTerms.toList ++ nonVarTerms.toList match {
        case Nil => True
        case x :: Nil => x
        case xs => And(xs)
      }
    }

    final def mkOr(ts: List[Term]): Term = {
      val varTerms = mutable.Set.empty[Term]
      val nonVarTerms = mutable.ListBuffer.empty[Term]
      for (t <- ts) {
        t match {
          case True => return False
          case False => // nop
          case x@Term.Var(_) => varTerms += x
          case Or(ts0) =>
            for (t0 <- ts0) {
              t0 match {
                case True => return True
                case False => // nop
                case x@Term.Var(_) => varTerms += x
                case _ => nonVarTerms += t
              }
            }
          case _ => nonVarTerms += t
        }
      }

      varTerms.toList ++ nonVarTerms.toList match {
        case Nil => False
        case x :: Nil => x
        case xs => And(xs)
      }
    }

    final def mkXor(x: Term, y: Term): Term = mkOr(mkAnd(x, mkNot(y)), mkAnd(mkNot(x), y))

  }

  // TODO: Rename to BoolSubst
  private object LocalSubstitution {
    val empty: LocalSubstitution = LocalSubstitution(Map.empty)

    def singleton(x: Int, t: Term): LocalSubstitution = empty.extended(x, t)
  }

  private case class LocalSubstitution(m: Map[Int, Term]) {
    def apply(t: Term): Term = t match {
      case Term.True => Term.True
      case Term.False => Term.False
      case Term.Var(x) => m.get(x) match {
        case None => Term.Var(x)
        case Some(t0) => t0
      }
      case Term.Not(t) => Term.mkNot(this.apply(t))
      case Term.And(ts) => Term.mkAnd(ts.map(this.apply))
      case Term.Or(ts) => Term.mkOr(ts.map(this.apply))
    }

    def apply(eq: Equation): Equation = eq match {
      case Equation(t1, t2) => Equation(apply(t1), apply(t2))
    }

    def extended(x: Int, t: Term): LocalSubstitution = LocalSubstitution(m + (x -> t))

    def ++(that: LocalSubstitution): LocalSubstitution = {
      if (this.m.isEmpty) {
        that
      } else if (that.m.isEmpty) {
        this
      } else {
        LocalSubstitution(
          this.m ++ that.m.filter(kv => !this.m.contains(kv._1))
        )
      }
    }

    def @@(that: LocalSubstitution): LocalSubstitution = ???

    def toSubst(implicit bimap: Bimap[Type.Var, Int]): Substitution = {
      Substitution(m.foldLeft(Map.empty[Symbol.KindedTypeVarSym, Type]) {
        case (macc, (k, v)) => macc + (bimap.getBackward(k).get.sym -> toType(v, SourceLocation.Unknown))
      })
    }
  }

}
