package ca.uwaterloo.flix.language.library

import ca.uwaterloo.flix.language.ast.Name
import ca.uwaterloo.flix.language.ast.TypedAst.Type
import ca.uwaterloo.flix.language.ast.TypedAst.Type._

import scala.collection.immutable

object FOpt {

  /**
    * All Opt operations.
    */
  val Ops: immutable.Map[Name.Resolved, OptOperator] = List(
    // TODO: None/Some
    // TODO: isNone
    // TODO: isSome

    // TODO: null
    // TODO: get
    // TODO: getOrElse
    // TODO:  or
    // TODO: orElse: (Opt[A], Opt[A] => Opt[A]
    // TODO: filter
    // TODO: exists
    // TODO: forall

    // TODO: map2: ((Opt, Opt) => A, Opt, Opt) => Opt
    // TODO: flatMap2: see above

    "Opt/map" -> map,
    "Opt/map2" -> map,
    "Opt/flatMap" -> flatMap,
    "Opt/toList" -> toList,
    "Opt/toSet" -> toSet
  ).map {
    case (name, op) => Name.Resolved.mk(name) -> op
  }.toMap

  /**
    * A common super-type for all option operations.
    */
  sealed trait OptOperator extends LibraryOperator

  /**
    * Generic type variables.
    */
  val A = Type.Var("A")
  val B = Type.Var("B")
  val C = Type.Var("C")

  /////////////////////////////////////////////////////////////////////////////
  // Basic Operations                                                        //
  /////////////////////////////////////////////////////////////////////////////
  object map extends OptOperator {
    val tpe = (A ~> B, Opt(A)) ~> Opt(B)
  }

  object map2 extends OptOperator {
    val tpe = ((A, B) ~> C, Opt(A), Opt(B)) ~> Opt(C)
  }

  object flatMap extends OptOperator {
    val tpe = (A ~> Opt(B), Opt(A)) ~> Opt(B)
  }

  object flatMap2 extends OptOperator {
    val tpe = ((A, B) ~> Opt(C), Opt(A), Opt(B)) ~> Opt(C)
  }

  object toList extends OptOperator {
    val tpe = Opt(A) ~> Lst(A)
  }

  object toSet extends OptOperator {
    val tpe = Opt(A) ~> Set(A)
  }

}
