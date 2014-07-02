package examples

import impl._
import impl.logic._
import impl.runtime.Solver
import syntax.Symbols._

object Example01 {

  import impl.logic.Term._

  def main(args: Array[String]): Unit = {

    /**
     * Intervals
     */
    // Lattice Interval = Bot | Top | Range(Int, Int).

    // Interval.Leq(Bot, _).
    // Interval.Leq(Range(b1, e1), Range(b2, e2)) :- b2 <= b1, e2 >= e1.
    // Interval.Leq(_, Top).

    // Interval.Join(Bot, x, x).
    // Interval.Join(x, Bot, x).
    // Interval.Join(Range(b1, e1), Range(b2, e2), Range(b3, e3)) :- (max(e1, e2) - min(b1, b2)) <= 10.
    // Interval.Join(Range(b1, e1), Range(b2, e2), Top) :- (max(e1, e2) - min(b1, b2)) > 10.
    // Interval.Join(Top, _, Top).
    // Interval.Join(_, Top, Top).

    // Interval.Lift(i, Range(i)).

    // Notice: Strictness
    // Interval.Sum(Bot, _, Bot).
    // Interval.Sum(_, Bot, Bot).
    // Interval.Sum(Range(b1, e1), Range(b2, e2), Range(b1 + b2, e1 + e2)).
    // Interval.Sum(Top, _, Top).
    // Interval.Sum(_, Top, Top).

    /**
     * Sign
     */
    // Lattice Sign = Bot | Top | Neg | Zero | Pos.

    // Sign.Leq(Bot, _).
    // Sign.Leq(Neg, Neg).
    // Sign.Leq(Zero, Zero).
    // Sign.Leq(Pos, Pos).
    // Sign.Leq(_, Top).

    // Sign.Join(Bot, x, x).
    // Sign.Join(x, Bot, x).
    // Sign.Join(Neg, Neg, Neg).
    // Sign.Join(Zero, Zero, Zero).
    // Sign.Join(Pos, Pos, Pos).
    // Sign.Join(x, y, Top) :- x != y.
    // Sign.Join(Top, _, Top).
    // Sign.Join(_, Top, Top).

    // Sign.Lift(i, Neg) :- i < 0.
    // Sign.Lift(i, Zero) :- i == 0.
    // Sign.Lift(i, Pos) :- i > 0.

    // Notice: Strictness
    // Sign.Sum(Bot, _, Bot).
    // Sign.Sum(_, Bot, Bot).
    // Sign.Sum(Neg, Neg, Neg).
    // Sign.Sum(Neg, Zero, Neg).
    // Sign.Sum(Neg, Pos, Top).
    // Sign.Sum(Zero, Neg, Neg).
    // Sign.sum(Zero, Zero, Zero).
    // Sign.Sum(Zero, Pos, Pos).
    // Sign.Sum(Pos, Neg, Top).
    // Sign.Sum(Pos, Zero, Pos).
    // Sign.Sum(Pos, Pos, Pos).
    // Sign.Sum(Top, _, Top).
    // Sign.Sum(_, Top, Top).

    val Sum = Set(
      HornClause(Predicate("Sum".asP, List(Term.Constructor0("Bot"), Term.Variable("_"), Term.Constructor0("Bot"))), Set.empty),
      HornClause(Predicate("Sum".asP, List(Term.Variable("_"), Term.Constructor0("Bot"), Term.Constructor0("Bot"))), Set.empty)
    )

    val clauses = Set(
      // Constraint VarPointsTo(var, obj) :-
      //   New(var, obj).
      HornClause(Predicate("VarPointsTo".asP, List(Variable("var"), Variable("obj"))), Set(
        Predicate("New".asP, List(Variable("var"), Variable("obj")))
      )),

      // Constraint VarPointsTo(var1, value) :-
      //   Assign(var1, var2),
      //   VarPointsTo(var2, value).
      HornClause(Predicate("VarPointsTo".asP, List(Variable("var1"), Variable("value"))), Set(
        Predicate("Assign".asP, List(Variable("var1"), Variable("var2"))),
        Predicate("VarPointsTo".asP, List(Variable("var2"), Variable("value")))
      )),

      // Constraint VarPointsTo(var1, value) :-
      //   Load(var1, var2, field),
      //   VarPointsTo(var2, base),
      //   HeapPointsTo(base, field, value).
      HornClause(Predicate("VarPointsTo".asP, List(Variable("var1"), Variable("value"))), Set(
        Predicate("Load".asP, List(Variable("var1"), Variable("var2"), Variable("field"))),
        Predicate("VarPointsTo".asP, List(Variable("var2"), Variable("base"))),
        Predicate("HeapPointsTo".asP, List(Variable("base"), Variable("field"), Variable("value")))
      )),

      // Constraint HeapPointsTo(base, field, value) :-
      //   Store(var1, field, var2),
      //   VarPointsTo(var1, base),
      //   VarPointsTo(var2, value).
      HornClause(Predicate("HeapPointsTo".asP, List(Variable("base"), Variable("field"), Variable("value"))), Set(
        Predicate("Store".asP, List(Variable("var1"), Variable("field"), Variable("var2"))),
        Predicate("VarPointsTo".asP, List(Variable("var1"), Variable("base"))),
        Predicate("VarPointsTo".asP, List(Variable("var2"), Variable("value")))
      ))
    )

    val facts = Set(
      HornClause(Predicate("New".asP, List(Term.Constant(Value.Int(0)), Term.Constant(Value.Int(1)))), Set.empty)
    )

    val VariableType = Type.Constructor0("VariableT")
    val FieldType = Type.Constructor0("FieldType")
    val ObjectType = Type.Constructor0("ObjectType")

    val SignType = Type.Variant(IndexedSeq(
      Type.Constructor0("Bot"),
      Type.Constructor0("Top"),
      Type.Constructor0("Neg"),
      Type.Constructor0("Zero"),
      Type.Constructor0("Pos")
    ))

    // Lattice
    val LatticeType = Type.Lattice(
      elms = SignType,
      bot = Value.Constructor0("Bot"),
      order = Set.empty,
      join = Set.empty
    )

    val interpretation = Map(
      "New".asP -> Interpretation.Relation.In2(VariableType, ObjectType),
      "Assign".asP -> Interpretation.Relation.In2(VariableType, VariableType),
      "Load".asP -> Interpretation.Relation.In3(VariableType, VariableType, FieldType),
      "Store".asP -> Interpretation.Relation.In3(VariableType, FieldType, VariableType),

      "VarPointsTo".asP -> Interpretation.Relation.In2(VariableType, LatticeType),
      "HeapPointsTo".asP -> Interpretation.Relation.In3(ObjectType, FieldType, LatticeType),

      "Sum".asP -> Interpretation.Functional.Functional2(SignType, SignType, Sum)
    )

    val p = Program(clauses ++ facts, interpretation)

    println(p)


    val s = new Solver(p)
    s.solve()
  }

}
