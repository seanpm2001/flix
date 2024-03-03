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
import ca.uwaterloo.flix.language.ast.{Rigidity, RigidityEnv, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.phase.unification.FastBoolUnification.{ConflictException, Equation, Term, TooComplexException}
import ca.uwaterloo.flix.language.phase.unification.UnificationError.TooComplex
import ca.uwaterloo.flix.util.{InternalCompilerException, Result}
import ca.uwaterloo.flix.util.collection.Bimap

import scala.collection.mutable

object EffUnification2 {

  /**
    * Returns the most general unifier of the pairwise unification equations in `l`.
    */
  def unifyAll(l: List[(Type, Type)], renv: RigidityEnv, loc: SourceLocation)(implicit flix: Flix): Result[Substitution, UnificationError] = {
    // Compute a bi-directional from type variables to ints.
    implicit val bimap: Bimap[Type.Var, Int] = mkBidirectionalVarMap(l)

    // Translate all unification problems from equations on types to equations on terms.
    val equations = l.map(p => toEquation(p._1, p._2, SourceLocation.Unknown)(renv, bimap)) // TODO: Argument needs source location. Probably argument should be TypeConstraint?

    // Compute the most-general unifier of all the term equations.
    FastBoolUnification.solveAll(equations) match {
      case Result.Ok(subst) => Result.Ok(fromBoolSubst(subst))

      case Result.Err((ex: ConflictException, _, _)) => // TODO: Use loc from ex.
        val tpe1 = fromTerm(ex.x, loc)
        val tpe2 = fromTerm(ex.y, loc)
        Result.Err(UnificationError.MismatchedEffects(tpe1, tpe2))

      case Result.Err((ex: TooComplexException, _, _)) => // TODO: Use loc from ex.
        val tpe1 = ??? // TODO
        val tpe2 = ??? // TODO
        Result.Err(UnificationError.TooComplex(tpe1, tpe2))

      case Result.Err((ex, _, _)) => throw ex
    }
  }

  /**
    * Returns a bi-directional from type variables to ints computed from the given list of unification equations `l`.
    */
  private def mkBidirectionalVarMap(l: List[(Type, Type)]): Bimap[Type.Var, Int] = {
    // Find all type variables that occur in anywhere in `l`.
    val allVars = mutable.Set.empty[Type.Var]
    for ((t1, t2) <- l) {
      allVars ++= t1.typeVars
      allVars ++= t2.typeVars
    }

    // Construct the map from type variables to ints.
    val forward = allVars.foldLeft(Map.empty[Type.Var, Int]) {
      case (macc, tvar) => macc + (tvar -> tvar.sym.id)
    }

    // Construct the reverse map from ints to type variables.
    val backward = allVars.foldLeft(Map.empty[Int, Type.Var]) {
      case (macc, tvar) => macc + (tvar.sym.id -> tvar)
    }
    Bimap(forward, backward)
  }

  /**
    * Translates the given unification equation on types `p` into a unification equation on terms.
    */
  private def toEquation(p: (Type, Type, SourceLocation))(implicit renv: RigidityEnv, m: Bimap[Type.Var, Int]): Equation = {
    val (tpe1, tpe2, loc) = p
    Equation.mk(toTerm(tpe1), toTerm(tpe2), loc)
  }

  /**
    * Returns the given type `t` as term.
    *
    * Uses the given bimap `m` to map type variables to term variables.
    *
    * The rigidity environment `renv` is used to map rigid type variables to constants and flexible type variables to term variables.
    */
  private def toTerm(t: Type)(implicit renv: RigidityEnv, m: Bimap[Type.Var, Int]): Term = Type.eraseTopAliases(t) match {
    case Type.Pure => Term.True
    case Type.Univ => Term.False

    case t: Type.Var => m.getForward(t) match {
      case None => throw InternalCompilerException(s"Unexpected unbound type variable: '$t'.", t.loc)
      case Some(x) => renv.get(t.sym) match {
        case Rigidity.Flexible => Term.Var(x) // A flexible variable is a real variable.
        case Rigidity.Rigid => Term.Cst(x) // A rigid variable is a constant.
      }
    }

    case Type.Apply(Type.Cst(TypeConstructor.Complement, _), tpe1, _) => Term.mkNot(toTerm(tpe1))
    case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.Union, _), tpe1, _), tpe2, _) => Term.mkAnd(toTerm(tpe1), toTerm(tpe2))
    case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.Intersection, _), tpe1, _), tpe2, _) => Term.mkOr(toTerm(tpe1), toTerm(tpe2))

    case _ => throw InternalCompilerException(s"Unexpected type: '$t'.", t.loc)
  }

  /**
    * Returns a Boolean substitution as a regular type substitution.
    */
  private def fromBoolSubst(s: FastBoolUnification.BoolSubstitution)(implicit m: Bimap[Type.Var, Int]): Substitution = {
    Substitution(s.m.foldLeft(Map.empty[Symbol.KindedTypeVarSym, Type]) {
      case (macc, (k, v)) => macc + (m.getBackward(k).get.sym -> fromTerm(v, SourceLocation.Unknown))
    })
  }

  /**
    * Returns the given term `t` as a type.
    *
    * Uses the given bimap `m` to map term variables to type variables.
    * Uses the given source location `loc` as the source location for all sub-terms.
    *
    * Both constants and variables are mapped back to type variables. The rigidity environment, in the type world,
    * distinguishes their rigidity or flexibility.
    */
  private def fromTerm(t: Term, loc: SourceLocation)(implicit m: Bimap[Type.Var, Int]): Type = t match {
    case Term.True => Type.Pure
    case Term.False => Type.Univ
    case Term.Cst(c) => m.getBackward(c).get // Safe: We never introduce new variables.
    case Term.Var(x) => m.getBackward(x).get // Safe: We never introduce new variables.
    case Term.Not(t) => Type.mkComplement(fromTerm(t, loc), loc)
    case Term.And(csts, vars, rest) =>
      val ts = csts.toList.map(fromTerm(_, loc)) ++ vars.toList.map(fromTerm(_, loc)) ++ rest.map(fromTerm(_, loc))
      Type.mkIntersection(ts, loc)
    case Term.Or(ts) => Type.mkUnion(ts.map(fromTerm(_, loc)), loc)
  }

}